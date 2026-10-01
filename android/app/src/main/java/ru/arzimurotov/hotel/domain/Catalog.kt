package ru.arzimurotov.hotel.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** UI prototype data. No availability or booking can be inferred from this catalogue. */
data class Catalog(
    val countries: List<Country>,
    val cities: List<City>,
    val hotels: List<Hotel>,
    val places: List<Place>,
) {
    fun city(id: String) = cities.first { it.id == id }

    fun country(id: String) = countries.first { it.id == id }

    fun hotel(id: String) = hotels.find { it.id == id }
}

data class Country(val id: String, val name: String, val currency: String)

data class City(val id: String, val countryId: String, val name: String, val caption: String)

enum class Photo {
    EXTERIOR,
    ROOM,
    POOL,
}

enum class Amenity(val title: String) {
    WIFI("Wi-Fi"),
    BREAKFAST("Завтрак"),
    POOL("Бассейн"),
    SPA("Спа"),
    PARKING("Парковка"),
    FITNESS("Фитнес"),
    TRANSFER("Трансфер"),
}

enum class RoomKind(val title: String) {
    STANDARD("Стандарт"),
    SUPERIOR("Комфорт"),
    SUITE("Люкс"),
}

data class RoomOffer(val kind: RoomKind, val capacity: Int, val area: Int, val pricePerNight: Long)

data class HotelService(
    val id: String,
    val name: String,
    val price: Long,
    val perNight: Boolean,
    val description: String,
)

data class Hotel(
    val id: String,
    val cityId: String,
    val name: String,
    val stars: Int,
    val rating: Double,
    val distanceKm: Double,
    val address: String,
    val description: String,
    val amenities: Set<Amenity>,
    val rooms: List<RoomOffer>,
    val services: List<HotelService>,
    val photos: List<Photo>,
) {
    val startingPrice: Long
        get() = rooms.minOf { it.pricePerNight }
}

enum class PlaceCategory(val title: String) {
    RESTAURANT("Рестораны"),
    CAFE("Кафе"),
    PARK("Парки"),
    MUSEUM("Музеи"),
    LEISURE("Отдых"),
}

data class Place(
    val id: String,
    val cityId: String,
    val name: String,
    val category: PlaceCategory,
    val description: String,
    val address: String,
    val photo: Photo,
)

enum class SortOrder(val title: String) {
    RECOMMENDED("Рекомендуемые"),
    PRICE("Сначала дешевле"),
    RATING("Высокий рейтинг"),
    DISTANCE("Ближе к центру"),
}

data class SearchFilters(
    val stars: Set<Int> = emptySet(),
    val minRating: Double = 0.0,
    val maxPrice: Long? = null,
    val amenities: Set<Amenity> = emptySet(),
    val roomKind: RoomKind? = null,
) {
    val activeCount
        get() =
            listOf(
                    stars.isNotEmpty(),
                    minRating > 0,
                    maxPrice != null,
                    amenities.isNotEmpty(),
                    roomKind != null,
                )
                .count { it }
}

data class SearchQuery(
    val countryId: String = "uz",
    val cityId: String? = "tashkent",
    val checkIn: LocalDate = LocalDate.now().plusDays(7),
    val checkOut: LocalDate = LocalDate.now().plusDays(10),
    val adults: Int = 2,
    val children: Int = 0,
    val rooms: Int = 1,
    val filters: SearchFilters = SearchFilters(),
    val sort: SortOrder = SortOrder.RECOMMENDED,
    val name: String = "",
) {
    val nights: Long
        get() = ChronoUnit.DAYS.between(checkIn, checkOut)

    val guestsPerRoom: Int
        get() = (adults + children + rooms - 1) / rooms.coerceAtLeast(1)

    /** A single country is required, so prices never compare RUB, TRY and UZS numerically. */
    fun validationError(catalog: Catalog, today: LocalDate = LocalDate.now()): String? =
        when {
            catalog.countries.none { it.id == countryId } -> "Выберите страну."
            cityId != null &&
                catalog.cities.none { it.id == cityId && it.countryId == countryId } ->
                "Город не относится к выбранной стране."
            checkIn < today -> "Дата заезда не может быть в прошлом."
            nights !in 1..90 -> "Выберите проживание от 1 до 90 ночей."
            adults !in 1..8 || children !in 0..4 || rooms !in 1..4 ->
                "Проверьте количество гостей и номеров."
            rooms > adults -> "На каждый номер нужен хотя бы один взрослый."
            adults + children > rooms * 4 ->
                "В одном номере размещается до 4 гостей. Увеличьте число номеров."
            else -> null
        }
}

interface CatalogRepository {
    suspend fun load(): Catalog
}

/** Local filtering for design verification, NOT server-side inventory search. */
fun Catalog.search(query: SearchQuery): List<Hotel> {
    val cityIds =
        cities
            .filter {
                it.countryId == query.countryId && (query.cityId == null || it.id == query.cityId)
            }
            .map { it.id }
            .toSet()
    val f = query.filters
    val result =
        hotels.filter { hotel ->
            hotel.cityId in cityIds &&
                (f.stars.isEmpty() || hotel.stars in f.stars) &&
                hotel.rating >= f.minRating &&
                hotel.amenities.containsAll(f.amenities) &&
                hotel.name.contains(query.name.trim(), ignoreCase = true) &&
                hotel.rooms.any {
                    it.capacity >= query.guestsPerRoom &&
                        (f.roomKind == null || it.kind == f.roomKind) &&
                        (f.maxPrice == null || it.pricePerNight <= f.maxPrice)
                }
        }
    return when (query.sort) {
        SortOrder.RECOMMENDED ->
            result.sortedWith(compareByDescending<Hotel> { it.rating }.thenBy { it.id })
        SortOrder.PRICE ->
            result.sortedWith(compareBy<Hotel> { it.matchingPrice(query) }.thenBy { it.id })
        SortOrder.RATING ->
            result.sortedWith(compareByDescending<Hotel> { it.rating }.thenBy { it.id })
        SortOrder.DISTANCE -> result.sortedWith(compareBy<Hotel> { it.distanceKm }.thenBy { it.id })
    }
}

fun Hotel.matchingPrice(query: SearchQuery): Long =
    rooms
        .filter {
            it.capacity >= query.guestsPerRoom &&
                (query.filters.roomKind == null || it.kind == query.filters.roomKind) &&
                (query.filters.maxPrice == null || it.pricePerNight <= query.filters.maxPrice)
        }
        .minOfOrNull { it.pricePerNight } ?: startingPrice

/** Preliminary display estimate, never used to create a booking or charge money. */
fun previewTotal(room: RoomOffer, query: SearchQuery, services: List<HotelService>): Long =
    room.pricePerNight * query.nights * query.rooms +
        services.sumOf { it.price * if (it.perNight) query.nights * query.rooms else 1 }
