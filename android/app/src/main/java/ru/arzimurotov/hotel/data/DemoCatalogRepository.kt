package ru.arzimurotov.hotel.data

import javax.inject.Inject
import javax.inject.Singleton
import ru.arzimurotov.hotel.domain.*

/**
 * Локальная реализация CatalogRepository для второго этапа, без сети и SQL. Создаёт
 * детерминированные 36 гостиниц: 6 городов × 6 объектов, по два на 3/4/5 звёзд. 30 мест = 6 городов
 * × 5 категорий. Страны/города реальные; названия объектов, адреса, рейтинги, цены и сведения об
 * услугах вымышлены. Стабильные id позволяют восстанавливать навигацию и избранное; каталог не
 * является SQL seed. В этапах 3–4 его заменит REST-источник с серверной проверкой данных.
 */
@Singleton
class DemoCatalogRepository @Inject constructor() : CatalogRepository {
    override suspend fun load(): Catalog = createCatalog()

    companion object {
        /** Чистая фабрика также используется unit/UI-тестами без DI и Android Activity. */
        fun createCatalog(): Catalog {
            val countries =
                listOf(
                    Country("uz", "Узбекистан", "UZS"),
                    Country("ru", "Россия", "RUB"),
                    Country("tr", "Турция", "TRY"),
                )
            val cities =
                listOf(
                    City(
                        "tashkent",
                        "uz",
                        "Ташкент",
                        "Современный город и восточное гостеприимство",
                    ),
                    City(
                        "samarkand",
                        "uz",
                        "Самарканд",
                        "Архитектура, история и неспешные прогулки",
                    ),
                    City("moscow", "ru", "Москва", "Большой город для новых впечатлений"),
                    City("petersburg", "ru", "Санкт-Петербург", "Набережные, музеи и уютные кафе"),
                    City("istanbul", "tr", "Стамбул", "На перекрёстке культур и морских маршрутов"),
                    City("antalya", "tr", "Анталья", "Море, солнце и отдых у бассейна"),
                )
            val hotelNames =
                listOf(
                    "Городской сад",
                    "Тихий квартал",
                    "Лазурный двор",
                    "Панорама",
                    "Гранд Оазис",
                    "Резиденция света",
                )
            val hotels =
                cities.flatMapIndexed { cityIndex, city ->
                    (0..5).map { index ->
                        val stars = 3 + index / 2
                        // Цены каждой страны заданы в своей валюте; курс обмена не моделируется.
                        val base =
                            when (city.countryId) {
                                "uz" -> 450_000L
                                "ru" -> 4_500L
                                else -> 2_000L
                            }
                        val price = base + base * index / 2 + base * (cityIndex % 2) / 5
                        val amenities = buildSet {
                            add(Amenity.WIFI)
                            add(Amenity.PARKING)
                            add(Amenity.TRANSFER)
                            if (index >= 1) add(Amenity.BREAKFAST)
                            if (stars >= 4) {
                                add(Amenity.POOL)
                                add(Amenity.FITNESS)
                            }
                            if (stars == 5) add(Amenity.SPA)
                        }
                        Hotel(
                            "${city.id}-${index + 1}",
                            city.id,
                            hotelNames[index],
                            stars,
                            8.0 + index * 0.25 + (cityIndex % 2) * 0.1,
                            0.6 + (5 - index) * 0.45,
                            "${city.name}, Учебная улица, ${index + 10}",
                            "Спокойное пространство для отдыха и знакомства с городом. Светлые номера, внимательное обслуживание и удобная отправная точка для прогулок. Это вымышленная гостиница учебного каталога; адрес и оценки приведены для демонстрации интерфейса.",
                            amenities,
                            listOf(
                                RoomOffer(RoomKind.STANDARD, 2, 22, price),
                                RoomOffer(RoomKind.SUPERIOR, 3, 30, price * 13 / 10),
                                RoomOffer(RoomKind.SUITE, 4, 45, price * 18 / 10),
                            ),
                            listOf(
                                HotelService(
                                    "breakfast",
                                    "Завтрак",
                                    base / 5,
                                    true,
                                    "За ночь на один номер. Выбор услуги пока только для расчёта.",
                                ),
                                HotelService(
                                    "transfer",
                                    "Встреча в аэропорту",
                                    base,
                                    false,
                                    "Одна поездка для группы. Аэропорт, рейс и время будут запрашиваться при оформлении брони.",
                                ),
                                HotelService(
                                    "late",
                                    "Поздний выезд",
                                    base / 2,
                                    false,
                                    "Разовая услуга. Время и возможность подтвердит сервер на этапе бронирования.",
                                ),
                            ),
                            if (city.id == "antalya" || stars == 5)
                                listOf(Photo.POOL, Photo.ROOM, Photo.EXTERIOR)
                            else listOf(Photo.EXTERIOR, Photo.ROOM, Photo.POOL),
                        )
                    }
                }
            val places =
                // Городские места не дублируются для каждой гостиницы и не продают билеты.
                cities.flatMap { city ->
                    PlaceCategory.entries.mapIndexed { i, category ->
                        val names =
                            listOf(
                                "Ресторан «Тёплый вечер»",
                                "Кафе «Утро»",
                                "Парк «Зелёный маршрут»",
                                "Музей городских историй",
                                "Пространство отдыха «Горизонт»",
                            )
                        Place(
                            "${city.id}-place-$i",
                            city.id,
                            names[i],
                            category,
                            "Учебный пример места в городе ${city.name}. Здесь можно изучить описание и подготовить маршрут. Название, адрес и сведения вымышлены. Билеты, заказы столиков и внешние услуги в этой версии отсутствуют.",
                            "${city.name}, Демонстрационный проспект, ${i + 1}",
                            if (category == PlaceCategory.LEISURE) Photo.POOL else Photo.EXTERIOR,
                        )
                    }
                }
            return Catalog(countries, cities, hotels, places)
        }
    }
}
