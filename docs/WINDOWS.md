# Запуск на Asus с Windows и работа на двух компьютерах

Инструкция предполагает Windows 10/11 x64 и процессор Intel/AMD. Если Asus работает
под другой ОС или на ARM, конфигурацию эмулятора нужно выбрать отдельно.
Исходный код одинаков для MacBook и Asus; пути SDK, JDK, пароли и локальные базы различаются.

## 1. Скачать проект

Установите [Git for Windows](https://git-scm.com/downloads/win) и
[Android Studio для Windows](https://developer.android.com/studio/install).
В PowerShell выполните:

```powershell
New-Item -ItemType Directory -Force C:\Projects | Out-Null
Set-Location C:\Projects
git clone https://github.com/AbdullaArzimurotov/Hotel-Booking-Android.git
Set-Location Hotel-Booking-Android
```

Путь `C:\Projects` выбран коротким, без OneDrive и кириллицы, чтобы снизить вероятность
проблем Windows с длинными путями. Не переносите `.idea`, `.gradle`, `.local` и
`local.properties` с Mac. Clone переносит только нужные файлы.

## 2. Открыть Android и нажать Run

1. Android Studio → **Open** → `C:\Projects\Hotel-Booking-Android\android`.
   Открывайте именно `android`, но не копируйте её отдельно: сборка использует
   `..\gradle\libs.versions.toml` из корня репозитория.
2. Дождитесь Gradle Sync. В Settings → Build, Execution, Deployment → Build Tools → Gradle
   выберите Gradle JDK **jbr** из установленной Android Studio. На Mac проверен JBR 25;
   для одинакового окружения используйте JDK 25 и на Asus. Если встроенный JBR другой
   версии, установите JDK 25 через пункт Download JDK в выборе Gradle JDK.
   Используйте Wrapper проекта **9.2.1**, а не локально установленный Gradle.
3. В SDK Manager установите **Android SDK Platform 36**, Build-Tools **36.0.0**,
   Android SDK Platform-Tools и Android Emulator. SDK-путь Android Studio сохранит
   самостоятельно в игнорируемом `local.properties`.
4. Device Manager → Create Virtual Device → телефон, например Pixel 6 →
   образ **API 36 x86_64** для Intel/AMD. ARM64-образ MacBook на обычный Asus не переносится.
   Для ускорения эмулятора включите аппаратную виртуализацию в BIOS/UEFI и настройте
   гипервизор согласно [официальной инструкции](https://developer.android.com/studio/run/emulator-acceleration).
   Запускайте один эмулятор за раз.
5. На панели сверху выберите **app**, созданный эмулятор и зелёную кнопку **Run ▶**.

Откроется русскоязычный экран поиска. 36 демо-гостиниц, 30 мест, фильтры, фотографии,
избранное и предварительный расчёт доступны без сервера. Первое скачивание Gradle и
зависимостей требует интернета. Ни SQL-бронирование, ни платежи пока не реализованы.
Не подтверждайте автоматическое обновление AGP/Kotlin только на одном компьютере:
версии проекта уже согласованы в `gradle/libs.versions.toml`.

## 3. PostgreSQL и backend — для диагностики

Для интерфейса этапа 2 этот раздел необязателен. Для «Профиль → Диагностика системы»
нужно запустить backend на том же компьютере, где работает эмулятор.

Установите Python 3.10+ с [python.org](https://www.python.org/downloads/windows/)
и PostgreSQL **18** из [официального раздела Windows](https://www.postgresql.org/download/windows/).
Скрипт использует бинарные файлы PostgreSQL, но не изменяет базу Windows-службы,
созданную установщиком, и не требует её пароля. Если служба использует 5432, это не
мешает отдельному кластеру проекта на **55432**. Не устанавливайте службу на 55432.

В PowerShell из корня проекта:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:HOTEL_PG_BIN = 'C:\Program Files\PostgreSQL\18\bin'
py -3 scripts/dev.py server
```

Укажите свои пути, если установили программы иначе. Если Gradle JDK 25 загружен
отдельно, `JAVA_HOME` должен указывать на его корень с `bin\java.exe`.
`JAVA_HOME` нужен терминалу; IDE выбирает Gradle JDK отдельно в настройках.
Скрипт создаёт `.local\postgres`, пользователя `hotel_app`, базу `hotel_coursework`,
приватные случайные пароли, применяет миграции и запускает Ktor на **127.0.0.1:8080**.
Команда оставляет сервер в текущем терминале; не закрывайте его во время диагностики.
При повторном запуске используются прежние данные. Скрипт отказывается занимать чужой порт.

В другом PowerShell, также из корня проекта:

```powershell
py -3 scripts/dev.py smoke
```

Ожидаются `status: ok`, `database: connected`. Android Emulator обращается к локальному
компьютеру через **http://10.0.2.2:8080/** на обеих ОС; IP Mac в исходниках не зашит.
Для остановки Ktor — Ctrl+C в первом терминале. Затем:

```powershell
py -3 scripts/dev.py stop-db
```

Остановка не удаляет данные. На Windows защита `.local` определяется ACL учётной записи,
а не Unix-режимом 600. Не храните каталог в общей сетевой папке и не публикуйте пароли.

## 4. Сборка и тесты в PowerShell

Из корня проекта, с настроенным `JAVA_HOME`:

```powershell
py -3 scripts/dev.py smoke
py -3 -m unittest discover -s scripts -p "test_*.py" -v
Set-Location android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:dokkaGeneratePublicationHtml
# UI запускается из корня только на конкретном AVD, личный телефон не используется.
Set-Location ..
py -3 scripts/ui_tests.py --serial emulator-5554
py -3 scripts/dev.py test-sql
Set-Location android
Set-Location ..\server
.\gradlew.bat test installDist dokkaGeneratePublicationHtml
```

`smoke` требует запущенного сервера; остальные модульные тесты настоящую базу не требуют.
Не используйте connectedDebugAndroidTest при подключённом телефоне: Gradle может выбрать
все устройства и удалить приложение. Наш ui_tests.py устанавливает APK с -r только на AVD.
APK находится в `android\app\build\outputs\apk\debug\app-debug.apk`.
В GitHub Actions настроены сборка APK, Android unit/lint, backend-тесты и проверки скрипта
на Windows. Это не проверка вашего Asus, не UI-тест на AVD и не smoke реальной SQL-базы.
Фактический статус каждого запуска смотрите во вкладке **Actions** репозитория.

## 5. Продолжать работу попеременно на MacBook и Asus

Перед началом работы на любом компьютере из корня репозитория:

```sh
git status
git pull --ff-only origin main
```

Перед переходом на другой компьютер сохраните изменения и отправьте их:

```sh
git diff
git add android server gradle scripts docs README.md .gitignore .gitattributes .env.example .github
git diff --cached
git commit -m "Добавлен следующий этап приложения"
git push origin main
```

Если меняли не все каталоги, добавляйте только нужные. Для push войдите в GitHub через
Git Credential Manager/Android Studio; не добавляйте токены в remote URL и исходники.
При первом собственном коммите задайте `git config user.name` и `git config user.email`
на этом компьютере своими значениями. Не используйте `push --force`.
Если `pull --ff-only` не прошёл, не затирайте локальные изменения: сначала проверьте
`git status`, сохраните свой коммит и осознанно разрешите расхождение истории.

**Git синхронизирует код, а не базы и настройки IDE.** PostgreSQL, учётные данные и
заказы на Mac и Asus будут локальными и независимыми. Каталог создаётся SQL seed;
редактирование, аккаунты и заказы локальны. Миграции V1–V5 применяются автоматически.
btree_gist входит в PostgreSQL; при внешней БД для расширения нужны соответствующие права.
Приложение не обходит ошибку миграции удалением ограничений или пересозданием данных.
Единые пользовательские заказы потребуют отдельного общего backend.
Не переносите бинарную `.local\postgres` между macOS и Windows. Для переноса реальных
данных позднее используйте отдельно согласованный `pg_dump`/restore, а не Git.

## Источники настройки

- [Установка Android Studio](https://developer.android.com/studio/install).
- [Аппаратное ускорение эмулятора](https://developer.android.com/studio/run/emulator-acceleration).
- [Установка PostgreSQL на Windows](https://www.postgresql.org/download/windows/).
- [Параметры initdb](https://www.postgresql.org/docs/18/app-initdb.html),
  [запуск через pg_ctl](https://www.postgresql.org/docs/18/app-pg-ctl.html).
# Asus / Windows: актуальная версия 0.9.0

Для самостоятельного APK PostgreSQL не нужен. Клонируйте весь репозиторий,
откройте `android` в Android Studio, используйте встроенный JBR 25 и API 36,
выберите `app` → Run. Подробности: [OFFLINE_09.md](OFFLINE_09.md).
PowerShell: `cd android`, затем `.\gradlew.bat :app:assembleDebug`.
Первый Sync требует интернет; работа установленного APK — нет.
Не копируйте Mac `local.properties` на Asus. Базы и аккаунты устройств независимы.
Release-ключ приватный и в Git отсутствует; без него используйте debug сборку.
Не удаляйте существующие данные для обновления.

## Архив: установка необязательного backend 0.6.0 на Windows
