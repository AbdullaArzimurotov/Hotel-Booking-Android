# «Гостиница» — HotelCoursework

Курсовая работа Арзимуротова А.М., ПИН-123. Android/Kotlin, Ktor, PostgreSQL.

## Текущий результат: этап 1

Сейчас реализованы Android-проект с Compose/Hilt/MVVM, реальная проверка соединения,
Ktor REST API, изолированная PostgreSQL-база, первая SQL-миграция и тесты инфраструктуры.
Каталог, авторизация, бронирования, карта и административные экраны **еще не реализованы**.
Экран приложения честно обозначает этот этап. Платежи и внешние услуги будут учебными.

Проверено: APK запущен на API 36 ARM64, SQL-миграция и восстановление соединения работают;
6 Android unit + 4 backend + 3 Compose UI теста пройдены. Lint: 0 ошибок, 1 рекомендация
о новой версии Gradle. Dokka сгенерирована для обеих частей.
Подробности: [протокол этапа 1](docs/STAGE_1_VERIFICATION.md),
[соответствие заданию](docs/REQUIREMENTS.md).

## Быстрый запуск на этом Mac

1. Откройте **папку `android`** в Android Studio: File → Open. Дождитесь Gradle Sync.
   При запросе Gradle JDK выберите встроенный JDK Android Studio (jbr-25).
   Не открывайте `HotelCoursework` как самостоятельный Android Gradle-проект.
2. В терминале перейдите в папку `HotelCoursework` и выполните:

   ```sh
   python3 scripts/dev.py server
   ```

   Команда сначала запустит отдельный PostgreSQL и затем Ktor. Терминал оставьте открытым.
   Внешнее окно Postgres.app запускать не требуется; используются его установленные бинарные файлы.
3. В Android Studio выберите созданный `HotelCoursework_API36` (Pixel 6, ARM64, API 36)
   и нажмите Run для конфигурации `app`.
   На экране должны появиться «Система готова» и два значения «Подключено».
4. Для независимой проверки в другом терминале:

   ```sh
   python3 scripts/dev.py smoke
   ```

Для остановки сервера нажмите Ctrl+C в его терминале. Остановка базы:

```sh
python3 scripts/dev.py stop-db
```

База не удаляется. Следующий запуск использует те же данные. Пока сервер остановлен,
Android покажет понятное сообщение и кнопку повторной проверки.

## Безопасная локальная база

- PostgreSQL 18 установлен в `/Applications/Postgres.app`; существующие базы не затрагиваются.
- Для проекта создается отдельный кластер `.local/postgres`, база `hotel_coursework`,
  несуперпользователь `hotel_app`, порт **55432**, адрес **127.0.0.1**.
- Пароли генерируются автоматически и хранятся в `.local/credentials.json` с правами 600.
  Эта папка исключена из Git. Не отправляйте ее на GitHub.
- TCP и локальный Unix-сокет требуют SCRAM-аутентификацию. Сервер доступен только
  на этом Mac; это не конфигурация публичного production-сервера.
- Скрипт отказывается запускаться поверх чужого процесса на порту или чужого кластера.
- Для другого расположения Postgres.app задайте `HOTEL_PG_BIN`.

## Сборка и тесты

У обоих проектов собственный стандартный Gradle Wrapper 9.2.1. В терминале без настроенной
Java предварительно задайте JDK, который уже поставляется с Android Studio:

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
```

Android, из каталога `android`:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:dokkaGeneratePublicationHtml
```

Команда `connectedDebugAndroidTest` требует один работающий эмулятор.
APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

Backend, из каталога `server`:

```sh
./gradlew test installDist dokkaGeneratePublicationHtml
```

Backend unit/API-тесты не требуют настоящей базы. Команда `scripts/dev.py smoke`
проверяет запущенный сервер с настоящей PostgreSQL через Exposed.
HTML-отчеты тестов и документация Dokka находятся в `build` соответствующего проекта.

## Настройки Android

- compileSdk/targetSdk 36, minSdk 26.
- Эмулятор обращается к Mac через `http://10.0.2.2:8080/`.
- HTTP разрешен только для этого адреса и только в debug-сборке.
- Release использует адрес-заглушку `https://hotel.invalid/`: публичного сервера пока нет.
  Для реального развертывания адрес должен быть заменен на собственный HTTPS API.
- На физическом телефоне `10.0.2.2` не является адресом Mac; этот этап проверяется на AVD.
- Для другого Mac измените игнорируемый `android/local.properties` или дайте Android Studio
  создать его автоматически. SDK-путь не хранится в Git.

## Структура

- `android` — UI/Domain/Data, Hilt и Ktor Client.
- `server` — REST API, HikariCP, Exposed JDBC и Flyway.
- `gradle/libs.versions.toml` — общие фиксированные версии зависимостей.
- `scripts/dev.py` — безопасный запуск локальной базы и сервера, smoke-проверка.
- `docs` — API, состояние этапов, соответствие заданию и протокол проверки.

Локальный Git-репозиторий готовится к GitHub. Публикация выполняется только в выбранный
пользователем репозиторий; адрес удаленного репозитория и учетные данные пока не заданы.
Не включайте в коммит `.local`, пароли, SDK-пути, signing keys и Maps API-ключ.
