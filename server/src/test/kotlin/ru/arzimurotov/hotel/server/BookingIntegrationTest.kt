package ru.arzimurotov.hotel.server

import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.arzimurotov.hotel.domain.*

/** Реальная PostgreSQL обязательна: H2 не доказывает работу блокировок и конкурентных броней.
 * Тест работает только в одноразовой базе scripts/dev.py test-sql, основную базу не очищает. */
class BookingIntegrationTest {
    private class TestClock(var now:Instant):Clock() {override fun instant()=now;override fun getZone()=ZoneOffset.UTC;override fun withZone(zone:ZoneId):Clock=Clock.fixed(now,zone)}
    @Test fun transactionsExpiryPaymentsAndHistoricalPdf()=runBlocking {
        val url=System.getenv("HOTEL_TEST_DB_URL");assumeTrue(url!=null)
        require(Regex("^jdbc:postgresql://127\\.0\\.0\\.1:55432/hotel_coursework_test_[0-9a-f]{12}$").matches(url!!))
        val config=ServerConfig.fromEnvironment(mapOf("DB_URL" to url,"DB_USER" to "hotel_app","DB_PASSWORD" to System.getenv("HOTEL_TEST_DB_PASSWORD")))
        DatabaseService(config).use {db->
            val catalog=CatalogRepository(db);catalog.seed()
            val clock=TestClock(Instant.now());val search=SearchRepository(db,clock);search.seedBlocks();search.seedBlocks()
            val repo=BookingRepository(db,search,clock);val service=BookingService(repo)
            val auth=AuthService(UserRepository(db),TokenService("transaction-only-secret-".repeat(4)))
            val user=auth.register(RegisterRequest("booking-${UUID.randomUUID()}@example.test","Safe-test-pass-123","Клиент SQL"))
            val stranger=auth.register(RegisterRequest("other-${UUID.randomUUID()}@example.test","Safe-test-pass-123","Другой клиент"))
            val data=catalog.load();val hotel=data.hotels.first {it.legacyId.endsWith("6") || it.legacyId.endsWith("5")}
            val country=data.city(hotel.cityId).countryId
            val start=LocalDate.now().plusDays(25)
            val q=StayRequest(country,hotel.cityId,start.toString(),start.plusDays(3).toString(),rooms=4,adults=4)
            val offer=search.availability(hotel.id,q).offers.first {it.kind=="STANDARD"}
            fun request(stay:StayRequest=q,payment:String="ONLINE_DEMO",o:AvailableOffer=offer)=CreateBooking(hotel.id,o.roomTypeId,o.rateId,stay,paymentMethod=payment,expectedTotal=o.stayTotal,idempotencyKey=UUID.randomUUID().toString())
            val r=request()
            val competing=coroutineScope { listOf(r,request()).map { v->async {runCatching {repo.create(user,v)}} }.awaitAll() }
            assertEquals(1,competing.count {it.isSuccess});assertTrue(competing.first {it.isFailure}.exceptionOrNull() is ApiFailure)
            val b=competing.first {it.isSuccess}.getOrThrow()
            val used=if(b.id==runCatching {repo.create(user,r)}.getOrNull()?.id) r else null
            // Повтор успешного ключа не продлевает expiresAt и не меняет цену.
            if(used!=null) {assertEquals(b.id,repo.create(user,used).id);assertEquals(b.expiresAt,repo.create(user,used).expiresAt)}
            assertTrue(search.availability(hotel.id,q).offers.none {it.kind=="STANDARD"})
            val payment=DemoPayment(b.id,UUID.randomUUID().toString());val paid=repo.pay(user,payment)
            assertEquals("PAID",paid.paymentStatus);assertEquals(paid.transactionId,repo.pay(user,payment).transactionId)
            assertNotNull(paid.receiptId)
            assertTrue(runCatching {repo.pay(user,DemoPayment(b.id,UUID.randomUUID().toString()))}.isFailure)
            assertTrue(runCatching {repo.get(stranger,b.id)}.isFailure)
            assertTrue(runCatching {repo.receipt(stranger,paid.receiptId!!)}.isFailure)
            db.query {c->c.execute("UPDATE hotels SET name='Новое название' WHERE id=?",uid(hotel.id))}
            val pdf=ReceiptService(service).download(user,paid.receiptId!!)
            assertTrue(pdf.size>1000)
            org.apache.pdfbox.Loader.loadPDF(pdf).use {doc->val text=org.apache.pdfbox.text.PDFTextStripper().getText(doc);assertTrue(text.contains(b.snapshot.hotelName));assertTrue(text.contains("Не является фискальным чеком"))}
            val cancelled=repo.cancel(user,b.id);assertEquals("REVERSED",cancelled.paymentStatus);assertEquals(cancelled.id,repo.cancel(user,b.id).id)
            assertEquals("PAID",repo.receipt(user,paid.receiptId!!).first.paymentStatus)
            assertEquals(4,search.availability(hotel.id,q).offers.first {it.kind=="STANDARD"}.availableCount)
            val pending=repo.create(user,request());clock.now=clock.now.plusSeconds(901)
            assertEquals("CANCELLED",repo.get(user,pending.id).status)
            assertTrue(runCatching {repo.pay(user,DemoPayment(pending.id,UUID.randomUUID().toString()))}.isFailure)
            assertEquals(4,search.availability(hotel.id,q).offers.first {it.kind=="STANDARD"}.availableCount)
            repo.maintenance();assertEquals("EXPIRED",repo.get(user,pending.id).cancellationReason)
            val atHotel=repo.create(user,request(payment="PAY_AT_HOTEL"));assertEquals("CONFIRMED",atHotel.status);assertEquals("UNPAID",atHotel.paymentStatus);assertNull(atHotel.receiptId)
            assertTrue(runCatching {repo.pay(user,DemoPayment(atHotel.id,UUID.randomUUID().toString()))}.isFailure)
            val adjacent=q.copy(checkIn=q.checkOut,checkOut=start.plusDays(5).toString())
            assertEquals(4,search.availability(hotel.id,adjacent).offers.first {it.kind=="STANDARD"}.availableCount)
            val checkin=start.atTime(14,0).atZone(ZoneId.of(atHotel.snapshot.timezone)).toInstant()
            clock.now=checkin.minusSeconds(3600);assertTrue(runCatching {repo.cancel(user,atHotel.id)}.isFailure)
            clock.now=start.plusDays(3).atTime(12,1).atZone(ZoneId.of(atHotel.snapshot.timezone)).toInstant();repo.maintenance()
            assertEquals("COMPLETED",repo.get(user,atHotel.id).status)
            // Значение остаётся в SQL, проверяется через другой репозиторий (без RAM-кеша).
            assertEquals(atHotel.snapshot,BookingRepository(db,search,clock).get(user,atHotel.id).snapshot)
        }
    }
}
