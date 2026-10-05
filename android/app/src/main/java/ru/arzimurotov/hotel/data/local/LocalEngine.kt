package ru.arzimurotov.hotel.data.local

import androidx.room.withTransaction
import java.time.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.arzimurotov.hotel.data.*
import ru.arzimurotov.hotel.domain.*

/**
 * Локальный бизнес-сервис самостоятельной демонстрации. Room сериализует записи в SQL транзакциях:
 * проверка цены, выделение всех комнат, услуги/snapshot — единый commit. Ни сеть, ни старый token
 * сервера не нужны. Отдельный SessionStore сохраняет owner+версию. Граница доверия: личная учебная
 * БД устройства, не общий коммерческий сервис.
 */
class LocalEngine(
    val db: LocalDatabase,
    private val session: TokenStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    val dao = db.dao()
    private val seedMutex = Mutex()
    @Volatile private var ready = false

    fun now() = clock.instant().epochSecond

    fun fail(code: String, message: String): Nothing = throw AuthProblem(ApiError(code, message))

    fun checkValue(ok: Boolean, message: String) {
        if (!ok) fail("VALIDATION", message)
    }

    fun uid() = UUID.randomUUID().toString()

    private fun stable(key: String) =
        UUID.nameUUIDFromBytes(("local09:" + key).toByteArray()).toString()

    /** Seed только новой базы; не заменяет редактированные/архивные записи при обновлении APK. */
    suspend fun ensure() =
        withContext(Dispatchers.IO) {
            if (!ready)
                seedMutex.withLock {
                    if (!ready) {
                        if (dao.entry("__seed09") == null) {
                            val adminHash = LocalSecurity.hash("HotelAdmin!2026")
                            val clientHash = LocalSecurity.hash("HotelClient!2026")
                            db.withTransaction {
                                val c = SeedCatalog.sqlCatalog()
                                c.countries.forEach {
                                    dao.put(
                                        CatalogEntry(
                                            it.id,
                                            "country",
                                            it.name,
                                            json.encodeToString(it),
                                        )
                                    )
                                }
                                Amenity.entries.forEach {
                                    dao.put(
                                        CatalogEntry(
                                            stable("amenity:" + it.name),
                                            "amenity",
                                            it.title,
                                            json.encodeToString(
                                                mapOf("code" to it.name, "name" to it.title)
                                            ),
                                        )
                                    )
                                }
                                c.cities.forEach { v ->
                                    val point = Geo.centers.getValue(v.legacyId)
                                    val city =
                                        v.copy(
                                            latitude = point.first,
                                            longitude = point.second,
                                            timezone =
                                                Geo.timezone(
                                                    c.countries
                                                        .first { it.id == v.countryId }
                                                        .legacyId
                                                ),
                                        )
                                    dao.put(
                                        CatalogEntry(
                                            v.id,
                                            "city",
                                            v.name,
                                            json.encodeToString(city),
                                        )
                                    )
                                }
                                c.hotels.forEachIndexed { i, h ->
                                    val p =
                                        Geo.centers.getValue(
                                            c.cities.first { it.id == h.cityId }.legacyId
                                        )
                                    val lat = p.first + (i % 6 - 2.5) * 0.0015
                                    val lon = p.second + (i % 3 - 1) * 0.002
                                    val hotel =
                                        h.copy(
                                            latitude = lat,
                                            longitude = lon,
                                            distanceKm =
                                                Geo.distanceKm(p.first, p.second, lat, lon),
                                        )
                                    dao.put(
                                        CatalogEntry(
                                            h.id,
                                            "hotel",
                                            h.name,
                                            json.encodeToString(hotel),
                                        )
                                    )
                                    h.rooms.forEachIndexed { k, r ->
                                        val tid = stable(h.id + ":" + r.kind)
                                        dao.put(
                                            LocalType(
                                                tid,
                                                h.id,
                                                r.kind.name,
                                                r.capacity,
                                                r.area,
                                                json.encodeToString(
                                                    h.amenities.map { it.name }.toSet()
                                                ),
                                            )
                                        )
                                        repeat(4) { n ->
                                            dao.put(
                                                LocalRoom(
                                                    stable(tid + ":" + n),
                                                    tid,
                                                    "${k+1}0${n+1}",
                                                )
                                            )
                                        }
                                        dao.put(
                                            LocalRate(
                                                stable("rate:$tid"),
                                                tid,
                                                r.pricePerNight,
                                                c.countries
                                                    .first {
                                                        it.id ==
                                                            c.cities
                                                                .first { it.id == h.cityId }
                                                                .countryId
                                                    }
                                                    .currency,
                                                "2000-01-01",
                                                "2100-01-01",
                                            )
                                        )
                                    }
                                    h.services.forEach { s ->
                                        dao.put(
                                            LocalService(
                                                stable("service:${h.id}:${s.id}"),
                                                h.id,
                                                s.code,
                                                s.name,
                                                s.description,
                                                s.price,
                                                s.perNight,
                                            )
                                        )
                                    }
                                }
                                c.places.forEachIndexed { i, v ->
                                    val p =
                                        Geo.centers.getValue(
                                            c.cities.first { it.id == v.cityId }.legacyId
                                        )
                                    val place =
                                        v.copy(
                                            latitude = p.first + (i % 5 - 2) * 0.0015,
                                            longitude = p.second + (i % 3 - 1) * 0.002,
                                        )
                                    dao.put(
                                        CatalogEntry(
                                            v.id,
                                            "place",
                                            v.name,
                                            json.encodeToString(place),
                                        )
                                    )
                                    c.hotels
                                        .filter { it.cityId == v.cityId }
                                        .forEach { dao.put(LocalHotelPlace(it.id, v.id)) }
                                }
                                dao.put(
                                    LocalUser(
                                        stable("admin"),
                                        "admin@hotel.demo",
                                        "Администратор",
                                        null,
                                        "ADMIN",
                                        adminHash,
                                    )
                                )
                                dao.put(
                                    LocalUser(
                                        stable("client"),
                                        "client@hotel.demo",
                                        "Демо-клиент",
                                        null,
                                        "USER",
                                        clientHash,
                                    )
                                )
                                dao.put(CatalogEntry("__seed09", "meta", "0.9.0", "{}"))
                            }
                        }
                        ready = true
                    }
                }
        }

    /** IO + единая SQLite-транзакция: проверка и запись не разделяются между потоками. */
    suspend fun <T> transaction(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            ensure()
            db.withTransaction { block() }
        }

    fun profile(u: LocalUser) = UserProfile(u.id, u.email, u.name, u.phone, u.role)

    suspend fun actor(): LocalUser {
        val saved =
            session.read()?.split('|') ?: fail("UNAUTHORIZED", "Войдите в локальный аккаунт.")
        val u = saved.getOrNull(1)?.let { dao.user(it) }
        if (
            saved.firstOrNull() != "local09" ||
                u == null ||
                !u.active ||
                saved.getOrNull(2) != u.sessionVersion.toString()
        )
            fail("UNAUTHORIZED", "Сеанс завершён. Войдите снова.")
        return u
    }

    suspend fun admin(): LocalUser =
        actor().also {
            if (it.role != "ADMIN")
                fail("FORBIDDEN", "Только администратор может изменить каталог.")
        }

    private fun email(v: String) =
        v.trim().lowercase(java.util.Locale.ROOT).also {
            checkValue(
                it.length <= 254 && Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(it),
                "Введите корректный email.",
            )
        }

    private fun password(v: String) {
        checkValue(v.length in 10..128, "Пароль: от 10 до 128 символов.")
    }

    suspend fun restore(): UserProfile? = transaction {
        try {
            profile(actor())
        } catch (_: AuthProblem) {
            null
        }
    }

    suspend fun register(r: RegisterRequest): UserProfile = transaction {
        val e = email(r.email)
        password(r.password)
        checkValue(r.fullName.trim().length in 2..120, "Имя: от 2 до 120 символов.")
        checkValue(r.phone == null || r.phone.length <= 30, "Телефон: до 30 символов.")
        if (dao.userEmail(e) != null)
            fail("EMAIL_EXISTS", "Этот email уже зарегистрирован на устройстве.")
        val u =
            LocalUser(uid(), e, r.fullName.trim(), r.phone, "USER", LocalSecurity.hash(r.password))
        dao.put(u)
        profile(u)
    }

    suspend fun login(r: LoginRequest): UserProfile = transaction {
        checkValue(r.password.length <= 128, "Некорректный пароль.")
        var u = dao.userEmail(r.email.trim().lowercase(java.util.Locale.ROOT))
        if (u == null || !u.active || !LocalSecurity.matches(r.password, u.passwordHash))
            fail("INVALID_CREDENTIALS", "Неверный email или пароль.")
        if (u.recoveryHash == null) {
            val code = LocalSecurity.recoveryCode()
            u = u.copy(recoveryHash = LocalSecurity.hash(code))
            dao.put(u)
            session.saveRecovery(u.id + "|" + code)
        }
        session.save("local09|${u.id}|${u.sessionVersion}")
        profile(u)
    }

    fun takeRecovery(id: String): String? =
        session.readRecovery()?.split('|')?.takeIf { it.firstOrNull() == id }?.getOrNull(1)

    fun acknowledgeRecovery() = session.clearRecovery()

    suspend fun update(r: ProfilePatch): UserProfile = transaction {
        checkValue(r.fullName.trim().length in 2..120, "Имя: от 2 до 120 символов.")
        checkValue(r.phone == null || r.phone.length <= 30, "Телефон: до 30 символов.")
        val u = actor().copy(name = r.fullName.trim(), phone = r.phone)
        dao.put(u)
        profile(u)
    }

    suspend fun changePassword(old: String, new: String): String = transaction {
        val u = actor()
        password(new)
        if (!LocalSecurity.matches(old, u.passwordHash))
            fail("INVALID_CREDENTIALS", "Текущий пароль неверен.")
        val code = LocalSecurity.recoveryCode()
        val next =
            u.copy(
                passwordHash = LocalSecurity.hash(new),
                recoveryHash = LocalSecurity.hash(code),
                sessionVersion = u.sessionVersion + 1,
            )
        dao.put(next)
        session.save("local09|${next.id}|${next.sessionVersion}")
        session.saveRecovery(next.id + "|" + code)
        code
    }

    suspend fun resetPassword(address: String, code: String, new: String): String = transaction {
        password(new)
        val u = dao.userEmail(email(address))
        if (
            u == null ||
                !u.active ||
                u.recoveryHash == null ||
                !LocalSecurity.matches(code.trim().uppercase(), u.recoveryHash)
        )
            fail("INVALID_RECOVERY", "Email или код восстановления неверен.")
        val nextCode = LocalSecurity.recoveryCode()
        dao.put(
            u.copy(
                passwordHash = LocalSecurity.hash(new),
                recoveryHash = LocalSecurity.hash(nextCode),
                sessionVersion = u.sessionVersion + 1,
            )
        )
        session.clear()
        session.saveRecovery(u.id + "|" + nextCode)
        nextCode
    }

    suspend fun logout() = withContext(Dispatchers.IO) { session.clear() }

    /**
     * Каталог — проекция активных SQL-записей. Неактивный предок скрывает дочерние объекты.
     * Пустая/черновая гостиница не публикуется, но остаётся доступна администратору.
     */
    suspend fun catalog(): Catalog = transaction { catalogInside() }

    suspend fun catalogInside(): Catalog {
        val e = dao.entries().filter { it.active }
        val countries =
            e.filter { it.kind == "country" }.map { json.decodeFromString<Country>(it.payload) }
        val cities =
            e.filter { it.kind == "city" }
                .map { json.decodeFromString<City>(it.payload) }
                .filter { v -> countries.any { it.id == v.countryId } }
        val types = dao.types().filter { it.active }
        val rates = dao.rates().filter { it.active }
        val services = dao.services().filter { it.active }
        val links = dao.links()
        val places =
            e.filter { it.kind == "place" }
                .map { json.decodeFromString<Place>(it.payload) }
                .filter { v -> cities.any { it.id == v.cityId } }
        val hotels =
            e.filter { it.kind == "hotel" }
                .map { json.decodeFromString<Hotel>(it.payload) }
                .filter { v -> cities.any { it.id == v.cityId } }
                .map { h ->
                    h.copy(
                        amenities =
                            types
                                .filter { it.hotelId == h.id }
                                .flatMap { json.decodeFromString<Set<String>>(it.amenities) }
                                .mapNotNull { code -> Amenity.entries.find { it.name == code } }
                                .toSet(),
                        rooms =
                            types
                                .filter { it.hotelId == h.id }
                                .mapNotNull { t ->
                                    rates
                                        .firstOrNull { it.typeId == t.id }
                                        ?.let {
                                            RoomOffer(
                                                RoomKind.valueOf(t.kind),
                                                t.capacity,
                                                t.area,
                                                it.amount,
                                            )
                                        }
                                },
                        services =
                            services
                                .filter { it.hotelId == h.id }
                                .map {
                                    HotelService(
                                        it.id,
                                        it.name,
                                        it.amount,
                                        it.perNight,
                                        it.description,
                                        it.code,
                                    )
                                },
                        nearbyPlaceIds =
                            links
                                .filter {
                                    it.hotelId == h.id && places.any { p -> p.id == it.placeId }
                                }
                                .map { it.placeId },
                    )
                }
                .filter { it.rooms.isNotEmpty() }
        return Catalog(
            countries,
            cities,
            hotels.map { h ->
                h.copy(
                    amenityLabels =
                        types
                            .filter { it.hotelId == h.id }
                            .flatMap { json.decodeFromString<Set<String>>(it.amenities) }
                            .distinct()
                            .mapNotNull { code ->
                                e.firstOrNull {
                                        it.kind == "amenity" &&
                                            json
                                                .decodeFromString<Map<String, String>>(it.payload)[
                                                    "code"] == code
                                    }
                                    ?.title
                            }
                )
            },
            places,
        )
    }

    suspend fun summary(): AdminSummary = transaction {
        admin()
        val c = catalogInside()
        AdminSummary(
            c.countries.size,
            c.cities.size,
            c.hotels.size,
            dao.rooms().count { it.active },
            c.places.size,
            dao.userCount(),
        )
    }

    private fun decode(v: LocalOrder) = json.decodeFromString<Booking>(v.payload)

    private suspend fun save(row: LocalOrder, b: Booking) {
        dao.put(row.copy(payload = json.encodeToString(b)))
    }

    /** Мёртвый резерв исключается даже если процесс не работал. Время телефона, не сервера. */
    suspend fun housekeeping() {
        val at = now()
        dao.orders().forEach { r ->
            val b = decode(r)
            if (b.status == "PENDING_PAYMENT" && (b.expiresAt ?: 0) <= at)
                save(r, b.copy(status = "CANCELLED", cancellationReason = "EXPIRED"))
            else if (
                b.status == "CONFIRMED" &&
                    LocalDate.parse(b.checkOut)
                        .atTime(12, 0)
                        .atZone(ZoneId.of(b.snapshot.timezone))
                        .toEpochSecond() <= at
            )
                save(r, b.copy(status = "COMPLETED"))
        }
    }

    /** Полуоткрытый интервал; истёкший резерв не занимает комнату даже до housekeeping. */
    suspend fun freeRooms(
        type: String,
        start: String,
        end: String,
        ignoreBlock: String? = null,
    ): List<LocalRoom> {
        val occupied =
            dao.orders()
                .filter { it.start < end && it.end > start }
                .filter { r ->
                    val b = decode(r)
                    b.status in setOf("CONFIRMED", "COMPLETED") ||
                        b.status == "PENDING_PAYMENT" && (b.expiresAt ?: 0) > now()
                }
                .map { it.id }
                .toSet()
        val used = dao.allocations().filter { it.bookingId in occupied }.map { it.roomId }.toSet()
        val blocked =
            dao.blocks()
                .filter { it.active && it.id != ignoreBlock && it.start < end && it.end > start }
                .map { it.roomId }
                .toSet()
        return dao.rooms().filter {
            it.active && it.typeId == type && it.id !in used && it.id !in blocked
        }
    }

    private fun query(r: StayRequest, c: Catalog): SearchQuery {
        val q =
            try {
                SearchQuery(
                    r.countryId,
                    r.cityId,
                    LocalDate.parse(r.checkIn),
                    LocalDate.parse(r.checkOut),
                    r.adults,
                    r.children,
                    r.rooms,
                    SearchFilters(
                        r.stars,
                        r.minRating,
                        r.maxPrice,
                        r.amenities.map { Amenity.valueOf(it) }.toSet(),
                        r.roomKind?.let { RoomKind.valueOf(it) },
                        r.maxDistanceKm,
                    ),
                    SortOrder.valueOf(r.sort),
                    r.name,
                )
            } catch (_: Exception) {
                fail("VALIDATION", "Проверьте даты и фильтры.")
            }
        q.validationError(c, LocalDate.now(clock))?.let { fail("VALIDATION", it) }
        checkValue(
            r.stars.all { it in 3..5 } &&
                r.minRating.isFinite() &&
                r.minRating in 0.0..10.0 &&
                (r.maxPrice == null || r.maxPrice >= 0) &&
                (r.maxDistanceKm == null ||
                    r.maxDistanceKm.isFinite() && r.maxDistanceKm in 0.0..1000.0),
            "Некорректные фильтры.",
        )
        return q
    }

    private suspend fun offers(h: Hotel, q: SearchQuery, c: Catalog): List<AvailableOffer> {
        val currency = c.country(c.city(h.cityId).countryId).currency
        val types =
            dao.types().filter {
                it.active &&
                    it.hotelId == h.id &&
                    it.capacity >= q.guestsPerRoom &&
                    (q.filters.roomKind == null || it.kind == q.filters.roomKind.name) &&
                    json
                        .decodeFromString<Set<String>>(it.amenities)
                        .containsAll(q.filters.amenities.map { a -> a.name })
            }
        val rates = dao.rates()
        return types.mapNotNull { t ->
            val rate =
                rates.firstOrNull {
                    it.active &&
                        it.typeId == t.id &&
                        it.start <= q.checkIn.toString() &&
                        it.end >= q.checkOut.toString() &&
                        it.currency == currency &&
                        (q.filters.maxPrice == null || it.amount <= q.filters.maxPrice)
                } ?: return@mapNotNull null
            val count = freeRooms(t.id, q.checkIn.toString(), q.checkOut.toString()).size
            if (count < q.rooms) return@mapNotNull null
            AvailableOffer(
                t.id,
                rate.id,
                t.kind,
                t.capacity,
                t.area,
                rate.amount,
                Math.multiplyExact(Math.multiplyExact(rate.amount, q.nights), q.rooms.toLong()),
                currency,
                count,
                rate.demo,
                rate.atHotel,
                rate.freeCancelHours,
            )
        }
    }

    suspend fun search(r: StayRequest, offset: Int): SearchResponse = transaction {
        checkValue(offset >= 0, "Некорректная страница.")
        housekeeping()
        val c = catalogInside()
        val q = query(r, c)
        val candidates =
            c.hotels.filter { h ->
                val city = c.city(h.cityId)
                city.countryId == q.countryId &&
                    (q.cityId == null || city.id == q.cityId) &&
                    (q.filters.stars.isEmpty() || h.stars in q.filters.stars) &&
                    h.rating >= q.filters.minRating &&
                    h.name.contains(q.name.trim(), true) &&
                    (q.filters.maxDistanceKm == null || h.distanceKm <= q.filters.maxDistanceKm)
            }
        val hits = candidates.mapNotNull { h ->
            offers(h, q, c).takeIf { it.isNotEmpty() }?.let { SearchHit(h.id, it) }
        }
        val hotels = c.hotels.associateBy { it.id }
        val sorted =
            when (q.sort) {
                SortOrder.PRICE ->
                    hits.sortedWith(
                        compareBy<SearchHit> { it.offers.minOf { o -> o.nightlyPrice } }
                            .thenBy { it.hotelId }
                    )
                SortOrder.DISTANCE ->
                    hits.sortedWith(
                        compareBy<SearchHit> { hotels.getValue(it.hotelId).distanceKm }
                            .thenBy { it.hotelId }
                    )
                else ->
                    hits.sortedWith(
                        compareByDescending<SearchHit> { hotels.getValue(it.hotelId).rating }
                            .thenBy { it.hotelId }
                    )
            }
        SearchResponse(sorted.drop(offset).take(20), sorted.size, offset, 20, now())
    }

    suspend fun availability(id: String, r: StayRequest): Availability = transaction {
        housekeeping()
        val c = catalogInside()
        val q = query(r, c)
        val h = c.hotel(id) ?: fail("NOT_FOUND", "Гостиница не найдена.")
        checkValue(c.city(h.cityId).countryId == r.countryId, "Выберите страну гостиницы.")
        Availability(id, offers(h, q, c), now(), h.services)
    }

    private fun fingerprint(r: CreateBooking) =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(
                json
                    .encodeToString(
                        r.copy(
                            idempotencyKey = "",
                            serviceIds = r.serviceIds.toSortedSet(),
                            stay =
                                r.stay.copy(
                                    stars = r.stay.stars.toSortedSet(),
                                    amenities = r.stay.amenities.toSortedSet(),
                                ),
                        )
                    )
                    .toByteArray()
            )
            .joinToString("") { "%02x".format(it) }

    /** Повтор ключа возвращает прежний заказ; другой payload отклоняется до выделения комнат. */
    suspend fun create(owner: String, r: CreateBooking): Booking = transaction {
        val user = actor()
        checkValue(user.id == owner, "Аккаунт изменился.")
        housekeeping()
        val key = r.idempotencyKey.takeIf { it.isNotBlank() } ?: uid()
        val hash = fingerprint(r)
        dao.orders()
            .firstOrNull { it.ownerId == owner && it.key == key }
            ?.let {
                if (it.fingerprint != hash)
                    fail("IDEMPOTENCY_CONFLICT", "Ключ использован другим заказом.")
                return@transaction decode(it).copy(serverNow = now())
            }
        val c = catalogInside()
        val q = query(r.stay, c)
        val h = c.hotel(r.hotelId) ?: fail("NOT_FOUND", "Гостиница недоступна.")
        checkValue(
            c.city(h.cityId).countryId == q.countryId && (q.cityId == null || q.cityId == h.cityId),
            "Гостиница не соответствует направлению.",
        )
        val offer =
            offers(h, q, c).firstOrNull { it.roomTypeId == r.roomTypeId && it.rateId == r.rateId }
                ?: fail("AVAILABILITY_CHANGED", "Свободных номеров недостаточно. Обновите поиск.")
        checkValue(
            r.paymentMethod in setOf("ONLINE_DEMO", "PAY_AT_HOTEL"),
            "Выберите способ оплаты.",
        )
        checkValue(
            if (r.paymentMethod == "ONLINE_DEMO") offer.allowDemo else offer.allowAtHotel,
            "Способ оплаты недоступен.",
        )
        val zone = ZoneId.of(c.city(h.cityId).timezone)
        val checkIn = q.checkIn.atTime(14, 0).atZone(zone).toEpochSecond()
        val checkout = q.checkOut.atTime(12, 0).atZone(zone).toEpochSecond()
        checkValue(checkIn > now(), "Время заезда уже наступило.")
        checkValue(r.serviceIds.all { id -> h.services.any { it.id == id } }, "Услуга недоступна.")
        val selected = h.services.filter { it.id in r.serviceIds }
        val transfer = selected.any { it.code == "transfer" }
        if (transfer) {
            val t = r.transfer ?: fail("VALIDATION", "Укажите аэропорт, рейс, время и телефон.")
            val pickup = runCatching {
                LocalDateTime.parse(t.pickupAt).atZone(zone).toEpochSecond()
            }
                .getOrElse { fail("VALIDATION", "Время встречи: ГГГГ-ММ-ДДTЧЧ:ММ.") }
            checkValue(
                t.airport.trim().length in 2..100 &&
                    t.flight.trim().length in 2..30 &&
                    Regex("[+0-9 ()-]{7,30}").matches(t.phone) &&
                    pickup >= now() &&
                    pickup < checkout &&
                    pickup >= checkIn - 86400,
                "Проверьте данные трансфера.",
            )
        } else checkValue(r.transfer == null, "Сначала выберите трансфер.")
        val extras = selected.map { s ->
            BookedService(
                s.name,
                if (s.perNight)
                    Math.multiplyExact(Math.multiplyExact(s.price, q.nights), q.rooms.toLong())
                else s.price,
                if (s.code == "transfer") r.transfer else null,
            )
        }
        val total = extras.fold(offer.stayTotal) { a, s -> Math.addExact(a, s.total) }
        checkValue(total in 0..999999999999L, "Сумма превышает лимит.")
        if (total != r.expectedTotal) fail("PRICE_CHANGED", "Цена изменилась. Обновите расчёт.")
        val rooms = freeRooms(r.roomTypeId, r.stay.checkIn, r.stay.checkOut).take(q.rooms)
        if (rooms.size != q.rooms) fail("AVAILABILITY_CHANGED", "Номеров недостаточно.")
        val id = uid()
        val pending = r.paymentMethod == "ONLINE_DEMO"
        val b =
            Booking(
                id,
                "HC-" + id.take(8).uppercase(),
                r.stay.checkIn,
                r.stay.checkOut,
                q.adults,
                q.children,
                if (pending) "PENDING_PAYMENT" else "CONFIRMED",
                r.paymentMethod,
                "UNPAID",
                total,
                offer.currency,
                if (pending) now() + 900 else null,
                checkIn - offer.freeCancelHours * 3600L,
                now(),
                null,
                BookingSnapshot(
                    h.name,
                    h.address,
                    user.name,
                    user.email,
                    offer.kind,
                    rooms.map { it.number },
                    offer.nightlyPrice,
                    offer.stayTotal,
                    extras,
                    zone.id,
                ),
                serverNow = now(),
            )
        dao.put(
            LocalOrder(
                id,
                owner,
                h.id,
                r.roomTypeId,
                b.checkIn,
                b.checkOut,
                key,
                hash,
                json.encodeToString(b),
            )
        )
        rooms.forEach { dao.put(LocalAllocation(id, it.id)) }
        b
    }

    // Ключ детерминирован для одинакового незавершённого расчёта; новый расчёт получает новый.
    private fun sessionKey(owner: String, payload: String) =
        UUID.nameUUIDFromBytes((owner + payload).toByteArray()).toString()

    private suspend fun owned(id: String, administrator: Boolean = false): LocalOrder {
        val u = if (administrator) admin() else actor()
        val row = dao.order(id) ?: fail("NOT_FOUND", "Заказ не найден.")
        if (!administrator && row.ownerId != u.id) fail("NOT_FOUND", "Заказ не найден.")
        return row
    }

    suspend fun orders(offset: Int): Page<Booking> = transaction {
        checkValue(offset >= 0, "Некорректная страница.")
        val user = actor()
        housekeeping()
        val all =
            dao.orders()
                .filter { it.ownerId == user.id }
                .map { decode(it).copy(serverNow = now()) }
                .sortedWith(compareByDescending<Booking> { it.createdAt }.thenBy { it.id })
        Page(all.drop(offset).take(20), all.size, offset, 20)
    }

    suspend fun order(id: String): Booking = transaction {
        housekeeping()
        decode(owned(id)).copy(serverNow = now())
    }

    /** Атомарно платёж + неизменяемый документ + статус; повтор не продлевает резерв. */
    suspend fun pay(owner: String, id: String): Booking = transaction {
        checkValue(actor().id == owner, "Аккаунт изменился.")
        housekeeping()
        val row = owned(id)
        val b = decode(row)
        if (b.paymentStatus == "PAID") return@transaction b.copy(serverNow = now())
        if (
            b.status != "PENDING_PAYMENT" ||
                b.paymentMethod != "ONLINE_DEMO" ||
                (b.expiresAt ?: 0) <= now()
        )
            fail("PAYMENT_NOT_ALLOWED", "Резерв истёк или заказ нельзя оплатить.")
        val receipt = uid()
        val tx = "DEMO-" + uid()
        val paid =
            b.copy(
                status = "CONFIRMED",
                paymentStatus = "PAID",
                receiptId = receipt,
                transactionId = tx,
                serverNow = now(),
            )
        dao.put(
            LocalPayment(
                uid(),
                id,
                owner,
                sessionKey(owner, "payment:$id"),
                tx,
                b.total,
                b.currency,
                now(),
            )
        )
        dao.put(LocalReceipt(receipt, id, owner, json.encodeToString(paid)))
        save(row, paid)
        paid
    }

    /** ADMIN также соблюдает сохранённый срок. REVERSED — локальный demo, не банковский возврат. */
    suspend fun cancel(id: String, administrator: Boolean = false): Booking = transaction {
        housekeeping()
        val row = owned(id, administrator)
        val b = decode(row)
        if (b.status == "CANCELLED") return@transaction b.copy(serverNow = now())
        if (b.status == "COMPLETED" || b.status == "CONFIRMED" && now() >= b.cancelUntil)
            fail("CANCELLATION_CLOSED", "Срок отмены истёк.")
        val next =
            b.copy(
                status = "CANCELLED",
                cancellationReason = if (administrator) "ADMIN" else "USER",
                paymentStatus = if (b.paymentStatus == "PAID") "REVERSED" else b.paymentStatus,
                serverNow = now(),
            )
        save(row, next)
        dao.payment(id)?.let { dao.put(it.copy(status = "REVERSED")) }
        if (administrator) dao.put(LocalAudit(uid(), actor().id, "CANCEL", id, now()))
        next
    }

    suspend fun receipt(id: String): ByteArray = transaction {
        val user = actor()
        val r = dao.receipt(id) ?: fail("NOT_FOUND", "Документ не найден.")
        if (r.ownerId != user.id) fail("NOT_FOUND", "Документ не найден.")
        ConfirmationHtml.render(json.decodeFromString<Booking>(r.payload)).toByteArray()
    }
}

