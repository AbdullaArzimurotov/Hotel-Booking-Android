#!/usr/bin/env python3
"""UI-тесты ТОЛЬКО на указанном AVD. Gradle connectedDebugAndroidTest не вызывается:
он может выбрать все подключённые устройства и удалить приложение после испытаний."""
import argparse
import os
from pathlib import Path
import subprocess
import dev


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--class", dest="test_class", help="Один класс ru.arzimurotov.hotel.ui.* (необязательно)")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-") or not args.serial[9:].isdigit():
        raise SystemExit("Refusing physical device: use an explicit emulator-NNNN serial")
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / ("AppData/Local/Android/Sdk" if dev.WINDOWS else "Library/Android/sdk"))))
    adb = sdk / "platform-tools" / ("adb.exe" if dev.WINDOWS else "adb")
    devices = subprocess.check_output([str(adb), "devices"], text=True)
    if args.serial + "\tdevice" not in devices:
        raise SystemExit("Selected emulator is not online")
    environment = dict(os.environ, JAVA_HOME=str(dev.java_home()))
    command = ([os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", "gradlew.bat"] if dev.WINDOWS else [dev.ROOT / "android/gradlew"])
    dev.run(command + ["--no-daemon", ":app:assembleDebug", ":app:assembleDebugAndroidTest"],
            cwd=dev.ROOT / "android", env=environment)
    for apk in ("android/app/build/outputs/apk/debug/app-debug.apk",
                "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"):
        dev.run([adb, "-s", args.serial, "install", "-r", "-t", dev.ROOT / apk])
    if args.test_class and not args.test_class.startswith("ru.arzimurotov.hotel.ui."):
        raise SystemExit("Only project UI test classes are allowed")
    extra = ["-e", "class", args.test_class] if args.test_class else []
    result = subprocess.run([str(adb), "-s", args.serial, "shell", "am", "instrument", "-w"] + extra + [
                             "ru.arzimurotov.hotel.test/androidx.test.runner.AndroidJUnitRunner"],
                            capture_output=True, text=True, encoding="utf-8", timeout=900)
    dev.LOCAL.mkdir(exist_ok=True)
    (dev.LOCAL / ("ui-tests-" + args.serial + ".log")).write_text(result.stdout, encoding="utf-8")
    print(result.stdout)
    if result.returncode or "FAILURES!!!" in result.stdout or "OK (" not in result.stdout:
        raise SystemExit("UI tests failed; no physical device was touched")


if __name__ == "__main__":
    main()
