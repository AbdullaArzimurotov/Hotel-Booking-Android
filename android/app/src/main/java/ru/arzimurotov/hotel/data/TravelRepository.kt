package ru.arzimurotov.hotel.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.arzimurotov.hotel.domain.*
import javax.inject.Inject
import javax.inject.Singleton

fun SearchQuery.wire()=StayRequest(countryId,cityId,checkIn.toString(),checkOut.toString(),adults,children,rooms,
    filters.stars,filters.minRating,filters.maxPrice,filters.amenities.map { it.name }.toSet(),filters.roomKind?.name,filters.maxDistanceKm,name,sort.name)

interface TravelRepository {
    suspend fun search(q:StayRequest,offset:Int):SearchResponse
    suspend fun availability(id:String,q:StayRequest):Availability
    suspend fun create(userId:String,r:CreateBooking):Booking
    suspend fun bookings(offset:Int):Page<Booking>
    suspend fun booking(id:String):Booking
    suspend fun cancel(id:String):Booking
    suspend fun pay(userId:String,id:String):Booking
    suspend fun receipt(id:String):ByteArray
}

/** Все коммерческие операции идут в REST API. Сетевой сбой не заменяется фиктивным успехом.
 * Ключ незавершённого POST шифруется Keystore и повторно используется после потери ответа. */
@Singleton class RemoteTravelRepository @Inject constructor(private val client:HttpClient,
    private val connection:ApiConnection,private val session:SessionStore):TravelRepository {
    private val json=kotlinx.serialization.json.Json { encodeDefaults=true }
    private fun url(p:String)=connection.baseUrl+"api/v1/"+p
    private fun io.ktor.client.request.HttpRequestBuilder.stay(q:StayRequest) {
        parameter("countryId",q.countryId);q.cityId?.let { parameter("cityId",it) }
        parameter("checkIn",q.checkIn);parameter("checkOut",q.checkOut);parameter("adults",q.adults);parameter("children",q.children);parameter("rooms",q.rooms)
        if(q.stars.isNotEmpty()) parameter("stars",q.stars.sorted().joinToString(","))
        parameter("minRating",q.minRating);q.maxPrice?.let { parameter("maxPrice",it) }
        if(q.amenities.isNotEmpty()) parameter("amenities",q.amenities.sorted().joinToString(","))
        q.roomKind?.let { parameter("roomKind",it) };q.maxDistanceKm?.let { parameter("maxDistanceKm",it) }
        parameter("name",q.name);parameter("sort",q.sort)
    }
    private suspend fun token()=withContext(Dispatchers.IO) { session.read() } ?: throw AuthProblem(ApiError("UNAUTHORIZED","Войдите в аккаунт."))
    private suspend fun check(r:HttpResponse):HttpResponse {
        if(r.status.value !in 200..299) {
            if(r.status==HttpStatusCode.Unauthorized) withContext(Dispatchers.IO) { session.invalidate() }
            throw AuthProblem(runCatching { r.body<ApiError>() }.getOrElse { ApiError("HTTP_ERROR","Сервис недоступен.") })
        };return r
    }
    override suspend fun search(q:StayRequest,offset:Int):SearchResponse=check(client.get(url("hotels/search")) { stay(q);parameter("offset",offset);parameter("limit",20) }).body()
    override suspend fun availability(id:String,q:StayRequest):Availability=check(client.get(url("hotels/$id/availability")) { stay(q) }).body()
    private suspend fun pending(owner:String,payload:String):String=withContext(Dispatchers.IO) {
        val fingerprint=java.security.MessageDigest.getInstance("SHA-256").digest((connection.baseUrl+owner+payload).toByteArray()).joinToString("") { "%02x".format(it) }
        val existing=session.readPending()?.split('|',limit=2)
        if(existing?.firstOrNull()==fingerprint) existing[1] else java.util.UUID.randomUUID().toString().also { session.savePending("$fingerprint|$it") }
    }
    override suspend fun create(userId:String,r:CreateBooking):Booking {
        val key=pending(userId,json.encodeToString(CreateBooking.serializer(),r.copy(idempotencyKey="")))
        val t=token()
        val result=check(client.post(url("bookings")) { bearerAuth(t);contentType(ContentType.Application.Json);setBody(r.copy(idempotencyKey=key)) }).body<Booking>()
        withContext(Dispatchers.IO) { session.clearPending() };return result
    }
    override suspend fun bookings(offset:Int):Page<Booking> { val t=token();return check(client.get(url("bookings")) { bearerAuth(t);parameter("offset",offset);parameter("limit",20) }).body() }
    override suspend fun booking(id:String):Booking { val t=token();return check(client.get(url("bookings/$id")) { bearerAuth(t) }).body() }
    override suspend fun cancel(id:String):Booking { val t=token();return check(client.post(url("bookings/$id/cancel")) { bearerAuth(t) }).body() }
    override suspend fun pay(userId:String,id:String):Booking {
        val key=pending(userId,"payment:$id");val t=token()
        val result=check(client.post(url("payments/demo")) { bearerAuth(t);contentType(ContentType.Application.Json);setBody(DemoPayment(id,key)) }).body<Booking>()
        withContext(Dispatchers.IO) { session.clearPending() };return result
    }
    override suspend fun receipt(id:String):ByteArray { val t=token();return check(client.get(url("receipts/$id")) { bearerAuth(t) }).body() }
}
