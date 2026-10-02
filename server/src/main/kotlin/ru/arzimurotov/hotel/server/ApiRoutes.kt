package ru.arzimurotov.hotel.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ru.arzimurotov.hotel.domain.*

/** HTTP-слой не содержит SQL и не принимает роль/active от клиента. Списки имеют предел 100. */
private fun pageParams(call: ApplicationCall): Pair<Int,Int> {
        fun number(key: String, fallback: Int) = call.request.queryParameters[key]?.let { it.toIntOrNull() ?: invalid(mapOf(key to "Требуется целое число.")) } ?: fallback
        val offset=number("offset",0); val limit=number("limit",20)
        if(offset<0 || limit !in 1..100) invalid(mapOf("pagination" to "offset ≥ 0; limit от 1 до 100."))
        return offset to limit
    }
private fun id(value: String?): String {
        return try { uid(value ?: "").toString() } catch(_: IllegalArgumentException) { invalid(mapOf("id" to "Требуется UUID.")) }
    }
private suspend inline fun <reified T> ApplicationCall.list(values: List<T>) {
        val (offset,limit)=pageParams(this)
        respond(Page(values.drop(offset).take(limit),values.size,offset,limit))
    }
/** Query-параметры имеют те же ограничения, что DTO оформления; неизвестные значения не игнорируются. */
private fun stay(call:ApplicationCall):StayRequest {
    val p=call.request.queryParameters
    fun required(k:String)=p[k] ?: invalid(mapOf(k to "Обязательное поле."))
    fun int(k:String,d:Int)=p[k]?.toIntOrNull() ?: if(p[k]==null) d else invalid(mapOf(k to "Требуется целое число."))
    fun double(k:String,d:Double)=p[k]?.toDoubleOrNull() ?: if(p[k]==null) d else invalid(mapOf(k to "Требуется число."))
    return StayRequest(required("countryId"),p["cityId"],required("checkIn"),required("checkOut"),int("adults",2),int("children",0),int("rooms",1),
        p["stars"]?.split(',')?.map { it.toIntOrNull() ?: invalid(mapOf("stars" to "Укажите 3,4,5.")) }?.toSet() ?: emptySet(),double("minRating",0.0),
        p["maxPrice"]?.let { it.toLongOrNull() ?: invalid(mapOf("maxPrice" to "Требуется сумма в минимальных единицах.")) },
        p["amenities"]?.split(',')?.toSet() ?: emptySet(),p["roomKind"],p["maxDistanceKm"]?.let { double("maxDistanceKm",0.0) },p["name"] ?: "",p["sort"] ?: "RECOMMENDED")
}
fun Route.apiRoutes(catalog: CatalogRepository, auth: AuthService, search:SearchService?=null, bookings:BookingService?=null) {
    val limiter=AuthLimiter()
    route("/api/v1") {
        post("/auth/register") {
            limiter.check(call.request.local.remoteHost)
            call.respond(HttpStatusCode.Created,auth.register(call.receive<RegisterRequest>()))
        }
        post("/auth/login") {
            limiter.check(call.request.local.remoteHost)
            call.respond(auth.login(call.receive<LoginRequest>()))
        }
        get("/profile") { call.respond(auth.authenticated(call.request.headers["Authorization"])) }
        patch("/profile") {
            val user=auth.authenticated(call.request.headers["Authorization"])
            call.respond(auth.update(user,call.receive<ProfilePatch>()))
        }
        get("/locations/countries") { call.list(catalog.load().countries) }
        get("/locations/cities") {
            val country=call.request.queryParameters["countryId"]?.let(::id)
            call.list(catalog.load().cities.filter { country==null || it.countryId==country })
        }
        get("/hotels") {
            val city=call.request.queryParameters["cityId"]?.let(::id)
            call.list(catalog.load().hotels.filter { city==null || it.cityId==city })
        }
        if(search!=null) {
            get("/hotels/search") { val (o,l)=pageParams(call);call.respond(search.search(stay(call),o,l)) }
            get("/hotels/{id}/availability") { call.respond(search.availability(id(call.parameters["id"]),stay(call))) }
        }
        if(bookings!=null) {
            val receipts=ReceiptService(bookings)
            post("/bookings") { val u=auth.authenticated(call.request.headers["Authorization"]);call.respond(HttpStatusCode.Created,bookings.create(u,call.receive<CreateBooking>())) }
            get("/bookings") { val u=auth.authenticated(call.request.headers["Authorization"]);val (o,l)=pageParams(call);call.respond(bookings.list(u,o,l)) }
            get("/bookings/{id}") { val u=auth.authenticated(call.request.headers["Authorization"]);call.respond(bookings.get(u,id(call.parameters["id"]))) }
            post("/bookings/{id}/cancel") { val u=auth.authenticated(call.request.headers["Authorization"]);call.respond(bookings.cancel(u,id(call.parameters["id"]))) }
            post("/payments/demo") { val u=auth.authenticated(call.request.headers["Authorization"]);call.respond(bookings.pay(u,call.receive<DemoPayment>())) }
            get("/receipts/{id}") {
                val u=auth.authenticated(call.request.headers["Authorization"])
                call.response.headers.append("Cache-Control","private, no-store")
                call.respondBytes(receipts.download(u,id(call.parameters["id"])),io.ktor.http.ContentType.Application.Pdf)
            }
        }
        get("/hotels/{id}") {
            val value=catalog.load().hotel(id(call.parameters["id"])) ?: throw ApiFailure(HttpStatusCode.NotFound,"NOT_FOUND","Гостиница не найдена.")
            call.respond(value)
        }
        get("/rooms") { call.list(catalog.rooms(id(call.request.queryParameters["hotelId"]))) }
        get("/places") {
            val city=call.request.queryParameters["cityId"]?.let(::id)
            call.list(catalog.load().places.filter { city==null || it.cityId==city })
        }
        get("/hotels/{id}/places") { call.list(catalog.hotelPlaces(id(call.parameters["id"]))) }
        get("/admin/summary") {
            val user=auth.authenticated(call.request.headers["Authorization"])
            auth.requireAdmin(user); call.respond(catalog.summary())
        }
    }
}
