package ru.arzimurotov.hotel.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.sql.Date
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.arzimurotov.hotel.domain.*

/** Контракт API, расчёт услуг, отсутствие дублей и гонки проверяются на PostgreSQL, не H2. */
class TravelApiIntegrationTest {
    private fun config():ServerConfig {
        val url=System.getenv("HOTEL_TEST_DB_URL");assumeTrue(url!=null)
        require(Regex("^jdbc:postgresql://127\\.0\\.0\\.1:55432/hotel_coursework_test_[0-9a-f]{12}$").matches(url!!))
        return ServerConfig.fromEnvironment(mapOf("DB_URL" to url,"DB_USER" to "hotel_app","DB_PASSWORD" to System.getenv("HOTEL_TEST_DB_PASSWORD")))
    }
    @Test fun httpFiltersBookingServicesAndPdfRetry()=runBlocking {
        DatabaseService(config()).use {db->
            val catalog=CatalogRepository(db);catalog.seed();val data=catalog.load()
            val search=SearchRepository(db);search.seedBlocks();val repo=BookingRepository(db,search)
            val auth=AuthService(UserRepository(db),TokenService("api-integration-secret-".repeat(4)))
            val user=auth.register(RegisterRequest("travel-${UUID.randomUUID()}@example.test","Safe-test-pass-123","API Клиент"))
            val token=auth.login(LoginRequest(user.email,"Safe-test-pass-123")).token
            val hotel=data.hotels.first {it.legacyId=="tashkent-5"};val country=data.city(hotel.cityId).countryId
            val start=LocalDate.now().plusDays(45)
            val q=StayRequest(country,hotel.cityId,start.toString(),start.plusDays(3).toString())
            val full=search.search(q.copy(sort="PRICE"),0,100)
            assertEquals(6,full.total)
            assertEquals(full.items.map {it.offers.minOf {o->o.nightlyPrice}}.sorted(),full.items.map {it.offers.minOf {o->o.nightlyPrice}})
            val first=search.search(q,0,2);val next=search.search(q,2,2)
            assertEquals(2,first.items.size);assertTrue(first.items.none {hit->next.items.any {it.hotelId==hit.hotelId}})
            assertEquals(0,search.search(q.copy(name="%' OR 1=1 --"),0,20).total)
            assertTrue(search.search(q.copy(stars=setOf(hotel.stars),roomKind="STANDARD",maxDistanceKm=hotel.distanceKm,maxPrice=hotel.rooms.first {it.kind==RoomKind.STANDARD}.pricePerNight),0,20).items.any {it.hotelId==hotel.id})
            assertTrue(runCatching {search.search(q.copy(rooms=4,adults=2),0,20)}.isFailure)
            assertTrue(runCatching {search.search(q.copy(checkIn=q.checkOut),0,20)}.isFailure)
            listOf(q.copy(checkIn=start.minusDays(100).toString()),
                q.copy(checkOut=start.plusDays(91).toString()),q.copy(adults=0),q.copy(children=5),
                q.copy(rooms=5),q.copy(roomKind="UNKNOWN"),q.copy(amenities=setOf("UNKNOWN"))).forEach {
                assertTrue("Invalid search must fail: $it",runCatching {search.search(it,0,20)}.isFailure)
            }
            val a=search.availability(hotel.id,q);val offer=a.offers.first {it.kind=="STANDARD"}
            // Тариф должен покрывать и последнюю ночь. Частично действующий тариф
            // не превращается в предложение за полный период.
            db.query {c->c.execute("UPDATE rates SET valid_to=? WHERE id=?",Date.valueOf(start.plusDays(2)),uid(offer.rateId))}
            assertTrue(search.availability(hotel.id,q).offers.none {it.kind=="STANDARD"})
            db.query {c->c.execute("UPDATE rates SET valid_to=? WHERE id=?",Date.valueOf("2100-01-01"),uid(offer.rateId))}
            val transfer=a.services.first {it.code=="transfer"}
            val nightly=a.services.first {it.perNight}
            val selected=setOf(transfer.id,nightly.id)
            val r=CreateBooking(hotel.id,offer.roomTypeId,offer.rateId,q,selected,
                TransferDetails("TAS","HY101",q.checkIn+"T12:00","+998901234567"),"ONLINE_DEMO",
                offer.stayTotal+transfer.price+nightly.price*3,UUID.randomUUID().toString())
            assertTrue(runCatching {repo.create(user,r.copy(expectedTotal=r.expectedTotal+1))}.exceptionOrNull().let {it is ApiFailure && it.code=="PRICE_CHANGED"})
            assertTrue(runCatching {repo.create(user,r.copy(transfer=null))}.isFailure)
            val b=repo.create(user,r);assertEquals(r.expectedTotal,b.total);assertEquals(2,b.snapshot.services.size)
            assertEquals(b.id,repo.create(user,r).id)
            assertTrue(runCatching {repo.create(user,r.copy(expectedTotal=0))}.exceptionOrNull().let {it is ApiFailure && it.code=="IDEMPOTENCY_CONFLICT"})
            db.query {c->c.execute("UPDATE hotel_services SET amount=amount+1 WHERE hotel_id=? AND service_id=?",uid(hotel.id),uid(transfer.id))}
            assertEquals(b.total,repo.get(user,b.id).total)
            val payment=DemoPayment(b.id,UUID.randomUUID().toString())
            val paid=repo.pay(user,payment)
            val service=BookingService(repo)
            assertTrue(runCatching {ReceiptService(service) {_,_->error("Injected generation failure")}.download(user,paid.receiptId!!)}.isFailure)
            assertEquals("PAID",repo.get(user,b.id).paymentStatus)
            val pdf=ReceiptService(service).download(user,paid.receiptId!!);assertTrue(pdf.size>1000)
            testApplication {
                application {hotelModule(db,catalog,auth,SearchService(search),service)}
                val api=createClient {install(ContentNegotiation){json()}}
                val query="countryId=$country&cityId=${hotel.cityId}&checkIn=${q.checkIn}&checkOut=${q.checkOut}"
                assertEquals(HttpStatusCode.OK,api.get("/api/v1/hotels/search?$query").status)
                assertEquals(6,api.get("/api/v1/hotels/search?$query").body<SearchResponse>().total)
                assertEquals(HttpStatusCode.BadRequest,api.get("/api/v1/hotels/search?$query&limit=101").status)
                assertEquals(HttpStatusCode.BadRequest,api.get("/api/v1/hotels/search?$query&minRating=NaN").status)
                assertEquals(HttpStatusCode.OK,api.get("/api/v1/hotels/${hotel.id}/availability?$query").status)
                assertEquals(HttpStatusCode.Unauthorized,api.get("/api/v1/bookings").status)
                assertEquals(b.id,api.get("/api/v1/bookings/${b.id}"){bearerAuth(token)}.body<Booking>().id)
                assertEquals(HttpStatusCode.OK,api.get("/api/v1/receipts/${paid.receiptId}"){bearerAuth(token)}.status)
                assertTrue(api.get("/api/v1/receipts/${paid.receiptId}"){bearerAuth(token)}.headers["Content-Type"]!!.startsWith("application/pdf"))
                assertEquals(HttpStatusCode.BadRequest,api.post("/api/v1/payments/demo"){bearerAuth(token);contentType(ContentType.Application.Json);setBody("""{"bookingId":"${b.id}","idempotencyKey":"${UUID.randomUUID()}","cardNumber":"fake"}""")}.status)
            }
            repo.cancel(user,b.id)
            Unit
        }
    }
    @Test fun wholeIntervalAndPaymentCancellationRace()=runBlocking {
        DatabaseService(config()).use {db->
            val catalog=CatalogRepository(db);catalog.seed();val data=catalog.load()
            val search=SearchRepository(db);val repo=BookingRepository(db,search)
            val auth=AuthService(UserRepository(db),TokenService("race-integration-secret-".repeat(4)))
            val user=auth.register(RegisterRequest("race-${UUID.randomUUID()}@example.test","Safe-test-pass-123","Гонка"))
            val hotel=data.hotels.first {it.legacyId=="samarkand-5"};val start=LocalDate.now().plusDays(55)
            val q=StayRequest(data.city(hotel.cityId).countryId,hotel.cityId,start.toString(),start.plusDays(2).toString(),adults=3,rooms=3)
            val type=search.availability(hotel.id,q).offers.first {it.kind=="STANDARD"}.roomTypeId
            val rooms=catalog.rooms(hotel.id).filter {it.roomTypeId==type}.take(2)
            db.query {c->rooms.forEachIndexed {i,r->c.execute("INSERT INTO room_blocks(id,room_id,start_date,end_date,reason) VALUES(?,?,?,?, 'TEST ONLY')",UUID.randomUUID(),uid(r.id),Date.valueOf(start.plusDays(i.toLong())),Date.valueOf(start.plusDays(i+1L)))} }
            assertTrue(search.availability(hotel.id,q).offers.none {it.kind=="STANDARD"})
            val small=q.copy(adults=2,rooms=1);val offer=search.availability(hotel.id,small).offers.first {it.kind=="STANDARD"}
            assertEquals(2,offer.availableCount)
            val b=repo.create(user,CreateBooking(hotel.id,offer.roomTypeId,offer.rateId,small,paymentMethod="ONLINE_DEMO",expectedTotal=offer.stayTotal,idempotencyKey=UUID.randomUUID().toString()))
            coroutineScope { listOf(async {runCatching {repo.pay(user,DemoPayment(b.id,UUID.randomUUID().toString()))}},async {runCatching {repo.cancel(user,b.id)}}).awaitAll() }
            assertEquals("CANCELLED",repo.get(user,b.id).status)
            assertTrue(db.query {c->c.select("SELECT count(*) FROM payments WHERE booking_id=?",uid(b.id)) {it.getInt(1)}.single()}<=1)
            assertEquals(2,search.availability(hotel.id,small).offers.first {it.kind=="STANDARD"}.availableCount)
        }
    }
}
