package ru.arzimurotov.hotel.server

import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.sql.Date
import java.time.*
import ru.arzimurotov.hotel.domain.*

internal fun conflict(code: String, message: String): Nothing = throw ApiFailure(HttpStatusCode.Conflict,code,message)
internal fun missing(): Nothing = throw ApiFailure(HttpStatusCode.NotFound,"NOT_FOUND","Объект не найден.")
internal fun uuidField(value: String, field: String): java.util.UUID = try { uid(value) } catch(_: IllegalArgumentException) { invalid(mapOf(field to "Требуется UUID.")) }

/** Общие правила поиска/брони. Даты относятся к месту проживания, не часовому поясу Mac/телефона. */
internal fun validateStay(q: StayRequest, today: LocalDate) {
    val dates=try { LocalDate.parse(q.checkIn) to LocalDate.parse(q.checkOut) } catch(_: Exception) { invalid(mapOf("dates" to "Укажите даты ISO-8601.")) }
    if(dates.first<today || java.time.temporal.ChronoUnit.DAYS.between(dates.first,dates.second) !in 1..90)
        invalid(mapOf("dates" to "Заезд не в прошлом; проживание от 1 до 90 ночей."))
    if(q.adults !in 1..8 || q.children !in 0..4 || q.rooms !in 1..4 || q.adults<q.rooms || q.adults+q.children>q.rooms*4)
        invalid(mapOf("guests" to "Проверьте гостей и номера; каждому номеру нужен взрослый."))
    if(q.stars.any { it !in 3..5 } || !q.minRating.isFinite() || q.minRating !in 0.0..10.0 ||
        q.maxPrice?.let { it<0 || it>999999999999L }==true || q.maxDistanceKm?.let { !it.isFinite() || it<0 || it>1000 }==true ||
        q.amenities.any { a -> Amenity.entries.none { it.name==a } } || q.roomKind?.let { k -> RoomKind.entries.none { it.name==k } }==true ||
        SortOrder.entries.none { it.name==q.sort } || q.name.length>120)
        invalid(mapOf("filters" to "Недопустимый фильтр."))
}

/** SQL-запрос считает целые свободные физические номера. Просроченный резерв не зависит от cron.
 * Значения передаются PreparedStatement; динамическими остаются только доверенные SQL-фрагменты. */
