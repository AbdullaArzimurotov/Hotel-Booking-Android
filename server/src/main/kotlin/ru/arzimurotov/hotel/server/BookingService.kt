package ru.arzimurotov.hotel.server

import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.sql.Date
import java.sql.Timestamp
import java.time.*
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import ru.arzimurotov.hotel.domain.*

/** Транзакционный репозиторий заказов. Все изменения доступности: номера UUID ASC, затем заказ.
 * Для создания дополнительно блокируем тип и ценовые строки; SQL не выполняется на Netty. */
class BookingRepository(private val db: DatabaseService, private val search: SearchRepository,
    private val clock: Clock = Clock.systemUTC()) {
    private val json=Json { encodeDefaults=true }
    private fun Connection.lockRooms(id:UUID) {
        select("SELECT rm.id FROM rooms rm JOIN booking_rooms br ON br.room_id=rm.id WHERE br.booking_id=? ORDER BY rm.id FOR UPDATE OF rm",id) { it.getString(1) }
    }
    private fun read(c:Connection,id:UUID,userId:UUID? = null): Booking {
        val now=clock.instant()
        return c.select("SELECT b.*,p.transaction_id,rc.id receipt_id FROM bookings b LEFT JOIN payments p ON p.booking_id=b.id LEFT JOIN receipts rc ON rc.booking_id=b.id WHERE b.id=?"+(if(userId==null) "" else " AND b.user_id=?"),*(if(userId==null) listOf(id) else listOf(id,userId)).toTypedArray()) { r ->
            val expires=r.getTimestamp("expires_at")?.toInstant()
            val expired=r.getString("status")=="PENDING_PAYMENT" && expires!!<=now
            Booking(r.getString("id"),r.getString("number"),r.getDate("check_in").toString(),r.getDate("check_out").toString(),r.getInt("adults"),r.getInt("children"),
                if(expired) "CANCELLED" else r.getString("status"),r.getString("payment_method"),r.getString("payment_status"),r.minor("total_amount"),r.getString("currency"),expires?.epochSecond,
                r.getTimestamp("cancel_until").toInstant().epochSecond,r.getTimestamp("created_at").toInstant().epochSecond,
                if(expired) "EXPIRED" else r.getString("cancellation_reason"),json.decodeFromString<BookingSnapshot>(r.getString("details_snapshot")),r.getString("receipt_id"),r.getString("transaction_id"),now.epochSecond)
        }.singleOrNull() ?: missing()
    }
    private fun hash(request:CreateBooking):String {
        val canonical=request.copy(serviceIds=request.serviceIds.toSortedSet(),stay=request.stay.copy(stars=request.stay.stars.toSortedSet(),amenities=request.stay.amenities.toSortedSet()))
        return java.security.MessageDigest.getInstance("SHA-256").digest(json.encodeToString(canonical).toByteArray()).joinToString("") { "%02x".format(it) }
    }
    suspend fun create(user:UserProfile,r:CreateBooking):Booking=db.query { c ->
        val userId=uid(user.id);val key=uuidField(r.idempotencyKey,"idempotencyKey");val fingerprint=hash(r)
        // Advisory lock сериализует одинаковые ключи до выделения номеров, а UNIQUE остаётся страховкой.
        c.select("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","booking:"+user.id+":"+key) { true }
        val old=c.select("SELECT id,request_hash FROM bookings WHERE user_id=? AND idempotency_key=?",userId,key) { it.getString(1) to it.getString(2) }.singleOrNull()
        if(old!=null) { if(old.second!=fingerprint) conflict("IDEMPOTENCY_CONFLICT","Ключ уже использован для другого заказа.");return@query read(c,uid(old.first),userId) }
        val hotel=uuidField(r.hotelId,"hotelId");val type=uuidField(r.roomTypeId,"roomTypeId");val rate=uuidField(r.rateId,"rateId")
        search.validate(c,r.stay)
        if(r.paymentMethod !in setOf("ONLINE_DEMO","PAY_AT_HOTEL")) invalid(mapOf("paymentMethod" to "Выберите способ оплаты."))
        if(r.expectedTotal !in 0..999999999999L) invalid(mapOf("expectedTotal" to "Некорректная сумма."))
        val info=c.select("SELECT h.name,h.address,ci.timezone FROM room_types t JOIN hotels h ON h.id=t.hotel_id JOIN cities ci ON ci.id=h.city_id WHERE t.id=? AND h.id=? AND h.active AND t.active AND ci.active AND ci.country_id=? FOR SHARE OF h,ci FOR UPDATE OF t",type,hotel,uid(r.stay.countryId)) { Triple(it.getString(1),it.getString(2),it.getString(3)) }.singleOrNull() ?: missing()
        c.select("SELECT id FROM rates WHERE id=? AND room_type_id=? FOR SHARE",rate,type) { it.getString(1) }.singleOrNull() ?: missing()
        val services=c.select("SELECT hs.service_id,hs.amount,hs.charge_unit,s.name,s.code FROM hotel_services hs JOIN services s ON s.id=hs.service_id WHERE hs.hotel_id=? AND hs.active AND s.active ORDER BY hs.service_id FOR SHARE OF hs,s",hotel) {
            ServiceRow(it.getString(1),it.minor("amount"),it.getString("charge_unit"),it.getString("name"),it.getString("code")) }
        r.serviceIds.forEach { uuidField(it,"serviceIds") }
        if(r.serviceIds.any { id->services.none { it.id==id } }) invalid(mapOf("services" to "Услуга недоступна в этой гостинице."))
        val selected=services.filter { it.id in r.serviceIds }
        val zone=ZoneId.of(info.third)
        val checkin=LocalDate.parse(r.stay.checkIn).atTime(14,0).atZone(zone).toInstant()
        val checkout=LocalDate.parse(r.stay.checkOut).atTime(12,0).atZone(zone).toInstant()
        val now=clock.instant()
        if(checkin<=now) invalid(mapOf("dates" to "Время заезда уже наступило."))
        val hasTransfer=selected.any { it.code=="transfer" }
        if(hasTransfer) {
            val t=r.transfer ?: invalid(mapOf("transfer" to "Укажите аэропорт, рейс, время и телефон."))
            val pickup=try { LocalDateTime.parse(t.pickupAt).atZone(zone).toInstant() } catch(_: Exception) { invalid(mapOf("pickupAt" to "Дата и время: ГГГГ-ММ-ДДTЧЧ:ММ.")) }
            if(t.airport.trim().length !in 2..100 || t.flight.trim().length !in 2..30 || !Regex("[+0-9 ()-]{7,30}").matches(t.phone) || pickup<now || pickup>=checkout || pickup<checkin.minusSeconds(86400))
                invalid(mapOf("transfer" to "Проверьте аэропорт, рейс, время встречи и телефон."))
        } else if(r.transfer!=null) invalid(mapOf("transfer" to "Сначала выберите услугу трансфера."))
        // Блокируем все комнаты типа: запрос конкурента дождётся фиксации и повторно прочитает бронь.
        c.select("SELECT id FROM rooms WHERE room_type_id=? ORDER BY id FOR UPDATE",type) { it.getString(1) }
        val decisionAt=clock.instant()
        val q=r.stay.copy(stars=emptySet(),minRating=0.0,maxPrice=null,amenities=emptySet(),roomKind=null,maxDistanceKm=null,name="")
        val offer=search.offers(c,q,decisionAt,r.hotelId).firstOrNull { it.roomTypeId==r.roomTypeId && it.rateId==r.rateId }
            ?: conflict("AVAILABILITY_CHANGED","Номеров недостаточно или тариф недоступен. Повторите поиск.")
        if(r.paymentMethod=="ONLINE_DEMO" && !offer.allowDemo || r.paymentMethod=="PAY_AT_HOTEL" && !offer.allowAtHotel)
            conflict("PAYMENT_METHOD_UNAVAILABLE","Способ оплаты недоступен по тарифу.")
        val nights=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(q.checkIn),LocalDate.parse(q.checkOut))
        val extra=selected.map { s -> BookedService(s.name,if(s.unit=="PER_NIGHT_ROOM") Math.multiplyExact(Math.multiplyExact(s.amount,nights),q.rooms.toLong()) else s.amount,if(s.code=="transfer") r.transfer else null) }
        val total=extra.fold(offer.stayTotal) { a,s->Math.addExact(a,s.total) }
        if(total>999999999999L) invalid(mapOf("total" to "Сумма превышает лимит."))
        if(total!=r.expectedTotal) conflict("PRICE_CHANGED","Цена изменилась. Обновите расчёт и подтвердите новую сумму.")
        val rooms=c.select("""SELECT rm.id,rm.number FROM rooms rm WHERE rm.room_type_id=? AND rm.active
          AND NOT EXISTS(SELECT 1 FROM room_blocks b WHERE b.room_id=rm.id AND b.active AND b.start_date<? AND b.end_date>?)
          AND NOT EXISTS(SELECT 1 FROM booking_rooms br JOIN bookings b ON b.id=br.booking_id WHERE br.room_id=rm.id
            AND b.status IN ('CONFIRMED','COMPLETED','PENDING_PAYMENT') AND (b.status<>'PENDING_PAYMENT' OR b.expires_at>?)
            AND b.check_in<? AND b.check_out>?) ORDER BY rm.id LIMIT ?""",type,Date.valueOf(q.checkOut),Date.valueOf(q.checkIn),Timestamp.from(decisionAt),Date.valueOf(q.checkOut),Date.valueOf(q.checkIn),q.rooms) { it.getString(1) to it.getString(2) }
        if(rooms.size!=q.rooms) conflict("AVAILABILITY_CHANGED","Недостаточно свободных номеров.")
        val id=UUID.randomUUID();val pending=r.paymentMethod=="ONLINE_DEMO"
        val snapshot=BookingSnapshot(info.first,info.second,user.fullName,user.email,offer.kind,rooms.map { it.second },offer.nightlyPrice,offer.stayTotal,extra,info.third)
        c.execute("""INSERT INTO bookings(id,number,user_id,hotel_id,room_type_id,rate_id,check_in,check_out,adults,children,room_count,status,payment_method,payment_status,total_amount,currency,expires_at,cancel_until,checkout_at,created_at,idempotency_key,request_hash,details_snapshot)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'UNPAID',?,?,?,?,?,?,?,?,?::jsonb)""",id,"HC-"+id.toString().take(8).uppercase()+"-"+id.toString().takeLast(4).uppercase(),userId,hotel,type,rate,Date.valueOf(q.checkIn),Date.valueOf(q.checkOut),q.adults,q.children,q.rooms,if(pending) "PENDING_PAYMENT" else "CONFIRMED",r.paymentMethod,amount(total),offer.currency,if(pending) Timestamp.from(decisionAt.plusSeconds(900)) else null,Timestamp.from(checkin.minusSeconds(offer.freeCancelHours*3600L)),Timestamp.from(checkout),Timestamp.from(decisionAt),key,fingerprint,json.encodeToString(snapshot))
        rooms.forEach { c.execute("INSERT INTO booking_rooms(booking_id,room_id) VALUES(?,?)",id,uid(it.first)) }
        selected.zip(extra).forEach { (s,e)->c.execute("INSERT INTO booking_services(booking_id,service_id,amount,details) VALUES(?,?,?,?::jsonb)",id,uid(s.id),amount(e.total),json.encodeToString(e)) }
        read(c,id,userId)
    }
    private data class ServiceRow(val id:String,val amount:Long,val unit:String,val name:String,val code:String)
    suspend fun get(user:UserProfile,id:String):Booking=db.query { read(it,uuidField(id,"id"),uid(user.id)) }
    suspend fun list(user:UserProfile,offset:Int,limit:Int):Page<Booking> = db.query { c ->
        if(offset<0 || limit !in 1..100) invalid(mapOf("pagination" to "Некорректная страница."))
        val count=c.select("SELECT count(*) FROM bookings WHERE user_id=?",uid(user.id)) { it.getInt(1) }.single()
        val ids=c.select("SELECT id FROM bookings WHERE user_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",uid(user.id),limit,offset) { uid(it.getString(1)) }
        Page(ids.map { read(c,it,uid(user.id)) },count,offset,limit)
    }
    suspend fun cancel(user:UserProfile,value:String):Booking=db.query { c ->
        val id=uuidField(value,"id");read(c,id,uid(user.id));c.lockRooms(id)
        c.select("SELECT id FROM bookings WHERE id=? FOR UPDATE",id) { it.getString(1) }
        val b=read(c,id,uid(user.id))
        if(b.status=="CANCELLED") return@query b
        if(b.status=="COMPLETED" || b.status=="CONFIRMED" && clock.instant().epochSecond>=b.cancelUntil)
            conflict("CANCELLATION_CLOSED","Срок отмены истёк.")
        c.execute("UPDATE bookings SET status='CANCELLED',cancellation_reason='USER',payment_status=CASE WHEN payment_status='PAID' THEN 'REVERSED' ELSE payment_status END WHERE id=?",id)
        c.execute("UPDATE payments SET status='REVERSED' WHERE booking_id=?",id)
        read(c,id,uid(user.id))
    }
    suspend fun pay(user:UserProfile,request:DemoPayment):Booking=db.query { c ->
        val id=uuidField(request.bookingId,"bookingId");val key=uuidField(request.idempotencyKey,"idempotencyKey")
        c.select("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","payment:"+user.id+":"+key) { true }
        val old=c.select("SELECT booking_id FROM payments WHERE user_id=? AND idempotency_key=?",uid(user.id),key) { it.getString(1) }.singleOrNull()
        if(old!=null && old!=request.bookingId) conflict("IDEMPOTENCY_CONFLICT","Ключ оплаты уже использован.")
        read(c,id,uid(user.id));c.lockRooms(id)
        c.select("SELECT id FROM bookings WHERE id=? FOR UPDATE",id) { it.getString(1) }
        val b=read(c,id,uid(user.id))
        if(b.paymentStatus=="PAID" && old==request.bookingId) return@query b
        if(b.status!="PENDING_PAYMENT" || b.paymentMethod!="ONLINE_DEMO") conflict("PAYMENT_NOT_ALLOWED","Этот заказ нельзя оплатить: проверьте состояние и срок резерва.")
        val now=clock.instant();val payment=UUID.randomUUID();val receipt=UUID.randomUUID();val transaction="DEMO-"+UUID.randomUUID()
        if(now.epochSecond>=(b.expiresAt ?: 0)) conflict("RESERVATION_EXPIRED","Резерв истёк. Оформите новый заказ.")
        c.execute("INSERT INTO payments(id,booking_id,user_id,idempotency_key,transaction_id,amount,currency,paid_at,status) VALUES(?,?,?,?,?,?,?,?,'PAID')",payment,id,uid(user.id),key,transaction,amount(b.total),b.currency,Timestamp.from(now))
        c.execute("UPDATE bookings SET status='CONFIRMED',payment_status='PAID' WHERE id=?",id)
        val document=b.copy(status="CONFIRMED",paymentStatus="PAID",receiptId=receipt.toString(),transactionId=transaction,serverNow=now.epochSecond)
        c.execute("INSERT INTO receipts(id,payment_id,booking_id,document_snapshot,created_at) VALUES(?,?,?,?::jsonb,?)",receipt,payment,id,json.encodeToString(document),Timestamp.from(now))
        read(c,id,uid(user.id))
    }
    /** Только сохранённые реквизиты: никакого обращения к изменяемому каталогу при выпуске PDF. */
    suspend fun receipt(user:UserProfile,id:String):Pair<Booking,Instant> = db.query { c ->
        c.select("SELECT rc.document_snapshot,rc.created_at FROM receipts rc JOIN bookings b ON b.id=rc.booking_id WHERE rc.id=? AND b.user_id=?",uuidField(id,"id"),uid(user.id)) { json.decodeFromString<Booking>(it.getString(1)) to it.getTimestamp(2).toInstant() }.singleOrNull() ?: missing()
    }
    /** После рестарта истёкшие резервы освобождаются и без этой процедуры. GET не изменяет SQL. */
    suspend fun maintenance() {
        val now=clock.instant()
        val ids=db.query { c->c.select("SELECT id FROM bookings WHERE (status='PENDING_PAYMENT' AND expires_at<=?) OR (status='CONFIRMED' AND checkout_at<=?) ORDER BY id",Timestamp.from(now),Timestamp.from(now)) { uid(it.getString(1)) } }
        ids.forEach { id->db.query { c ->
            c.lockRooms(id);c.select("SELECT id FROM bookings WHERE id=? FOR UPDATE",id) { it.getString(1) }
            val at=Timestamp.from(clock.instant())
            c.execute("UPDATE bookings SET status='CANCELLED',cancellation_reason='EXPIRED' WHERE id=? AND status='PENDING_PAYMENT' AND expires_at<=?",id,at)
            c.execute("UPDATE bookings SET status='COMPLETED' WHERE id=? AND status='CONFIRMED' AND checkout_at<=?",id,at)
        } }
    }
}

/** Бизнес-фасад отделён от HTTP. Владение проверяется репозиторием даже после получения блокировок. */
class BookingService(private val repository:BookingRepository) {
    suspend fun create(u:UserProfile,r:CreateBooking)=repository.create(u,r)
    suspend fun list(u:UserProfile,o:Int,l:Int)=repository.list(u,o,l)
    suspend fun get(u:UserProfile,id:String)=repository.get(u,id)
    suspend fun cancel(u:UserProfile,id:String)=repository.cancel(u,id)
    suspend fun pay(u:UserProfile,r:DemoPayment)=repository.pay(u,r)
    suspend fun receipt(u:UserProfile,id:String)=repository.receipt(u,id)
}
