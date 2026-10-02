# Структура и выполненные работы — 0.6.0

## Выполненные этапы

1. Платформа: независимые Android/Ktor Gradle-проекты, Wrapper, Hilt, health, PostgreSQL,
   Flyway V1, HikariCP/Exposed, безопасный переносимый локальный запуск.
2. UI: русская навигация, поиск/формы/фильтры, галерея, услуги, избранное, места,
   адаптивная панель. Фабрика каталога была локальной.
3. SQL/API: V2 без изменения V1, 36 гостиниц/432 номера/30 мест, нормализованные связи,
   устойчивый seed, регистрация/вход/профиль/Keystore, текущие серверные роли,
   только API без fallback, USB/debug-настройка и администраторская сводка.

4. SQL-поиск: диапазон тарифов, наличие физической комнаты на весь интервал,
   room_blocks, фильтры/сортировка/пагинация на сервере.
5. Заказы: несколько комнат, блокировки и повторная проверка, услуги/трансфер,
   серверный расчёт, снимки истории и идемпотентность.
6. Демооплата: 15 минут, история/отмена, фоновые статусы, PDFBox/SAF/печать.
GitHub/Windows workflow существуют. Карта/Room/полный ADMIN CRUD ещё не реализованы.

## Каталоги

```text
HotelCoursework/
  android/      Android Studio открывает этот Gradle-проект
    app/src/main/java/ru/arzimurotov/hotel/
      ui/       Compose, четыре ViewModel, Navigation, однонаправленный StateFlow
      data/     REST репозитории, ApiConnection, SessionStore
      domain/   контракт readiness
      di/       Hilt composition root
    app/src/test/         JVM unit
    app/src/androidTest/  UI/Activity/Keystore проверки на AVD
  server/       отдельный JVM/Ktor Gradle-проект
    src/main/kotlin/ru/arzimurotov/hotel/server/
      Application.kt          composition root, health, безопасные ошибки
      ApiRoutes.kt            HTTP/DTO/пагинация/маршруты
      AuthService.kt          правила учётной записи, JWT, Argon2, UserRepository
      SearchService.kt        SearchService + SearchRepository, SQL-наличие/фильтры
      BookingService.kt       BookingService + BookingRepository, блокировки/история/оплата
      ReceiptService.kt       PDFBox, кириллица/переносы, выпуск после транзакции
      CatalogRepository.kt    prepared SQL, seed, сборка каталога без N+1
      DatabaseService.kt      IO, Hikari, Flyway, Exposed транзакции
      ServerConfig.kt         конфигурация, редактированный toString
    src/main/resources/db/migration/ V1–V5 (ранее применённые файлы не изменяются)
    src/test/                 health, auth правила, SQL/API интеграция
  shared/src/main/kotlin/ru/arzimurotov/hotel/domain/
    Catalog.kt    модели/контракты, локальная валидация и расчёт
    ApiModels.kt  общий JSON-контракт
    BookingModels.kt поиск, актуальные предложения, заказы, услуги и демооплата
    SeedCatalog.kt единая детерминированная фабрика исходных данных
  gradle/libs.versions.toml   общие закреплённые версии
  scripts/dev.py             сервер/БД/USB/create-admin/test-sql
  scripts/ui_tests.py         безопасный runner только явно указанного AVD
  docs/                      API, ER, этапы, требования и запуск
```

shared — общий исходный source set двух Gradle-проектов, не отдельный Android module.
AGP 9: AndroidSourceSet.kotlin; Ktor: Kotlin JVM sourceSet.
Модели не импортируют Android/Compose/HTTP/SQL; JSON-аннотации — осознанная общая
зависимость протокола. Это компактное разделение UI–Domain–Data, а не обещание
полного многомодульного Clean Architecture с отдельным use-case на каждый экран.

## Android: события и состояния

Compose → callback → ViewModel → Repository → Ktor Client → API.
Обратно: DTO → неизменяемый StateFlow → collectAsStateWithLifecycle → Compose.

- BrowseViewModel: только один load job; SavedStateHandle хранит форму/избранное,
  SQL UUID нормализуются по legacyId, неизвестные объекты удаляются безопасно.
  При сетевом сбое уже загруженный RAM-снимок можно продолжать смотреть; новые данные
  не выдумываются. Для другого backend снимок сбрасывается.
