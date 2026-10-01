#!/usr/bin/env python3
"""Local development commands. Never reads or modifies the user's existing PG cluster."""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / ".local"
DATA = LOCAL / "postgres"
CONFIG = LOCAL / "credentials.json"
PORT = 55432
PG_BIN = Path(os.environ.get("HOTEL_PG_BIN", "/Applications/Postgres.app/Contents/Versions/18/bin"))
JBR = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def credentials():
    LOCAL.mkdir(mode=0o700, exist_ok=True)
    if CONFIG.exists():
        result = json.loads(CONFIG.read_text())
        if result.get("project") != "HotelCoursework" or result.get("port") != PORT:
            raise RuntimeError("Unexpected local credentials file; refusing to change a cluster")
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
    environment = dict(os.environ, PGPASSWORD=config["admin_password"])
    return run([PG_BIN / "psql", "-h", "127.0.0.1", "-p", PORT,
                "-U", "hotel_admin", "-d", database, "-v", "ON_ERROR_STOP=1", "-At"],
               input=sql, text=True, capture_output=True, env=environment).stdout.strip()


def port_available():
    with socket.socket() as test:
        try:
            test.bind(("127.0.0.1", PORT))
            return True
        except OSError:
            return False


def start_db():
    if not (PG_BIN / "initdb").is_file():
        raise RuntimeError("Postgres.app 18 binaries not found; set HOTEL_PG_BIN")
    config = credentials()
    if not (DATA / "PG_VERSION").exists():
        if not port_available():
            raise RuntimeError("Port 55432 is occupied; no existing process was stopped")
        descriptor, name = tempfile.mkstemp(dir=LOCAL, prefix="pg-init-", suffix=".secret")
        try:
            with os.fdopen(descriptor, "w") as stream:
                stream.write(config["admin_password"] + "\n")
            run([PG_BIN / "initdb", "-D", DATA, "-U", "hotel_admin", "--encoding=UTF8",
                 "--locale=C", "--auth-local=scram-sha-256", "--auth-host=scram-sha-256", "--pwfile", name])
        finally:
            Path(name).unlink(missing_ok=True)
    status = subprocess.run([str(PG_BIN / "pg_ctl"), "-D", str(DATA), "status"], capture_output=True)
    if status.returncode:
        if not port_available():
            raise RuntimeError("Port 55432 is occupied; no existing process was stopped")
        run([PG_BIN / "pg_ctl", "-D", DATA, "-l", LOCAL / "postgres.log", "-w",
             "-o", f"-h 127.0.0.1 -p {PORT} -k /tmp -c max_connections=20 -c shared_buffers=64MB", "start"])
    actual = psql("SHOW data_directory;", config)
    if Path(actual).resolve() != DATA.resolve():
        raise RuntimeError("The port belongs to a different database cluster")
    if not psql("SELECT 1 FROM pg_roles WHERE rolname='hotel_app';", config):
        password = config["app_password"]  # generated hex, never interpolated from user input
        psql(f"CREATE ROLE hotel_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '{password}';", config)
    if not psql("SELECT 1 FROM pg_database WHERE datname='hotel_coursework';", config):
        psql("CREATE DATABASE hotel_coursework OWNER hotel_app;", config)
    print("PostgreSQL ready: 127.0.0.1:55432/hotel_coursework (isolated project cluster)", flush=True)


def server():
    start_db()
    config = credentials()
    environment = dict(os.environ, DB_URL=f"jdbc:postgresql://127.0.0.1:{PORT}/hotel_coursework",
                       DB_USER="hotel_app", DB_PASSWORD=config["app_password"], HOST="127.0.0.1", PORT="8080")
    if JBR.is_dir():
        environment["JAVA_HOME"] = str(JBR)
    # Compile first, then run the distribution directly: no Gradle daemon stays in RAM.
    run([ROOT / "server/gradlew", "--no-daemon", "installDist"], cwd=ROOT / "server", env=environment)
    executable = ROOT / "server/build/install/HotelCourseworkServer/bin/HotelCourseworkServer"
    os.chdir(ROOT / "server")
    os.execve(executable, [str(executable)], environment)


def smoke():
    for path in ("/health", "/api/v1/health"):
        with urllib.request.urlopen("http://127.0.0.1:8080" + path, timeout=8) as response:
            body = json.load(response)
            assert response.status == 200 and body["status"] == "ok"
            if path.endswith("v1/health"):
                assert body["database"] == "connected"
            print(path, json.dumps(body, ensure_ascii=False))


def stop_db():
    if not CONFIG.exists() or not (DATA / "PG_VERSION").exists():
        print("Project database has not been initialized")
        return
    run([PG_BIN / "pg_ctl", "-D", DATA, "-m", "fast", "-w", "stop"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["start-db", "stop-db", "server", "smoke"])
    command = parser.parse_args().command
    try:
        {"start-db": start_db, "stop-db": stop_db, "server": server, "smoke": smoke}[command]()
    except (RuntimeError, OSError, subprocess.CalledProcessError, urllib.error.URLError) as error:
        # Do not print child-process input, environment, or credentials.
        print("Development command failed:", str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
