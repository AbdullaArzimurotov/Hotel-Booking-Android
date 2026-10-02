#!/usr/bin/env python3
"""Локальный запуск на macOS и Windows без изменения существующих баз PostgreSQL."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import tempfile
import getpass
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / ".local"
DATA = LOCAL / "postgres"
CONFIG = LOCAL / "credentials.json"
PORT = 55432
WINDOWS = sys.platform == "win32"


def postgres_bin():
    """Путь задаётся локально; каталог SDK и пути другого компьютера не копируются."""
    if os.environ.get("HOTEL_PG_BIN"):
        return Path(os.environ["HOTEL_PG_BIN"])
    if WINDOWS:
        return Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "PostgreSQL/18/bin"
    return Path("/Applications/Postgres.app/Contents/Versions/18/bin")


def java_home():
    if os.environ.get("JAVA_HOME"):
        return Path(os.environ["JAVA_HOME"])
    if WINDOWS:
        return Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Android/Android Studio/jbr"
    return Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")


def pg_tool(name):
    return postgres_bin() / (name + (".exe" if WINDOWS else ""))


def postgres_options():
    # В Windows нет Unix-сокетов. TCP ограничен локальным адресом на обеих ОС.
    options = f"-h 127.0.0.1 -p {PORT} -c max_connections=20 -c shared_buffers=64MB"
    if not WINDOWS:
        # Только TCP: длинный Unicode-путь курса превышает лимит Unix socket (103 байта).
        options += ' -k ""'
    return options


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def credentials():
    """Создаёт приватные пароли только для нового кластера; чужой конфиг отклоняется."""
    LOCAL.mkdir(mode=0o700, exist_ok=True)
    if CONFIG.exists():
        result = json.loads(CONFIG.read_text())
        if result.get("project") != "HotelCoursework" or result.get("port") != PORT:
            raise RuntimeError("Unexpected local credentials file; refusing to change a cluster")
        # В SQL ниже подставляются только автоматически сгенерированные hex-значения.
        for key in ("admin_password", "app_password"):
            value = result.get(key)
            if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{48}", value):
                raise RuntimeError("Invalid local credential format; no database was changed")
        return result
    if DATA.exists():
        raise RuntimeError("Database directory exists without credentials; refusing to overwrite it")
    result = {"project": "HotelCoursework", "port": PORT,
              "admin_password": secrets.token_hex(24), "app_password": secrets.token_hex(24)}
    descriptor = os.open(CONFIG, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump(result, stream)
    return result


def psql(sql, config, database="postgres"):
    """Передаёт SQL через stdin, пароль через окружение, а не через командную строку."""
    environment = dict(os.environ, PGPASSWORD=config["admin_password"], PGCLIENTENCODING="UTF8")
    return run([pg_tool("psql"), "-h", "127.0.0.1", "-p", PORT,
                "-U", "hotel_admin", "-d", database, "-v", "ON_ERROR_STOP=1", "-At"],
               input=sql, text=True, encoding="utf-8", capture_output=True, env=environment).stdout.strip()


def port_available():
    with socket.socket() as test:
        try:
            test.bind(("127.0.0.1", PORT))
            return True
        except OSError:
            return False


def start_db():
    """Инициализирует только .local/postgres, проверяет владельца порта и создаёт роль/базу."""
    if not pg_tool("initdb").is_file():
        raise RuntimeError("PostgreSQL 18 binaries not found; set HOTEL_PG_BIN (see docs/WINDOWS.md)")
    config = credentials()
    if not (DATA / "PG_VERSION").exists():
        if not port_available():
            raise RuntimeError("Port 55432 is occupied; no existing process was stopped")
        descriptor, name = tempfile.mkstemp(dir=LOCAL, prefix="pg-init-", suffix=".secret")
        try:
            with os.fdopen(descriptor, "w") as stream:
                stream.write(config["admin_password"] + "\n")
            run([pg_tool("initdb"), "-D", DATA, "-U", "hotel_admin", "--encoding=UTF8",
                 "--locale=C", "--auth-local=scram-sha-256", "--auth-host=scram-sha-256", "--pwfile", name])
        finally:
            Path(name).unlink(missing_ok=True)
    status = subprocess.run([str(pg_tool("pg_ctl")), "-D", str(DATA), "status"], capture_output=True)
    if status.returncode:
        if not port_available():
            raise RuntimeError("Port 55432 is occupied; no existing process was stopped")
        run([pg_tool("pg_ctl"), "-D", DATA, "-l", LOCAL / "postgres.log", "-w",
             "-o", postgres_options(), "start"])
    actual = psql("SHOW data_directory;", config)
    if Path(actual).resolve() != DATA.resolve():
        raise RuntimeError("The port belongs to a different database cluster")
    if not psql("SELECT 1 FROM pg_roles WHERE rolname='hotel_app';", config):
        password = config["app_password"]  # Проверенное hex-значение, а не пользовательский SQL.
        psql(f"CREATE ROLE hotel_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '{password}';", config)
    if not psql("SELECT 1 FROM pg_database WHERE datname='hotel_coursework';", config):
        psql("CREATE DATABASE hotel_coursework OWNER hotel_app;", config)
    print("PostgreSQL ready: 127.0.0.1:55432/hotel_coursework (isolated project cluster)", flush=True)


def jwt_secret():
    """Постоянный приватный ключ; смена перезапуска сервера не разлогинивает всех."""
    LOCAL.mkdir(mode=0o700, exist_ok=True)
    path = LOCAL / "jwt.secret"
    if not path.exists():
        descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            stream.write(secrets.token_hex(32))
    value = path.read_text().strip()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise RuntimeError("Invalid local JWT secret")
    return value


def server(admin=False):
    """Запускает свой PostgreSQL, собирает Ktor и передаёт локальные настройки процессу JVM."""
    start_db()
    config = credentials()
    environment = dict(os.environ, DB_URL=f"jdbc:postgresql://127.0.0.1:{PORT}/hotel_coursework",
                       DB_USER="hotel_app", DB_PASSWORD=config["app_password"], HOST="127.0.0.1", PORT="8080")
    jbr = java_home()
    if not (jbr / "bin" / ("java.exe" if WINDOWS else "java")).is_file():
        raise RuntimeError("Java not found; set JAVA_HOME to Android Studio jbr (see README.md)")
    environment["JAVA_HOME"] = str(jbr)
    environment["JWT_SECRET"] = jwt_secret()
    extra = []
    if admin:
        environment["ADMIN_EMAIL"] = input("Email администратора: ").strip()
        environment["ADMIN_NAME"] = input("Имя администратора: ").strip()
        password = getpass.getpass("Пароль (10–128 символов): ")
        if password != getpass.getpass("Повторите пароль: "):
            raise RuntimeError("Passwords do not match")
        environment["ADMIN_PASSWORD"] = password
        extra = ["create-admin"]
    # Сначала собираем дистрибутив: постоянный Gradle daemon не занимает память.
    if WINDOWS:
        # Относительное имя .bat и cwd избегают проблем cmd.exe с пробелами в пути.
        command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c"]
        run(command + ["gradlew.bat", "--no-daemon", "installDist"], cwd=ROOT / "server", env=environment)
        binary_dir = ROOT / "server/build/install/HotelCourseworkServer/bin"
        run(command + ["HotelCourseworkServer.bat"] + extra, cwd=binary_dir, env=environment)
        return
    run([ROOT / "server/gradlew", "--no-daemon", "installDist"], cwd=ROOT / "server", env=environment)
    executable = ROOT / "server/build/install/HotelCourseworkServer/bin/HotelCourseworkServer"
    os.chdir(ROOT / "server")
    os.execve(executable, [str(executable)] + extra, environment)


def usb():
    """Reverse только для единственного подключённого телефона; чужие приложения не меняет."""
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / ("AppData/Local/Android/Sdk" if WINDOWS else "Library/Android/sdk"))))
    adb = sdk / "platform-tools" / ("adb.exe" if WINDOWS else "adb")
    result = run([adb, "devices"], capture_output=True, text=True).stdout
    phones = [line.split()[0] for line in result.splitlines()[1:]
              if len(line.split()) == 2 and line.split()[1] == "device" and not line.startswith("emulator-")]
    if len(phones) != 1:
        raise RuntimeError("Connect exactly one authorized USB phone")
    run([adb, "-s", phones[0], "reverse", "tcp:8080", "tcp:8080"])
    print("USB ready. In debug app choose USB: 127.0.0.1:8080")


def test_sql():
    """Создаёт отдельную тестовую БД. Основная не очищается; тестовая сохраняется для аудита."""
    start_db()
    config = credentials()
    database = "hotel_coursework_test_" + secrets.token_hex(6)
    psql(f"CREATE DATABASE {database} OWNER hotel_app;", config)
    environment = dict(os.environ, JAVA_HOME=str(java_home()),
                       HOTEL_TEST_DB_URL=f"jdbc:postgresql://127.0.0.1:{PORT}/{database}",
                       HOTEL_TEST_DB_PASSWORD=config["app_password"])
    command = ([os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", "gradlew.bat"] if WINDOWS else [ROOT / "server/gradlew"])
    run(command + ["--no-daemon", "test", "--rerun-tasks"], cwd=ROOT / "server", env=environment)
    print("SQL tests used separate database:", database, "(retained, no primary data removed)")


def smoke():
    """Проверяет HTTP-сервер и реальную готовность базы, не имитирует успешный ответ."""
    for path in ("/health", "/api/v1/health"):
        with urllib.request.urlopen("http://127.0.0.1:8080" + path, timeout=8) as response:
            body = json.load(response)
            assert response.status == 200 and body["status"] == "ok"
            if path.endswith("v1/health"):
                assert body["database"] == "connected"
            print(path, json.dumps(body, ensure_ascii=False))


def stop_db():
    """Останавливает кластер по точному DATA-пути; данные и Windows-службы не удаляются."""
    if not CONFIG.exists() or not (DATA / "PG_VERSION").exists():
        print("Project database has not been initialized")
        return
    run([pg_tool("pg_ctl"), "-D", DATA, "-m", "fast", "-w", "stop"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["start-db", "stop-db", "server", "smoke", "usb", "create-admin", "test-sql"])
    command = parser.parse_args().command
    try:
        {"start-db": start_db, "stop-db": stop_db, "server": server, "smoke": smoke,
         "usb": usb, "create-admin": lambda: server(admin=True), "test-sql": test_sql}[command]()
    except (RuntimeError, OSError, subprocess.CalledProcessError, urllib.error.URLError) as error:
        # Не выводим stdin дочернего процесса, переменные окружения и пароли.
        print("Development command failed:", str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
