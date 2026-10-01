"""Проверки переносимости запуска. Чужие процессы и реальные базы не затрагиваются."""
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch

import dev


class DevelopmentCommandsTest(unittest.TestCase):
    def test_windows_postgres_path_and_executable(self):
        with patch.object(dev, "WINDOWS", True), patch.dict(os.environ, {"ProgramFiles": "C:/Program Files"}, clear=True):
            self.assertEqual(dev.pg_tool("initdb"), Path("C:/Program Files/PostgreSQL/18/bin/initdb.exe"))

    def test_macos_postgres_path(self):
        with patch.object(dev, "WINDOWS", False), patch.dict(os.environ, {}, clear=True):
            self.assertEqual(dev.pg_tool("psql"), Path("/Applications/Postgres.app/Contents/Versions/18/bin/psql"))

    def test_explicit_paths_take_priority(self):
        with patch.dict(os.environ, {"HOTEL_PG_BIN": "/custom/pg", "JAVA_HOME": "/custom/java"}):
            self.assertEqual(dev.postgres_bin(), Path("/custom/pg"))
            self.assertEqual(dev.java_home(), Path("/custom/java"))

    def test_windows_uses_android_studio_jbr(self):
        with patch.object(dev, "WINDOWS", True), patch.dict(os.environ, {"ProgramFiles": "C:/Program Files"}, clear=True):
            self.assertEqual(dev.java_home(), Path("C:/Program Files/Android/Android Studio/jbr"))

    def test_windows_options_have_no_unix_socket(self):
        with patch.object(dev, "WINDOWS", True):
            options = dev.postgres_options()
            self.assertNotIn(" -k ", options)
            self.assertIn("-h 127.0.0.1 -p 55432", options)

    def test_macos_socket_path_with_spaces_is_quoted(self):
        with patch.object(dev, "WINDOWS", False), patch.object(dev, "LOCAL", Path("/a folder/.local")):
            self.assertIn('-k "/a folder/.local"', dev.postgres_options())

    def test_windows_server_uses_batch_files_without_execve(self):
        with patch.object(dev, "WINDOWS", True), patch.object(dev, "start_db"), \
                patch.object(dev, "credentials", return_value={"app_password": "a" * 48}), \
                patch.object(Path, "is_file", return_value=True), patch.object(dev, "run") as run, \
                patch.object(dev.os, "execve") as execve:
            dev.server()
            commands = [call.args[0] for call in run.call_args_list]
            self.assertEqual(commands[0][-3:], ["gradlew.bat", "--no-daemon", "installDist"])
            self.assertEqual(commands[1][-1], "HotelCourseworkServer.bat")
            self.assertEqual(run.call_args_list[0].kwargs["cwd"], dev.ROOT / "server")
            self.assertEqual(run.call_args_list[1].kwargs["cwd"], dev.ROOT / "server/build/install/HotelCourseworkServer/bin")
            self.assertEqual(run.call_args_list[1].kwargs["env"]["HOST"], "127.0.0.1")
            execve.assert_not_called()

    def test_invalid_credential_is_rejected(self):
        invalid = {"project": "HotelCoursework", "port": 55432, "admin_password": "a" * 48, "app_password": "bad' SQL"}
        with patch.object(Path, "mkdir"), patch.object(Path, "exists", return_value=True), \
                patch.object(Path, "read_text", return_value=json.dumps(invalid)):
            with self.assertRaisesRegex(RuntimeError, "Invalid local credential"):
                dev.credentials()

    def test_foreign_credentials_are_rejected(self):
        with patch.object(Path, "mkdir"), patch.object(Path, "exists", return_value=True), \
                patch.object(Path, "read_text", return_value='{"project":"Other","port":55432}'):
            with self.assertRaisesRegex(RuntimeError, "Unexpected local credentials"):
                dev.credentials()

    def test_occupied_port_does_not_stop_an_existing_process(self):
        with patch.object(Path, "is_file", return_value=True), patch.object(Path, "exists", return_value=False), \
                patch.object(dev, "credentials", return_value={}), \
                patch.object(dev, "port_available", return_value=False), patch.object(dev, "run") as run:
            with self.assertRaisesRegex(RuntimeError, "occupied"):
                dev.start_db()
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
