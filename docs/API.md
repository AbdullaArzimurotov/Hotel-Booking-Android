# REST API — этапы 3–6, версия 0.6.0

Префикс /api/v1. JSON UTF-8, UUID строки. Денежные Long — **минимальные единицы**
(100 = 1,00 RUB/UZS/TRY), без Float/Double; SQL NUMERIC(12,2).
Рейтинг/расстояние могут быть Double: они не денежные величины.
Общий контракт: shared/src/main/kotlin/ru/arzimurotov/hotel/domain/ApiModels.kt и Catalog.kt.

## Действующие маршруты

| Метод и путь | Доступ / назначение |
|---|---|
| GET /health | Публичный liveness, без SQL |
| GET /api/v1/health | Публичный readiness SELECT 1: 200 connected / 503 unavailable, stage=6 |
| POST /auth/register | Имя, email, пароль, телефон; 201 UserProfile, только USER |
| POST /auth/login | Email/пароль; 200 AuthResponse (token, expiresAt epoch seconds, user) |
| GET /profile | Bearer JWT; собственный UserProfile |
| PATCH /profile | Bearer JWT; только fullName и phone |
| GET /locations/countries | Публичный Page<Country> |
| GET /locations/cities?countryId=UUID | Публичный Page<City>, необязательный countryId |
| GET /hotels?cityId=UUID | Публичный Page<Hotel>, необязательный cityId |
| GET /hotels/{id} | Один Hotel или 404 |
| GET /rooms?hotelId=UUID | Page<PhysicalRoom>, availabilityGuaranteed=false |
| GET /places?cityId=UUID | Общий городской справочник Page<Place> |
| GET /hotels/{id}/places | Page<Place>, связь hotel_places, не копии ресторана |
| GET /admin/summary | Только текущий активный ADMIN; счётчики SQL, без CRUD |

Пагинация всех списков: offset=0, limit=20 по умолчанию, limit 1–100.
Ответ: items, total, offset, limit. Отрицательный offset/limit>100/нечисловые параметры — 400.
Гостиница содержит типы номеров/цены, услуги/цены/charge-unit через perNight,
ключи фото EXTERIOR/ROOM/POOL и удобства. Физические номера выдаются отдельно.
legacyId обеспечивает безопасную миграцию старых UI-id версии 0.2 к SQL UUID.

## Тела запросов (только учебные значения)

```json
{"email":"person@example.com","password":"example-only-123","fullName":"Иван","phone":null}
```
Вход:
```json
{"email":"person@example.com","password":"example-only-123"}
```
Профиль:
```json
{"fullName":"Новое имя","phone":"+998901234567"}
```
Подтверждение пароля проверяется Android перед отправкой; пароль не сохраняется.
Неизвестные поля role, id, active в register/PATCH отклоняются строгим JSON-декодером.

## Права, срок и ошибки

Authorization: Bearer <token>. JWT issuer=hotel-coursework, audience=hotel-android,
HS256, срок 1800 секунд. Проверяются подпись, exp/iat/issuer/audience/UUID subject.
Роль/активность читаются из SQL при каждом защищённом запросе, а не доверяются claims/UI.
Нет refresh. Выход удаляет локальный token; ранее выданный JWT истекает через 30 минут
(серверного списка отозванных сессий пока нет).

Форма ошибки:
```json
{"code":"VALIDATION","message":"Проверьте заполнение полей.","fieldErrors":{"email":"Введите корректный email."},"requestId":"UUID"}
```
400 INVALID_BODY/VALIDATION; 401 INVALID_CREDENTIALS/UNAUTHORIZED;
403 FORBIDDEN; 404 NOT_FOUND; 409 EMAIL_EXISTS; 429 RATE_LIMIT; 500 INTERNAL_ERROR.
Внутренние SQL, пароли, токены и трассировки не передаются.
X-Request-Id добавляется для обработанных исключений.
Вход в отсутствующий/неактивный аккаунт и неверный пароль даёт общий ответ.
Регистрация повторного email — явный 409, поэтому она не обещает сокрытия наличия email.
Auth: максимум 10 запросов/минуту с IP; 2 параллельных Argon2 задачи.
Limiter локальный in-memory, X-Forwarded-For не доверяется.

## Поиск свободных номеров

GET /hotels/search и GET /hotels/{id}/availability: countryId, checkIn, checkOut обязательны.
Даты ISO YYYY-MM-DD, interval [checkIn,checkOut), 1–90 ночей, заезд не в прошлом.
cityId необязателен и должен принадлежать стране. adults=2 (1–8), children=0 (0–4), rooms=1 (1–4);
adults>=rooms, суммарная вместимость до 4 гостей/номер, дети учитываются как гости без возрастов.
stars=3,4,5; minRating=0..10; maxPrice=minor за ночь/номер; amenities=POOL,WIFI,...;
roomKind=STANDARD/SUPERIOR/SUITE; maxDistanceKm=0..1000; name — буквальное вхождение;
sort=RECOMMENDED/PRICE/RATING/DISTANCE. limit/offset применяются к гостиницам в SQL.

