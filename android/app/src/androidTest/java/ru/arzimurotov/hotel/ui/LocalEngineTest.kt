package ru.arzimurotov.hotel.ui

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.time.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import ru.arzimurotov.hotel.data.*
import ru.arzimurotov.hotel.data.local.*
import ru.arzimurotov.hotel.domain.*

/**
 * Реальный Android SQLite, изолированная in-memory база для каждого теста. Производственная база и
 * аккаунты телефона не очищаются. Fake clock проверяет 15 минут без ожидания; параллельные корутины
 * используют настоящие Room transactions.
 */
class LocalEngineTest {
    private lateinit var db: LocalDatabase
    private lateinit var e: LocalEngine
    private val clock = MutableClock()

    private class MemorySession : TokenStore {
        var token: String? = null
        var recovery: String? = null

        override fun read() = token

        override fun save(token: String) {
            this.token = token
        }

        override fun clear() {
            token = null
        }

        override fun readRecovery() = recovery

        override fun saveRecovery(value: String) {
            recovery = value
        }

        override fun clearRecovery() {
            recovery = null
        }
    }

    private class MutableClock : Clock() {
        var value = Instant.parse("2026-10-05T08:00:00Z")

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant() = value
    }

    private lateinit var session: MemorySession

    @Before
    fun setup() {
        db =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    LocalDatabase::class.java,
                )
                .build()
        session = MemorySession()
        e = LocalEngine(db, session, clock)
    }

    @After
    fun close() {
        db.close()
    }

    private suspend fun user() = e.login(LoginRequest("client@hotel.demo", "HotelClient!2026"))

    private suspend fun admin() = e.login(LoginRequest("admin@hotel.demo", "HotelAdmin!2026"))

    private suspend fun request(
        rooms: Int = 1,
        method: String = "ONLINE_DEMO",
        key: String = e.uid(),
    ): CreateBooking {
        val c = e.catalog()
        val h = c.hotels.first { it.legacyId == "tashkent-1" }
        val stay =
            StayRequest(
                c.country(c.city(h.cityId).countryId).id,
                h.cityId,
                "2026-10-15",
                "2026-10-17",
                adults = rooms * 2,
                rooms = rooms,
            )
        val o = e.availability(h.id, stay).offers.first { it.kind == "STANDARD" }
        return CreateBooking(
            h.id,
            o.roomTypeId,
            o.rateId,
            stay,
            paymentMethod = method,
            expectedTotal = o.stayTotal,
            idempotencyKey = key,
        )
    }

    private suspend fun error(code: String, body: suspend () -> Unit) {
        try {
            body()
            fail("Expected $code")
        } catch (x: AuthProblem) {
            assertEquals(code, x.error.code)
        }
    }

    @Test
    fun seedIsCompleteAndNeverOverwrites() = runBlocking {
        e.ensure()
        assertEquals(36, e.catalog().hotels.size)
        assertEquals(432, e.dao.rooms().size)
        assertEquals(30, e.catalog().places.size)
        admin()
        val a = LocalAdmin(e)
        val h = a.list("hotel").first()
        a.save("hotel", h.id, h.fields + ("name" to "Сохранённое имя"), true, h.version)
        val restarted = LocalEngine(db, session, clock)
        restarted.ensure()
        assertEquals("Сохранённое имя", restarted.catalog().hotel(h.id)!!.name)
        assertEquals(432, e.dao.rooms().size)
    }

    @Test
    fun passwordsRecoveryAndRoleChecks() = runBlocking {
        val u = user()
        val code = e.takeRecovery(u.id)!!
        assertEquals(code, LocalEngine(db, session, clock).takeRecovery(u.id))
        error("FORBIDDEN") { LocalAdmin(e).list("hotel") }
        error("INVALID_CREDENTIALS") { e.login(LoginRequest(u.email, "wrong")) }
        val next = e.changePassword("HotelClient!2026", "NewPassword!2026")
        assertNotEquals(code, next)
        error("INVALID_RECOVERY") { e.resetPassword(u.email, code, "NextPassword!2026") }
        e.resetPassword(u.email, next, "NextPassword!2026")
        assertNull(e.restore())
        assertEquals(u.id, e.login(LoginRequest(u.email, "NextPassword!2026")).id)
        e.acknowledgeRecovery()
        assertNull(e.takeRecovery(u.id))
    }

    @Test
    fun registrationNormalizesAndKeepsUserRole() = runBlocking {
        val u =
            e.register(RegisterRequest("  Alice@EXAMPLE.COM ", "MyPassword!2026", "Алиса", null))
        assertEquals("alice@example.com", u.email)
        assertEquals("USER", u.role)
        error("EMAIL_EXISTS") {
            e.register(RegisterRequest(u.email, "MyPassword!2026", "Алиса", null))
        }
        assertNull(e.restore())
    }

    @Test
    fun twoRoomsAreAtomicAndIdempotent() = runBlocking {
        val u = user()
        val r = request(2)
        val first = e.create(u.id, r)
        assertEquals(2, first.snapshot.roomNumbers.size)
        assertEquals(first.id, e.create(u.id, r).id)
        assertEquals(1, e.dao.orders().size)
        assertEquals(2, e.dao.allocations().size)
        error("IDEMPOTENCY_CONFLICT") { e.create(u.id, r.copy(paymentMethod = "PAY_AT_HOTEL")) }
        error("PRICE_CHANGED") { e.create(u.id, request().copy(expectedTotal = 1)) }
        assertEquals(1, e.dao.orders().size)
    }

    @Test
    fun concurrentBookingsNeverDoubleAllocate() = runBlocking {
        val u = user()
        val r = request(2)
        val results =
            List(3) {
                    async(Dispatchers.Default) {
                        runCatching { e.create(u.id, r.copy(idempotencyKey = e.uid())) }
                    }
                }
                .awaitAll()
        assertEquals(2, results.count { it.isSuccess })
        assertEquals(4, e.dao.allocations().size)
        assertEquals(4, e.dao.allocations().map { it.roomId }.distinct().size)
        assertEquals(
            "AVAILABILITY_CHANGED",
            (results.first { it.isFailure }.exceptionOrNull() as AuthProblem).error.code,
        )
    }

    @Test
    fun payCancelAndImmutableReceipt() = runBlocking {
        val u = user()
        val b = e.create(u.id, request())
        val paid = e.pay(u.id, b.id)
        assertEquals("PAID", paid.paymentStatus)
        assertEquals(paid.transactionId, e.pay(u.id, b.id).transactionId)
        val html = e.receipt(paid.receiptId!!).toString(Charsets.UTF_8)
        assertTrue(html.contains("Не является фискальным чеком"))
        assertTrue(html.contains(b.snapshot.hotelName))
        assertEquals("REVERSED", e.cancel(b.id).paymentStatus)
        assertEquals(html, e.receipt(paid.receiptId).toString(Charsets.UTF_8))
        assertEquals("CANCELLED", e.order(b.id).status)
    }

    @Test
    fun expiredReservationsReleaseRoomsImmediately() = runBlocking {
        val u = user()
        val r = request(4)
        val b = e.create(u.id, r)
        clock.value = clock.value.plusSeconds(900)
        assertEquals("CANCELLED", e.order(b.id).status)
        assertEquals("EXPIRED", e.order(b.id).cancellationReason)
        error("PAYMENT_NOT_ALLOWED") { e.pay(u.id, b.id) }
        assertEquals(
            4,
            e.availability(r.hotelId, r.stay)
                .offers
                .first { it.roomTypeId == r.roomTypeId }
                .availableCount,
        )
    }

    @Test
    fun atHotelAndOtherOwnerCannotRead() = runBlocking {
        val u = user()
        val b = e.create(u.id, request(method = "PAY_AT_HOTEL"))
        assertEquals("CONFIRMED", b.status)
        assertEquals("UNPAID", b.paymentStatus)
        assertNull(b.receiptId)
        e.register(RegisterRequest("other@example.com", "OtherPass!2026", "Другой", null))
        e.login(LoginRequest("other@example.com", "OtherPass!2026"))
        error("NOT_FOUND") { e.order(b.id) }
        error("NOT_FOUND") { e.cancel(b.id) }
    }

    @Test
    fun adminRateBlockConflictAndHistorySnapshots() = runBlocking {
        val u = user()
        val r = request()
        val b = e.create(u.id, r)
        admin()
        val a = LocalAdmin(e)
        val type = a.list("type").first { it.id == r.roomTypeId }
        val rate = a.list("rate").first { it.id == r.rateId }
        error("VALIDATION") { a.save("rate", null, rate.fields, true, 0) }
        val occupied = e.dao.allocations().first { it.bookingId == b.id }.roomId
        error("VALIDATION") {
            a.save(
                "block",
                null,
                mapOf(
                    "roomId" to occupied,
                    "start" to r.stay.checkIn,
                    "end" to r.stay.checkOut,
                    "reason" to "Ремонт",
                ),
                true,
                0,
            )
        }
        val hotel = a.list("hotel").first { it.id == r.hotelId }
        a.save("hotel", hotel.id, hotel.fields + ("name" to "Новое название"), true, hotel.version)
        error("EDIT_CONFLICT") { a.save("hotel", hotel.id, hotel.fields, true, hotel.version) }
        user()
        assertEquals(b.snapshot.hotelName, e.order(b.id).snapshot.hotelName)
        assertNotEquals(b.snapshot.hotelName, e.catalog().hotel(r.hotelId)!!.name)
    }

    @Test
    fun fullIntervalAndFiltersAreValidated() = runBlocking {
        user()
        val r = request()
        val a = e.availability(r.hotelId, r.stay)
        assertEquals(4, a.offers.first { it.kind == "STANDARD" }.availableCount)
        error("VALIDATION") { e.search(r.stay.copy(checkOut = r.stay.checkIn), 0) }
        error("VALIDATION") { e.search(r.stay.copy(adults = 1, rooms = 2), 0) }
        assertTrue(e.search(r.stay.copy(maxPrice = 1), 0).items.isEmpty())
        assertEquals(
            e.search(r.stay.copy(sort = "PRICE"), 0),
            e.search(r.stay.copy(sort = "PRICE"), 0),
        )
    }

    @Test
    fun allSixMapsAreReadableAndContainCenters() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Geo.centers.forEach { (city, point) ->
            val file = java.io.File(ctx.cacheDir, "test-$city.map")
            try {
                ctx.assets.open("maps/$city.map").use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                val map = org.mapsforge.map.reader.MapFile(file)
                try {
                    assertTrue(
                        map.boundingBox()
                            .contains(org.mapsforge.core.model.LatLong(point.first, point.second))
                    )
                    val z: Byte = 15
                    val tile =
                        org.mapsforge.core.model.Tile(
                            org.mapsforge.core.util.MercatorProjection.longitudeToTileX(
                                point.second,
                                z,
                            ),
                            org.mapsforge.core.util.MercatorProjection.latitudeToTileY(
                                point.first,
                                z,
                            ),
                            z,
                            256,
                        )
                    assertTrue(
                        "$city содержит улицы, а не только заголовок",
                        map.readMapData(tile).ways.isNotEmpty(),
                    )
                } finally {
                    map.close()
                }
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun adminCanCreateEntireCatalogueAndArchiveWithoutDeleting() = runBlocking {
        admin()
        val a = LocalAdmin(e)
        suspend fun add(kind: String, vararg fields: Pair<String, String>) =
            a.save(kind, null, fields.toMap(), true, 0)
        val country = add("country", "name" to "Учебная страна", "currency" to "RUB")
        val city =
            add(
                "city",
                "name" to "Учебный город",
                "countryId" to country,
                "caption" to "За пределами карт",
                "latitude" to "10",
                "longitude" to "10",
                "timezone" to "Europe/Moscow",
            )
        val hotel =
            add(
                "hotel",
                "name" to "Учебный отель",
                "cityId" to city,
                "stars" to "4",
                "rating" to "8",
                "address" to "Учебная улица",
                "description" to "Для теста",
                "latitude" to "10",
                "longitude" to "10",
            )
        add("amenity", "code" to "TEST_FEATURE", "name" to "Новое удобство")
        val type =
            add(
                "type",
                "hotelId" to hotel,
                "kind" to "STANDARD",
                "capacity" to "2",
                "area" to "25",
                "amenities" to "WIFI,TEST_FEATURE",
            )
        val room = add("room", "typeId" to type, "number" to "101")
        add(
            "rate",
            "typeId" to type,
            "amount" to "400000",
            "currency" to "RUB",
            "start" to "2026-01-01",
            "end" to "2100-01-01",
            "demo" to "true",
            "atHotel" to "true",
            "freeCancelHours" to "24",
        )
        add(
            "service",
            "hotelId" to hotel,
            "code" to "breakfast",
            "name" to "Завтрак",
            "description" to "Учебный",
            "amount" to "50000",
            "perNight" to "true",
        )
        val place =
            add(
                "place",
                "cityId" to city,
                "category" to "CAFE",
                "name" to "Кафе",
                "address" to "Учебный адрес",
                "description" to "Описание",
                "latitude" to "10",
                "longitude" to "10",
            )
        add("link", "hotelId" to hotel, "placeId" to place)
        val block =
            add(
                "block",
                "roomId" to room,
                "start" to "2026-10-15",
                "end" to "2026-10-17",
                "reason" to "Ремонт",
            )
        val b = a.list("block").first { it.id == block }
        a.save("block", b.id, b.fields + ("end" to "2026-10-18"), true, b.version)
        val h = e.catalog().hotel(hotel)!!
        assertTrue("Новое удобство" in h.amenityLabels)
        assertEquals(listOf(place), h.nearbyPlaceIds)
        assertEquals(1, h.services.size)
        val record = a.list("hotel").first { it.id == hotel }
        a.save("hotel", hotel, record.fields, false, record.version)
        assertNull(e.catalog().hotel(hotel))
        assertNotNull(e.dao.entry(hotel))
        assertTrue(a.audit().isNotEmpty())
    }

    @Test
    fun galleryImportIsLocalAndOrdered() = runBlocking {
        admin()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val source = java.io.File(ctx.cacheDir, "confirmations/test-import.png")
        source.parentFile!!.mkdirs()
        val bitmap =
            android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            val uri =
                androidx.core.content.FileProvider.getUriForFile(
                    ctx,
                    ctx.packageName + ".documents",
                    source,
                )
            val file = LocalMedia.import(ctx, uri)
            val file2 = LocalMedia.import(ctx, uri)
            val a = LocalAdmin(e)
            val h = e.catalog().hotels.first()
            a.photo("hotel", h.id, file)
            a.photo("hotel", h.id, file2)
            assertEquals(listOf(file2, file), e.catalog().hotel(h.id)!!.photoFiles)
            a.photo("hotel", h.id, file)
            assertEquals(listOf(file, file2), e.catalog().hotel(h.id)!!.photoFiles)
            a.photo("hotel", h.id, file, true)
            assertEquals(listOf(file2), e.catalog().hotel(h.id)!!.photoFiles)
            LocalMedia.file(ctx, file).delete()
            LocalMedia.file(ctx, file2).delete()
        } finally {
            bitmap.recycle()
            source.delete()
        }
        Unit
    }
}
