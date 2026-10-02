package ru.arzimurotov.hotel.server

import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import ru.arzimurotov.hotel.domain.*

/** PreparedStatement исключает подстановку пользовательских значений в SQL.
 * Соединение принадлежит Exposed-транзакции; каждый Statement/ResultSet закрывается use. */
internal fun Connection.execute(sql: String, vararg values: Any?) =
    prepareStatement(sql).use { s -> values.forEachIndexed { i, v -> s.setObject(i + 1, v) }; s.executeUpdate() }
internal fun <T> Connection.select(sql: String, vararg values: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { s ->
        values.forEachIndexed { i, v -> s.setObject(i + 1, v) }
        s.executeQuery().use { r -> buildList { while(r.next()) add(map(r)) } }
    }
internal fun uid(id: String) = UUID.fromString(id)
internal fun amount(minor: Long) = BigDecimal.valueOf(minor, 2)
internal fun ResultSet.minor(column: String): Long = getBigDecimal(column).movePointRight(2).longValueExact()

/** Идемпотентное первоначальное заполнение. ON CONFLICT DO NOTHING никогда не затирает
 * редактирование администратора. Все 36 гостиниц и 432 номера создаются одной транзакцией. */
class CatalogRepository(private val db: DatabaseService) {
    suspend fun seed() = db.query { c ->
        val seed = SeedCatalog.sqlCatalog()
        seed.countries.forEach { c.execute("INSERT INTO countries(id,catalog_key,name,currency) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",uid(it.id),it.legacyId,it.name,it.currency) }
        seed.cities.forEach { c.execute("INSERT INTO cities(id,catalog_key,country_id,name,caption,timezone) VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",uid(it.id),it.legacyId,uid(it.countryId),it.name,it.caption,
            when(seed.countries.first {co->co.id==it.countryId}.legacyId) {"uz"->"Asia/Tashkent";"ru"->"Europe/Moscow";else->"Europe/Istanbul"}) }
        Amenity.entries.forEach { c.execute("INSERT INTO amenities(id,code,name) VALUES(?,?,?) ON CONFLICT DO NOTHING",uid(SeedCatalog.uuid("amenity:"+it.name)),it.name,it.title) }
        seed.hotels.forEach { h ->
            c.execute("INSERT INTO hotels(id,catalog_key,city_id,name,stars,rating,distance_km,address,description) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",uid(h.id),h.legacyId,uid(h.cityId),h.name,h.stars,BigDecimal.valueOf(h.rating),BigDecimal.valueOf(h.distanceKm),h.address,h.description)
            h.photos.forEachIndexed { i, photo -> c.execute("INSERT INTO hotel_photos(id,hotel_id,photo_key,position) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",uid(SeedCatalog.uuid(h.id+":photo:"+i)),uid(h.id),photo.name,i) }
            h.rooms.forEach { room ->
                val type = uid(SeedCatalog.uuid(h.id+":"+room.kind.name))
                c.execute("INSERT INTO room_types(id,hotel_id,kind,capacity,area) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",type,uid(h.id),room.kind.name,room.capacity,room.area)
                c.execute("INSERT INTO room_type_photos(id,room_type_id,photo_key,position) VALUES(?,?,'ROOM',0) ON CONFLICT DO NOTHING",uid(SeedCatalog.uuid(type.toString()+":photo")),type)
                (1..4).forEach { n -> c.execute("INSERT INTO rooms(id,room_type_id,number) VALUES(?,?,?) ON CONFLICT DO NOTHING",uid(SeedCatalog.uuid(type.toString()+":room:"+n)),type,"${room.kind.ordinal+1}0$n") }
                c.execute("INSERT INTO rates(id,room_type_id,nightly_amount,currency,valid_from,valid_to) VALUES(?,?,?,?,DATE '2020-01-01',DATE '2100-01-01') ON CONFLICT DO NOTHING",uid(SeedCatalog.uuid(type.toString()+":rate")),type,amount(room.pricePerNight),seed.countries.first { it.id == seed.cities.first { city -> city.id == h.cityId }.countryId }.currency)
                h.amenities.forEach { a -> c.execute("INSERT INTO room_type_amenities(room_type_id,amenity_id) VALUES(?,?) ON CONFLICT DO NOTHING",type,uid(SeedCatalog.uuid("amenity:"+a.name))) }
            }
            h.services.forEach { s ->
                c.execute("INSERT INTO services(id,code,name,description) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",uid(s.id),SeedCatalog.createCatalog().hotels.first().services.first { SeedCatalog.uuid(it.id)==s.id }.id,s.name,s.description)
                c.execute("INSERT INTO hotel_services(hotel_id,service_id,amount,charge_unit) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",uid(h.id),uid(s.id),amount(s.price),if(s.perNight) "PER_NIGHT_ROOM" else "ONCE")
            }
        }
        seed.places.forEach { v ->
            c.execute("INSERT INTO places(id,catalog_key,city_id,name,category,description,address,photo_key) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",uid(v.id),v.legacyId,uid(v.cityId),v.name,v.category.name,v.description,v.address,v.photo.name)
            seed.hotels.filter { it.cityId==v.cityId }.forEach { h -> c.execute("INSERT INTO hotel_places(hotel_id,place_id) VALUES(?,?) ON CONFLICT DO NOTHING",uid(h.id),uid(v.id)) }
        }
    }

    /** DTO собирается только из SQL, не из seed. Вложенные данные запрашиваются пакетно,
     * поэтому число запросов не растёт с числом гостиниц (нет N+1). */
    suspend fun load(): Catalog = db.query { c ->
        val countries = c.select("SELECT * FROM countries WHERE active ORDER BY catalog_key") { Country(it.getString("id"),it.getString("name"),it.getString("currency"),it.getString("catalog_key")) }
        val cities = c.select("SELECT ci.* FROM cities ci JOIN countries co ON co.id=ci.country_id WHERE ci.active AND co.active ORDER BY ci.catalog_key") { City(it.getString("id"),it.getString("country_id"),it.getString("name"),it.getString("caption"),it.getString("catalog_key")) }
        val offers = c.select("SELECT t.*,r.nightly_amount FROM room_types t JOIN rates r ON r.room_type_id=t.id WHERE t.active AND r.active AND CURRENT_DATE >= r.valid_from AND CURRENT_DATE < r.valid_to ORDER BY t.kind") { it.getString("hotel_id") to RoomOffer(RoomKind.valueOf(it.getString("kind")),it.getInt("capacity"),it.getInt("area"),it.minor("nightly_amount")) }.groupBy({it.first},{it.second})
        val amenities = c.select("SELECT DISTINCT t.hotel_id,a.code FROM room_type_amenities ta JOIN room_types t ON t.id=ta.room_type_id JOIN amenities a ON a.id=ta.amenity_id WHERE t.active AND a.active") { it.getString("hotel_id") to Amenity.valueOf(it.getString("code")) }.groupBy({it.first},{it.second})
        val photos = c.select("SELECT * FROM hotel_photos WHERE active ORDER BY position") { it.getString("hotel_id") to Photo.valueOf(it.getString("photo_key")) }.groupBy({it.first},{it.second})
        val services = c.select("SELECT hs.*,s.name,s.description,s.code FROM hotel_services hs JOIN services s ON s.id=hs.service_id WHERE hs.active AND s.active ORDER BY s.code") { it.getString("hotel_id") to HotelService(it.getString("service_id"),it.getString("name"),it.minor("amount"),it.getString("charge_unit")=="PER_NIGHT_ROOM",it.getString("description"),it.getString("code")) }.groupBy({it.first},{it.second})
        val hotels = c.select("SELECT * FROM hotels WHERE active ORDER BY catalog_key") {
            val id=it.getString("id")
            Hotel(id,it.getString("city_id"),it.getString("name"),it.getInt("stars"),it.getDouble("rating"),it.getDouble("distance_km"),it.getString("address"),it.getString("description"),amenities[id].orEmpty().toSet(),offers[id].orEmpty(),services[id].orEmpty(),photos[id].orEmpty(),it.getString("catalog_key"))
        }.filter { it.rooms.isNotEmpty() && hCityExists(cities,it.cityId) }
        val places = c.select("SELECT * FROM places WHERE active ORDER BY catalog_key") { Place(it.getString("id"),it.getString("city_id"),it.getString("name"),PlaceCategory.valueOf(it.getString("category")),it.getString("description"),it.getString("address"),Photo.valueOf(it.getString("photo_key")),it.getString("catalog_key")) }.filter { hCityExists(cities,it.cityId) }
        Catalog(countries,cities,hotels,places)
    }
    private fun hCityExists(cities: List<City>, id: String) = cities.any { it.id==id }
    suspend fun hotelPlaces(id: String): List<Place> {
        val ids = db.query { c -> c.select("SELECT place_id FROM hotel_places WHERE hotel_id=?",uid(id)) { it.getString(1) }.toSet() }
        return load().places.filter { it.id in ids }
    }
    suspend fun rooms(id: String): List<PhysicalRoom> = db.query { c ->
        c.select("SELECT rm.id,rm.room_type_id,rm.number,t.kind,t.capacity,t.area,r.nightly_amount,r.currency FROM rooms rm JOIN room_types t ON t.id=rm.room_type_id JOIN rates r ON r.room_type_id=t.id JOIN hotels h ON h.id=t.hotel_id JOIN cities ci ON ci.id=h.city_id JOIN countries co ON co.id=ci.country_id WHERE t.hotel_id=? AND rm.active AND t.active AND r.active AND h.active AND ci.active AND co.active AND CURRENT_DATE >= r.valid_from AND CURRENT_DATE < r.valid_to ORDER BY rm.number",uid(id)) {
            PhysicalRoom(it.getString("id"),it.getString("room_type_id"),it.getString("number"),RoomOffer(RoomKind.valueOf(it.getString("kind")),it.getInt("capacity"),it.getInt("area"),it.minor("nightly_amount")),it.getString("currency"))
        }
    }
    suspend fun summary(): AdminSummary = db.query { c ->
        fun count(table: String) = c.select("SELECT count(*) FROM "+table) { it.getInt(1) }.single()
        AdminSummary(count("countries"),count("cities"),count("hotels"),count("rooms"),count("places"),count("users"))
    }
}