- AuthViewModel: Guest либо профиль из GET /profile; busy блокирует дубль отправки,
  fieldErrors относятся к полям; 401 очищает сеанс. Смена источника отменяет старую операцию.
- FoundationViewModel: независимая реальная проверка liveness/readiness.
- AuthForm: пароль/подтверждение только remember (не Bundle/Saveable), стираются после submit.
  Ввод email/имени сохраняется при пересоздании. Истечение JWT требует нового входа.
- SessionStore: Keystore AES-GCM, atomic ciphertext noBackupFilesDir; пароль не хранится.
- ApiConnection: только emulator и USB в debug; настройка достижима при ошибке каталога.
- NetworkModule: один HTTP-клиент, таймауты, production RemoteCatalogRepository.
  DemoCatalogRepository оставлен исключительно тестам; скрытого offline seed нет.

Маршруты: search, places, bookings, profile, results, favorites, system, auth, admin,
hotel/{id}, gallery/{id}/{page}, quote/{id}/{kind}, place/{id}.
Большие объекты не передаются в маршруте. NavigationRail от 720 dp.
AuthScreens.kt отвечает за вход/регистрацию, настоящий профиль и read-only ADMIN.
TravelViewModel обслуживает независимые jobs результатов/наличия и одну операцию заказа.
RemoteTravelRepository передаёт DTO; SessionStore хранит шифрованный ключ незавершённого POST.
CheckoutScreen оформляет заказ по актуальному серверному предложению и ценам услуг.
OrdersScreen показывает собственные SQL-заказы и серверный таймер; ReceiptActions — SAF/печать.
Локальный QuoteScreen оставлен только для детерминированных старых UI-тестов. Production
MainActivity всегда передаёт TravelState и открывает реальный CheckoutScreen.

## Backend: границы ответственности

Routes — DTO/HTTP, AuthService — нормализация/валидация/права/профиль,
UserRepository/CatalogRepository — SQL. Зависимости передаются конструкторами, без Android Hilt.
Catalogue GET не изменяет базу. SearchService выполняет SQL-поиск/наличие;
BookingService управляет заказами и демоплатежами; ReceiptService формирует PDF
после завершения транзакции. Фоновая задача обслуживает резервы каждую минуту.

JDBC запускается на Dispatchers.IO в Exposed transaction; maxAttempts=1 исключает скрытое
повторение записи. Hikari максимум 4 соединения. PreparedStatement закрывается use.
Каталог собирается пакетными запросами, а не отдельным запросом на каждый hotel.
Flyway миграции применяются до HTTP; seed в одной транзакции использует ON CONFLICT DO NOTHING.
Стабильные namespace UUID позволяют повторный запуск и перенос legacy избранного.
Источником ответов служат реальные строки SQL, а не runtime фабрика.

Argon2id pure JVM Bouncy Castle 1.86 переносим между ARM64 macOS и x86_64 Windows.
Две задачи одновременно, salt 16 байт, hash 32 байта; параметры PHC проверяются.
JWT Auth0 (зависимость Ktor auth-jwt) проверяется AuthService до обращения к protected route;
Ktor Authentication plugin в этом компактном варианте отдельно не устанавливается.
После JWT дополнительно читается текущий active/role SQL, поэтому снятие права действует сразу.
Публичная JSON-регистрация не содержит роль; неизвестные поля запрещены.

## Деньги и ограничения

SQL NUMERIC(12,2); JVM BigDecimal ↔ Long minor units через longValueExact.
UI отображает major units через BigDecimal(value,2). Бюджет формы вводится в целых major
units и переводится ×100, расчёт сохраняет дробную цену и не использует Double.
Double остаётся только для рейтинга/расстояния.

Запрос комнат выдаёт физические комнаты и цены, но availabilityGuaranteed=false.
V3 содержит room_blocks и ограничения тарифов; V4 — bookings, booking_rooms,
booking_services; V5 — payments и receipts. Реальные SQL-заказы есть, услуги и
денежные операции учебные: внешнему отелю/банку запрос не отправляется.
Places — общие городские объекты; hotel_places не размножает ресторан на каждую гостиницу.
Полный ADMIN CRUD, карта и offline кеш требуют следующих этапов и миграций.

## Источники решений

[Built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin),
[OWASP Argon2id](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html),
[Bouncy Castle 1.86](https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/),
[Android Keystore](https://developer.android.com/privacy-and-security/keystore),
[AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test).
