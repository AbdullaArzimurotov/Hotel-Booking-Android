#!/usr/bin/env python3
"""Smoke подписанного APK на явно указанном AVD: offline первый запуск и два запуска
процесса. Не выбирает физический телефон, не удаляет APK и не очищает данные.
Подробные бизнес/Compose проверки выполняются ui_tests.py на той же кодовой базе.
"""
import argparse
import os
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET
import dev

PACKAGE = "ru.arzimurotov.hotel.offline"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-") or not args.serial[9:].isdigit():
        raise SystemExit("Physical devices are never used by this script")
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / ("AppData/Local/Android/Sdk" if dev.WINDOWS else "Library/Android/sdk"))))
    adb = sdk / "platform-tools" / ("adb.exe" if dev.WINDOWS else "adb")
    prefix = [str(adb), "-s", args.serial]
    def command(*parts):
        return subprocess.check_output(prefix + list(parts), text=True, encoding="utf-8", timeout=40)
    command("shell", "svc", "wifi", "disable")
    command("shell", "svc", "data", "disable")
    apk = dev.ROOT / "output/apk/Гостиница-0.9.0-offline-signed.apk"
    print(command("install", "-r", str(apk)).strip())
    for attempt in range(2):
        command("shell", "am", "force-stop", PACKAGE)
        command("shell", "am", "start", "-n", PACKAGE + "/ru.arzimurotov.hotel.MainActivity")
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            try:
                command("shell", "uiautomator", "dump", "/sdcard/hotel-release-smoke.xml")
                xml = command("shell", "cat", "/sdcard/hotel-release-smoke.xml")
                nodes = ET.fromstring(xml).iter("node")
                if any(n.get("text") == "Найти гостиницы" for n in nodes):
                    print(f"Release launch {attempt+1}: local SQL catalogue visible, network disabled")
                    break
            except (ET.ParseError, subprocess.CalledProcessError):
                pass
        else:
            raise SystemExit("Release screen did not load; smoke failed")
    result = dev.ROOT / "docs/test-results/09-release-smoke.txt"
    result.parent.mkdir(parents=True, exist_ok=True)
    result.write_text("Signed 0.9.0; " + args.serial + "; two offline process launches passed.\n", encoding="utf-8")
    print("PASSED: signed APK, no backend, no Wi-Fi/data; no personal device changed")


if __name__ == "__main__":
    main()