class SearchRepository(private val db: DatabaseService, private val clock: Clock = Clock.systemUTC()) {
    suspend fun seedBlocks() = db.query { c ->
        c.execute("INSERT INTO app_metadata(key,value) VALUES('demo_block_anchor',?) ON CONFLICT DO NOTHING",LocalDate.now(clock).toString())
        val start=LocalDate.parse(c.select("SELECT value FROM app_metadata WHERE key='demo_block_anchor'") { it.getString(1) }.single()).plusDays(7)
        // Ранжирование гостиниц внутри города стабильно по исходному catalog_key.
        val rows=c.select("SELECT rm.id,t.kind,row_number() OVER(PARTITION BY t.id ORDER BY rm.number) n,h.catalog_key,ci.id city_id FROM rooms rm JOIN room_types t ON t.id=rm.room_type_id JOIN hotels h ON h.id=t.hotel_id JOIN cities ci ON ci.id=h.city_id ORDER BY h.catalog_key,rm.id") {
            arrayOf(it.getString("id"),it.getString("kind"),it.getInt("n").toString(),it.getString("catalog_key"),it.getString("city_id")) }
        val order=rows.groupBy { it[4] }.mapValues { it.value.map { row->row[3] }.distinct().sorted() }
        rows.forEach { r ->
            val index=order.getValue(r[4]).indexOf(r[3])
            if(index==1 || index==0 && r[1]=="STANDARD" || index==2 && r[1]=="STANDARD" && r[2].toInt()<=2)
                c.execute("INSERT INTO room_blocks(id,room_id,start_date,end_date,reason) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                    uid(SeedCatalog.uuid("block:"+r[0])),uid(r[0]),Date.valueOf(start),Date.valueOf(start.plusDays(3)),"Учебная блокировка (не бронь)")
        }
    }

    internal fun validate(c: Connection, q: StayRequest) {
        val country=uuidField(q.countryId,"countryId")
        val zones=c.select("SELECT ci.id,ci.timezone FROM cities ci JOIN countries co ON co.id=ci.country_id WHERE co.id=? AND co.active AND ci.active",country) { it.getString(1) to it.getString(2) }
        if(zones.isEmpty()) invalid(mapOf("countryId" to "Выберите доступную страну."))
        q.cityId?.let { uuidField(it,"cityId"); if(zones.none { v->v.first==it }) invalid(mapOf("cityId" to "Город не относится к стране.")) }
        val zone=zones.first { q.cityId==null || it.first==q.cityId }.second
        validateStay(q,LocalDate.now(clock.withZone(ZoneId.of(zone))))
    }

    /** Возвращает CTE и параметры; тот же запрос используется для повторной проверки в броне. */
    internal fun candidates(q: StayRequest, now: Instant, hotelId: String? = null): Pair<String,List<Any?>> {
        val args=mutableListOf<Any?>(Date.valueOf(q.checkIn),Date.valueOf(q.checkOut),Date.valueOf(q.checkOut),Date.valueOf(q.checkIn),java.sql.Timestamp.from(now),Date.valueOf(q.checkOut),Date.valueOf(q.checkIn),uuidField(q.countryId,"countryId"),(q.adults+q.children+q.rooms-1)/q.rooms,q.rooms)
        val filters=StringBuilder()
        fun add(sql:String,value:Any?) { filters.append(" AND "+sql);args.add(value) }
        q.cityId?.let { add("ci.id=?",uuidField(it,"cityId")) };hotelId?.let { add("h.id=?",uuidField(it,"hotelId")) }
        if(q.stars.isNotEmpty()) { filters.append(" AND h.stars IN ("+q.stars.joinToString(",") { "?" }+")");args.addAll(q.stars.sorted()) }
        add("h.rating>=?",q.minRating)
        q.maxPrice?.let { add("r.nightly_amount<=?",amount(it)) };q.maxDistanceKm?.let { add("h.distance_km<=?",it) }
        q.roomKind?.let { add("t.kind=?",it) }
        if(q.name.isNotBlank()) add("strpos(lower(h.name),lower(?))>0",q.name.trim())
        q.amenities.sorted().forEach { add("EXISTS(SELECT 1 FROM room_type_amenities ta JOIN amenities a ON a.id=ta.amenity_id WHERE ta.room_type_id=t.id AND a.active AND a.code=?)",it) }
        val sql="""WITH offers AS (
            SELECT h.id hotel_id,h.rating,h.distance_km,t.id type_id,t.kind,t.capacity,t.area,r.id rate_id,
              r.nightly_amount,r.currency,r.allow_demo,r.allow_at_hotel,r.free_cancel_hours,
              count(rm.id)::int available_count
            FROM hotels h JOIN cities ci ON ci.id=h.city_id JOIN countries co ON co.id=ci.country_id
              JOIN room_types t ON t.hotel_id=h.id JOIN rates r ON r.room_type_id=t.id
              JOIN rooms rm ON rm.room_type_id=t.id
            WHERE h.active AND ci.active AND co.active AND t.active AND rm.active AND r.active
              AND r.currency=co.currency AND (r.allow_demo OR r.allow_at_hotel)
              AND r.valid_from<=? AND r.valid_to>=?
              AND NOT EXISTS(SELECT 1 FROM room_blocks b WHERE b.room_id=rm.id AND b.active AND b.start_date<? AND b.end_date>?)
              AND NOT EXISTS(SELECT 1 FROM booking_rooms br JOIN bookings b ON b.id=br.booking_id
                WHERE br.room_id=rm.id AND b.status IN ('CONFIRMED','COMPLETED','PENDING_PAYMENT')
                AND (b.status<>'PENDING_PAYMENT' OR b.expires_at>?) AND b.check_in<? AND b.check_out>?)
              AND co.id=? AND t.capacity>=? $filters
            GROUP BY h.id,t.id,r.id HAVING count(rm.id)>=?
        )"""
        // HAVING последний placeholder: число комнат переносим в конец после дополнительных фильтров.
        val count=args.removeAt(9);args.add(count)
        return sql to args
    }
    internal fun offers(c: Connection,q: StayRequest,now:Instant,hotelId:String): List<AvailableOffer> {
        val (sql,args)=candidates(q,now,hotelId)
        return c.select(sql+" SELECT * FROM offers ORDER BY nightly_amount,type_id",*args.toTypedArray()) { r ->
            val price=r.minor("nightly_amount")
            AvailableOffer(r.getString("type_id"),r.getString("rate_id"),r.getString("kind"),r.getInt("capacity"),r.getInt("area"),price,
                Math.multiplyExact(Math.multiplyExact(price,java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(q.checkIn),LocalDate.parse(q.checkOut))),q.rooms.toLong()),r.getString("currency"),r.getInt("available_count"),r.getBoolean("allow_demo"),r.getBoolean("allow_at_hotel"),r.getInt("free_cancel_hours")) }
    }
    suspend fun search(q: StayRequest,offset:Int,limit:Int): SearchResponse = db.query { c ->
        c.transactionIsolation=Connection.TRANSACTION_REPEATABLE_READ
        validate(c,q);val now=clock.instant();val (sql,args)=candidates(q,now)
        val total=c.select(sql+" SELECT count(DISTINCT hotel_id) FROM offers",*args.toTypedArray()) { it.getInt(1) }.single()
        val order=when(q.sort) { "PRICE"->"price ASC";"RATING"->"rating DESC";"DISTANCE"->"distance_km ASC";else->"rating DESC,distance_km ASC" }
        val ids=c.select(sql+" SELECT hotel_id,min(nightly_amount) price,max(rating) rating,max(distance_km) distance_km FROM offers GROUP BY hotel_id ORDER BY $order,hotel_id LIMIT ? OFFSET ?",*(args+listOf(limit,offset)).toTypedArray()) { it.getString(1) }
        SearchResponse(ids.map { SearchHit(it,offers(c,q,now,it)) },total,offset,limit,now.epochSecond)
    }
    suspend fun availability(hotelId:String,q:StayRequest): Availability=db.query { c ->
        c.transactionIsolation=Connection.TRANSACTION_REPEATABLE_READ
        validate(c,q)
        if(c.select("SELECT 1 FROM hotels h JOIN cities ci ON ci.id=h.city_id WHERE h.id=? AND h.active AND ci.active AND ci.country_id=?",uuidField(hotelId,"hotelId"),uid(q.countryId)) { it.getInt(1) }.isEmpty()) missing()
        val services=c.select("SELECT hs.service_id,hs.amount,hs.charge_unit,s.name,s.description,s.code FROM hotel_services hs JOIN services s ON s.id=hs.service_id WHERE hs.hotel_id=? AND hs.active AND s.active ORDER BY hs.service_id",uid(hotelId)) {
            HotelService(it.getString("service_id"),it.getString("name"),it.minor("amount"),it.getString("charge_unit")=="PER_NIGHT_ROOM",it.getString("description"),it.getString("code")) }
        val now=clock.instant();Availability(hotelId,offers(c,q,now,hotelId),now.epochSecond,services)
    }
}

/** Сервис отделяет HTTP-пагинацию от SQL и обеспечивает одинаковые правила для обоих endpoints. */
class SearchService(private val repository: SearchRepository) {
    suspend fun search(q:StayRequest,offset:Int,limit:Int):SearchResponse {
        if(offset<0 || limit !in 1..100) invalid(mapOf("pagination" to "offset ≥ 0; limit 1–100."))
        return repository.search(q,offset,limit)
    }
    suspend fun availability(id:String,q:StayRequest)=repository.availability(id,q)
}