SearchResponse: items[{hotelId,offers}], total, offset, limit, checkedAt epoch seconds.
AvailableOffer: roomTypeId,rateId,kind,capacity,area,nightlyPrice,stayTotal,currency,availableCount,
allowDemo,allowAtHotel,freeCancelHours. stayTotal=nightlyPrice×nights×rooms; услуги не включены.
Availability: hotelId,offers,checkedAt,services. Актуальные цены услуг обновляют checkout.
services.code (breakfast/transfer/late) — стабильное правило; нельзя определять трансфер по названию.
Каталог /hotels и /rooms остаётся справочным; /rooms availabilityGuaranteed=false.

## Заказы и демооплата (Bearer JWT, только владелец)

| Метод и путь | Контракт |
|---|---|
| POST /bookings | CreateBooking; 201 Booking. Повтор того же ключа возвращает прежний заказ |
| GET /bookings | Page<Booking>, newest first, limit/offset |
| GET /bookings/{id} | Собственный Booking; чужой/отсутствующий 404 |
| POST /bookings/{id}/cancel | Собственный Booking; повтор отмены идемпотентен |
| POST /payments/demo | {bookingId,idempotencyKey}; Booking с PAID/receiptId/transactionId |
| GET /receipts/{id} | application/pdf, private no-store; только владелец |

CreateBooking: hotelId,roomTypeId,rateId,stay (StayRequest),serviceIds (множество UUID),
transfer nullable {airport,flight,pickupAt,phone},paymentMethod,expectedTotal,idempotencyKey UUID.
Сумма клиента лишь условие согласия: server рассчитывает свою; расхождение — 409 PRICE_CHANGED.
Все комнаты одного типа; конкретные rooms выбирает сервер и сохраняет booking_rooms.
Трансфер требует аэропорт/рейс/телефон и местный pickupAt YYYY-MM-DDTHH:mm; встреча не в прошлом,
не раньше чем за сутки до заезда и до выезда. PER_NIGHT_ROOM услуги ×ночей×комнат; ONCE один раз.
Стандартный заезд 14:00/выезд 12:00 местного города. Встреча не означает реальный вызов машины.

ONLINE_DEMO: PENDING_PAYMENT, expiresAt серверное время +900 секунд. PAY_AT_HOTEL:
CONFIRMED/UNPAID, без онлайн-транзакции и paid-PDF. Метод фиксируется в заказе.
Оплата требует действующего собственного резерва; карта/банковский gateway отсутствуют.
Уникальность user+key и отпечаток параметров предотвращают дубликаты. Другой payload с прежним
ключом — 409 IDEMPOTENCY_CONFLICT. Повтор не продлевает резерв. Новый ключ для оплаченного заказа
не создаёт второй платеж и возвращает 409 PAYMENT_NOT_ALLOWED.

Booking: id,number,checkIn,checkOut,adults,children,status,paymentMethod,paymentStatus,total,currency,
expiresAt,cancelUntil,createdAt,cancellationReason,snapshot,receiptId,transactionId,serverNow.
Снимок содержит клиента/почту, гостиницу/адрес, тип/номера, цену/услуги/трансфер/timezone.
Отмена PENDING_PAYMENT разрешена; CONFIRMED — только до cancelUntil тарифной политики.
Оплаченная отмена: CANCELLED/REVERSED (не банковский возврат). PDF сохраняет первоначальный PAID.
После выезда — COMPLETED. Отмена COMPLETED и восстановление отменённого заказа запрещены.
Истёкший резерв при GET логически CANCELLED/EXPIRED без скрытой записи; обслуживание пишет статус
раз в минуту/при запуске. Корректность поиска не зависит от обслуживания.

Дополнительные 409: AVAILABILITY_CHANGED, PAYMENT_METHOD_UNAVAILABLE, PRICE_CHANGED,
IDEMPOTENCY_CONFLICT, PAYMENT_NOT_ALLOWED, RESERVATION_EXPIRED, CANCELLATION_CLOSED.
Ошибка PDF не откатывает платёж: можно повторить GET по сохранённым реквизитам.

## Следующие этапы

Полный /admin CRUD, Google Maps и Room отсутствуют. История SQL не удаляется через API.
Реальные услуги/платежи/фискализация/билеты/такси/аренда авто не реализованы.