class LocalCatalogRepository(private val engine: LocalEngine) : CatalogRepository {
    override suspend fun load() = engine.catalog()
}

class LocalAuthRepository(private val engine: LocalEngine) : AuthRepository {
    override suspend fun restore() = engine.restore()

    override suspend fun register(request: RegisterRequest) = engine.register(request)

    override suspend fun login(request: LoginRequest) = engine.login(request)

    override suspend fun update(request: ProfilePatch) = engine.update(request)

    override suspend fun summary() = engine.summary()

    override suspend fun logout() = engine.logout()

    override fun takeRecovery(id: String) = engine.takeRecovery(id)

    override fun acknowledgeRecovery() = engine.acknowledgeRecovery()

    override suspend fun changePassword(old: String, new: String) = engine.changePassword(old, new)

    override suspend fun resetPassword(email: String, code: String, new: String) =
        engine.resetPassword(email, code, new)
}

class LocalTravelRepository(private val engine: LocalEngine) : TravelRepository {
    override suspend fun search(q: StayRequest, offset: Int) = engine.search(q, offset)

    override suspend fun availability(id: String, q: StayRequest) = engine.availability(id, q)

    override suspend fun create(userId: String, r: CreateBooking) = engine.create(userId, r)

    override suspend fun bookings(offset: Int) = engine.orders(offset)

    override suspend fun booking(id: String) = engine.order(id)

    override suspend fun cancel(id: String) = engine.cancel(id)

    override suspend fun pay(userId: String, id: String) = engine.pay(userId, id)

    override suspend fun receipt(id: String) = engine.receipt(id)
}
