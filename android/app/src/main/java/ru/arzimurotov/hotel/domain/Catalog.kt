package ru.arzimurotov.hotel.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Снимок локального каталога второго этапа: страны, города, гостиницы и городские места. Связи
 * выражены строковыми идентификаторами, а не ссылками на Compose или SQL-сущности. Данные
 * предназначены для проверки интерфейса; наличие номеров и бронь не подтверждаются.
 */
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

/** Страна задаёт валюту каталога; автоматический обмен валют пока не реализован. */
data class Country(val id: String, val name: String, val currency: String)

/** Город связан со страной через countryId; caption используется в карточке направления. */
data class City(val id: String, val countryId: String, val name: String, val caption: String)

/** Ключи трёх встроенных изображений. UI преобразует их в drawable, сеть не требуется. */
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

/**
 * Предложение типа номера, не конкретный физический номер и не остаток доступных комнат. capacity —
 * максимум гостей, area — площадь в м², pricePerNight — цена за номер/ночь в целых единицах валюты
 * страны. Денежные расчёты прототипа используют Long, не Double.
 */
data class RoomOffer(val kind: RoomKind, val capacity: Int, val area: Int, val pricePerNight: Long)

/**
 * Дополнительная услуга предварительного расчёта. perNight=true означает начисление за каждую ночь
 * на каждый номер (завтрак). perNight=false — один раз на весь расчёт (трансфер/поздний выезд).
 * Выбор услуги не отправляет заказ внешней компании.
 */
data class HotelService(
    val id: String,
    val name: String,
    val price: Long,
    val perNight: Boolean,
    val description: String,
)

/**
 * Вымышленная гостиница. Звёздность stars и демонстрационный рейтинг rating независимы. distanceKm
 * — учебное расстояние до центра, а не вычисленный GPS-маршрут. Комнаты и услуги вложены в модель
 * для прототипа; SQL-модель появится отдельно.
 */
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

/** Городской информационный объект. Билеты, брони столиков и платежи здесь отсутствуют. */
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

/** Группы фильтров. Пустой набор означает отсутствие ограничения по этому признаку. */
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

/**
 * Единое неизменяемое состояние поисковой формы. Все изменения создают копию через copy.
 * cityId=null выбирает все города одной страны. Диапазон дат — [checkIn, checkOut): день выезда не
 * считается отдельной ночью. Даты не проверяют серверную занятость.
 */
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
        // Округление вверх: тип номера должен вмещать максимальную долю гостей в группе.
        get() = (adults + children + rooms - 1) / rooms.coerceAtLeast(1)

    /** Одна страна обязательна: цены в RUB, TRY и UZS не сравниваются без конвертации. */
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

/**
 * Контракт источника каталога для ViewModel. На этапе 2 реализован локальными данными. suspend
 * позволяет впоследствии добавить REST без привязки экранов к HTTP-клиенту.
 */
interface CatalogRepository {
    suspend fun load(): Catalog
}

/**
 * Выбирает гостиницы только из городов нужной страны, затем применяет все ограничения. Гостиница
 * подходит, если существует хотя бы один тип номера нужной вместимости, категории и бюджета. Для
 * равных значений сортировки id даёт стабильный порядок. Это локальная фильтрация для проверки UX,
 * а не запрос свободных номеров по датам.
 */
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

/**
 * Цена подходящего типа номера для карточки/сортировки. Нельзя показывать дешёвый двухместный номер
 * как подходящее предложение для трёх гостей. startingPrice — резервное отображение для общей
 * карточки; функция сама не подтверждает доступность.
 */
fun Hotel.matchingPrice(query: SearchQuery): Long =
    rooms
        .filter {
            it.capacity >= query.guestsPerRoom &&
                (query.filters.roomKind == null || it.kind == query.filters.roomKind) &&
                (query.filters.maxPrice == null || it.pricePerNight <= query.filters.maxPrice)
        }
        .minOfOrNull { it.pricePerNight } ?: startingPrice

/**
 * Предварительная сумма = цена номера × ночи × номера + выбранные услуги. Посуточная услуга
 * умножается на ночи и номера; разовая начисляется один раз. Вызов предполагает валидный
 * SearchQuery. Налогов, конвертации и реального списания нет; окончательную сумму и доступность на
 * следующих этапах будет определять сервер.
 */
fun previewTotal(room: RoomOffer, query: SearchQuery, services: List<HotelService>): Long =
    room.pricePerNight * query.nights * query.rooms +
        services.sumOf { it.price * if (it.perNight) query.nights * query.rooms else 1 }
