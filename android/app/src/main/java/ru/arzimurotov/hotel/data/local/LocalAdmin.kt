package ru.arzimurotov.hotel.data.local

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import ru.arzimurotov.hotel.domain.*

data class AdminItem(
    val id: String,
    val title: String,
    val fields: Map<String, String>,
    val active: Boolean,
    val version: Int,
)

data class AdminField(
    val key: String,
    val label: String,
    val default: String = "",
    val reference: String? = null,
    val choices: List<String> = emptyList(),
)

data class AdminKind(val key: String, val title: String, val fields: List<AdminField>)

/**
 * Метаданные форм едины для UI и валидации repository. UUID не набирается вручную: поля reference
 * показывают названия родительских объектов. Суммы — минимальные единицы.
 */
object AdminSchema {
    val kinds =
        listOf(
            AdminKind(
                "country",
                "Страны",
                listOf(
                    AdminField("name", "Название"),
                    AdminField("currency", "Валюта", "RUB", choices = listOf("RUB", "UZS", "TRY")),
                ),
            ),
            AdminKind(
                "city",
                "Города",
                listOf(
                    AdminField("name", "Название"),
                    AdminField("countryId", "Страна", reference = "country"),
                    AdminField("caption", "Описание"),
                    AdminField("latitude", "Широта", "0"),
                    AdminField("longitude", "Долгота", "0"),
                    AdminField("timezone", "Часовой пояс IANA", "Europe/Moscow"),
                ),
            ),
            AdminKind(
                "hotel",
                "Гостиницы",
                listOf(
                    AdminField("name", "Название"),
                    AdminField("cityId", "Город", reference = "city"),
                    AdminField("stars", "Звёзды", "3", choices = listOf("3", "4", "5")),
                    AdminField("rating", "Демо-рейтинг", "8.0"),
                    AdminField("address", "Адрес"),
                    AdminField("description", "Описание"),
                    AdminField("latitude", "Широта", "0"),
                    AdminField("longitude", "Долгота", "0"),
                ),
            ),
            AdminKind(
                "type",
                "Типы номеров",
                listOf(
                    AdminField("hotelId", "Гостиница", reference = "hotel"),
                    AdminField(
                        "kind",
                        "Категория",
                        "STANDARD",
                        choices = RoomKind.entries.map { it.name },
                    ),
                    AdminField("capacity", "Гостей максимум", "2"),
                    AdminField("area", "Площадь, м²", "22"),
                    AdminField("amenities", "Коды удобств через запятую", "WIFI,PARKING"),
                ),
            ),
            AdminKind(
                "room",
                "Физические номера",
                listOf(
                    AdminField("typeId", "Тип номера", reference = "type"),
                    AdminField("number", "Номер комнаты"),
                ),
            ),
            AdminKind(
                "rate",
                "Тарифы",
                listOf(
                    AdminField("typeId", "Тип номера", reference = "type"),
                    AdminField("amount", "Цена / ночь в копейках, тийинах, курушах", "450000"),
                    AdminField("currency", "Валюта", "RUB", choices = listOf("RUB", "UZS", "TRY")),
                    AdminField("start", "Начало ГГГГ-ММ-ДД", LocalDate.now().toString()),
                    AdminField("end", "Конец, не включая дату", "2100-01-01"),
                    AdminField("demo", "Демооплата", "true", choices = listOf("true", "false")),
                    AdminField(
                        "atHotel",
                        "Оплата в гостинице",
                        "true",
                        choices = listOf("true", "false"),
                    ),
                    AdminField("freeCancelHours", "Бесплатная отмена за часов", "24"),
                ),
            ),
            AdminKind(
                "service",
                "Услуги",
                listOf(
                    AdminField("hotelId", "Гостиница", reference = "hotel"),
                    AdminField("code", "Стабильный код (transfer для трансфера)"),
                    AdminField("name", "Название"),
                    AdminField("description", "Описание"),
                    AdminField("amount", "Цена в минимальных единицах", "0"),
                    AdminField(
                        "perNight",
                        "За ночь / комнату",
                        "false",
                        choices = listOf("false", "true"),
                    ),
                ),
            ),
            AdminKind(
                "amenity",
                "Удобства",
                listOf(AdminField("code", "Код удобства"), AdminField("name", "Название")),
            ),
            AdminKind(
                "place",
                "Городские места",
                listOf(
                    AdminField("name", "Название"),
                    AdminField("cityId", "Город", reference = "city"),
                    AdminField(
                        "category",
                        "Категория",
                        "RESTAURANT",
                        choices = PlaceCategory.entries.map { it.name },
                    ),
                    AdminField("address", "Адрес"),
                    AdminField("description", "Описание"),
                    AdminField("latitude", "Широта", "0"),
                    AdminField("longitude", "Долгота", "0"),
                ),
            ),
            AdminKind(
                "link",
                "Рекомендации гостиницы",
                listOf(
                    AdminField("hotelId", "Гостиница", reference = "hotel"),
                    AdminField("placeId", "Место", reference = "place"),
                ),
            ),
            AdminKind(
                "block",
                "Закрытые периоды",
                listOf(
                    AdminField("roomId", "Комната", reference = "room"),
                    AdminField(
                        "start",
                        "Начало ГГГГ-ММ-ДД",
                        LocalDate.now().plusDays(7).toString(),
                    ),
                    AdminField(
                        "end",
                        "Конец, не включая дату",
                        LocalDate.now().plusDays(8).toString(),
                    ),
                    AdminField("reason", "Причина"),
                ),
            ),
        )
}

/**
 * ADMIN проверяется перед каждой операцией, включая чтение закрытых списков. Общая Room transaction
 * упорядочивает изменения с бронью. DELETE истории отсутствует.
 */
class LocalAdmin(private val e: LocalEngine) {
    private val d = e.dao

    private suspend fun version(id: String) = d.entry("version:$id")?.version ?: 1

    suspend fun list(kind: String): List<AdminItem> = e.transaction {
        e.admin()
        when (kind) {
            "country" ->
                d.entries()
                    .filter { it.kind == kind }
                    .map { v ->
                        val x = e.json.decodeFromString<Country>(v.payload)
                        AdminItem(
                            v.id,
                            v.title,
                            mapOf("name" to x.name, "currency" to x.currency),
                            v.active,
                            v.version,
                        )
                    }
            "city" ->
                d.entries()
                    .filter { it.kind == kind }
                    .map { v ->
                        val x = e.json.decodeFromString<City>(v.payload)
                        AdminItem(
                            v.id,
                            v.title,
                            mapOf(
                                "name" to x.name,
                                "countryId" to x.countryId,
                                "caption" to x.caption,
                                "latitude" to (x.latitude ?: 0).toString(),
                                "longitude" to (x.longitude ?: 0).toString(),
                                "timezone" to x.timezone,
                            ),
                            v.active,
                            v.version,
                        )
                    }
            "hotel" ->
                d.entries()
                    .filter { it.kind == kind }
                    .map { v ->
                        val x = e.json.decodeFromString<Hotel>(v.payload)
                        AdminItem(
                            v.id,
                            v.title,
                            mapOf(
                                "name" to x.name,
                                "cityId" to x.cityId,
                                "stars" to x.stars.toString(),
                                "rating" to x.rating.toString(),
                                "address" to x.address,
                                "description" to x.description,
                                "latitude" to (x.latitude ?: 0).toString(),
                                "longitude" to (x.longitude ?: 0).toString(),
                                "photoFiles" to x.photoFiles.joinToString(","),
                            ),
                            v.active,
                            v.version,
                        )
                    }
            "place" ->
                d.entries()
                    .filter { it.kind == kind }
                    .map { v ->
                        val x = e.json.decodeFromString<Place>(v.payload)
                        AdminItem(
                            v.id,
                            v.title,
                            mapOf(
                                "name" to x.name,
                                "cityId" to x.cityId,
                                "category" to x.category.name,
                                "address" to x.address,
                                "description" to x.description,
                                "latitude" to (x.latitude ?: 0).toString(),
                                "longitude" to (x.longitude ?: 0).toString(),
                                "photoFiles" to x.photoFile.orEmpty(),
                            ),
                            v.active,
                            v.version,
                        )
                    }
            "type" ->
                d.types().map {
                    AdminItem(
                        it.id,
                        it.hotelId + " · " + it.kind,
                        mapOf(
                            "hotelId" to it.hotelId,
                            "kind" to it.kind,
                            "capacity" to it.capacity.toString(),
                            "area" to it.area.toString(),
                            "amenities" to
                                e.json.decodeFromString<Set<String>>(it.amenities).joinToString(),
                        ),
                        it.active,
                        version(it.id),
                    )
                }
            "room" ->
                d.rooms().map {
                    AdminItem(
                        it.id,
                        "Комната " + it.number,
                        mapOf("typeId" to it.typeId, "number" to it.number),
                        it.active,
                        version(it.id),
                    )
                }
            "rate" ->
                d.rates().map {
                    AdminItem(
                        it.id,
                        "${it.amount} ${it.currency} · ${it.start}",
                        mapOf(
                            "typeId" to it.typeId,
                            "amount" to it.amount.toString(),
                            "currency" to it.currency,
                            "start" to it.start,
                            "end" to it.end,
                            "demo" to it.demo.toString(),
                            "atHotel" to it.atHotel.toString(),
                            "freeCancelHours" to it.freeCancelHours.toString(),
                        ),
                        it.active,
                        version(it.id),
                    )
                }
            "service" ->
                d.services().map {
                    AdminItem(
                        it.id,
                        it.name,
                        mapOf(
                            "hotelId" to it.hotelId,
                            "code" to it.code,
                            "name" to it.name,
                            "description" to it.description,
                            "amount" to it.amount.toString(),
                            "perNight" to it.perNight.toString(),
                        ),
                        it.active,
                        version(it.id),
                    )
                }
            "block" ->
                d.blocks().map {
                    AdminItem(
                        it.id,
                        it.reason + " · " + it.start,
                        mapOf(
                            "roomId" to it.roomId,
                            "start" to it.start,
                            "end" to it.end,
                            "reason" to it.reason,
                        ),
                        it.active,
                        version(it.id),
                    )
                }
            "link" ->
                d.links().map {
                    AdminItem(
                        it.hotelId + "|" + it.placeId,
                        "Рекомендация",
                        mapOf("hotelId" to it.hotelId, "placeId" to it.placeId),
                        true,
                        1,
                    )
                }
            "amenity" ->
                d.entries()
                    .filter { it.kind == kind }
                    .map {
                        AdminItem(
                            it.id,
                            it.title,
                            e.json.decodeFromString<Map<String, String>>(it.payload),
                            it.active,
                            it.version,
                        )
                    }
            else -> emptyList()
        }
    }

    suspend fun save(
        kind: String,
        id: String?,
        values: Map<String, String>,
        active: Boolean,
        expectedVersion: Int,
    ): String = e.transaction {
        val actor = e.admin()
        val key = id ?: e.uid()
        val old = id?.let { list(kind).find { x -> x.id == id } }
        val v = (old?.version ?: 0) + 1
        if (id != null && (old == null || old.version != expectedVersion))
            e.fail("EDIT_CONFLICT", "Запись изменена. Обновите список.")
        val schema = AdminSchema.kinds.first { it.key == kind }
        fun s(k: String) = values[k]?.trim().orEmpty()
        fun n(k: String) =
            s(k).toLongOrNull() ?: e.fail("VALIDATION", "Поле $k: требуется целое число.")
        fun f(k: String) =
            s(k).toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: e.fail("VALIDATION", "Поле $k: требуется число.")
        schema.fields.forEach { field ->
            e.checkValue(s(field.key).length <= 4000, "Текст слишком длинный.")
            if (field.reference != null)
                e.checkValue(
                    list(field.reference).any { it.id == s(field.key) && it.active },
                    "Выберите активный объект: ${field.label}.",
                )
            if (field.choices.isNotEmpty())
                e.checkValue(s(field.key) in field.choices, "Недопустимое значение ${field.label}.")
        }
        fun text(k: String, min: Int = 1) =
            s(k).also { e.checkValue(it.length in min..4000, "Заполните поле $k.") }
        fun coords() {
            e.checkValue(
                f("latitude") in -90.0..90.0 && f("longitude") in -180.0..180.0,
                "Координаты вне диапазона.",
            )
        }
        fun dates() {
            e.checkValue(
                runCatching {
                        LocalDate.parse(s("start"))
                        LocalDate.parse(s("end"))
                        s("start") < s("end")
                    }
                    .getOrDefault(false),
                "Дата окончания должна быть позже начала.",
            )
        }
        fun money() {
            e.checkValue(n("amount") in 0..999999999999L, "Цена вне диапазона.")
        }
        when (kind) {
            "country" -> {
                e.checkValue(
                    active ||
                        d.entries().count { it.kind == "country" && it.active && it.id != key } > 0,
                    "Оставьте хотя бы одну активную страну для поиска.",
                )
                val currency = s("currency")
                if (old != null && old.fields["currency"] != currency)
                    e.checkValue(
                        d.entries().none {
                            it.kind == "city" &&
                                e.json.decodeFromString<City>(it.payload).countryId == key
                        },
                        "Нельзя менять валюту страны с городами и ценами.",
                    )
                val x = Country(key, text("name"), currency)
                d.put(CatalogEntry(key, kind, x.name, e.json.encodeToString(x), active, v))
            }
            "city" -> {
                coords()
                e.checkValue(
                    runCatching { ZoneId.of(s("timezone")) }.isSuccess,
                    "Неизвестный часовой пояс.",
                )
                if (old != null && old.fields["countryId"] != s("countryId"))
                    e.checkValue(
                        d.entries().none {
                            it.kind == "hotel" &&
                                e.json.decodeFromString<Hotel>(it.payload).cityId == key
                        },
                        "Нельзя переносить город с гостиницами в другую страну.",
                    )
                val x =
                    City(
                        key,
                        s("countryId"),
                        text("name"),
                        s("caption"),
                        latitude = f("latitude"),
                        longitude = f("longitude"),
                        timezone = s("timezone"),
                    )
                d.put(CatalogEntry(key, kind, x.name, e.json.encodeToString(x), active, v))
            }
            "hotel" -> {
                coords()
                e.checkValue(f("rating") in 0.0..10.0, "Рейтинг от 0 до 10.")
                if (old != null && old.fields["cityId"] != s("cityId"))
                    e.checkValue(
                        d.types().none { it.hotelId == key },
                        "Нельзя переносить гостиницу с номерами. Создайте новую.",
                    )
                val prior = d.entry(key)?.let { e.json.decodeFromString<Hotel>(it.payload) }
                val city =
                    e.json.decodeFromString<City>(requireNotNull(d.entry(s("cityId"))).payload)
                val distance =
                    Geo.distanceKm(
                        city.latitude ?: 0.0,
                        city.longitude ?: 0.0,
                        f("latitude"),
                        f("longitude"),
                    )
                val x =
                    Hotel(
                        key,
                        city.id,
                        text("name"),
                        n("stars").toInt(),
                        f("rating"),
                        distance,
                        text("address"),
                        text("description"),
                        prior?.amenities ?: emptySet(),
                        emptyList(),
                        emptyList(),
                        prior?.photos ?: listOf(Photo.EXTERIOR),
                        latitude = f("latitude"),
                        longitude = f("longitude"),
                        photoFiles = prior?.photoFiles.orEmpty(),
                    )
                d.put(CatalogEntry(key, kind, x.name, e.json.encodeToString(x), active, v))
            }
            "place" -> {
                coords()
                val oldPlace = d.entry(key)?.let { e.json.decodeFromString<Place>(it.payload) }
                if (old != null && old.fields["cityId"] != s("cityId"))
                    e.checkValue(
                        d.links().none { it.placeId == key },
                        "Сначала удалите связи места с гостиницами.",
                    )
                val x =
                    Place(
                        key,
                        s("cityId"),
                        text("name"),
                        PlaceCategory.valueOf(s("category")),
                        text("description"),
                        text("address"),
                        Photo.EXTERIOR,
                        latitude = f("latitude"),
                        longitude = f("longitude"),
                        photoFile = oldPlace?.photoFile,
                    )
                d.put(CatalogEntry(key, kind, x.name, e.json.encodeToString(x), active, v))
            }
            "amenity" -> {
                val code = text("code")
                if (old != null && (old.fields["code"] != code || !active))
                    e.checkValue(
                        d.types().none {
                            old.fields["code"] in e.json.decodeFromString<Set<String>>(it.amenities)
                        },
                        "Удобство используется типом номера. Сначала уберите его из типов.",
                    )
                e.checkValue(
                    Regex("[A-Z][A-Z0-9_]{0,29}").matches(code),
                    "Код: A-Z, цифры и подчёркивание.",
                )
                e.checkValue(
                    d.entries().none {
                        it.kind == kind &&
                            it.id != key &&
                            e.json.decodeFromString<Map<String, String>>(it.payload)["code"] == code
                    },
                    "Код уже существует.",
                )
                d.put(
                    CatalogEntry(key, kind, text("name"), e.json.encodeToString(values), active, v)
                )
            }
            "type" -> {
                e.checkValue(
                    n("capacity") in 1..8 && n("area") in 1..1000,
                    "Проверьте вместимость и площадь.",
                )
                if (old != null)
                    e.checkValue(
                        old.fields["hotelId"] == s("hotelId") && old.fields["kind"] == s("kind"),
                        "Гостиница и категория существующего типа неизменяемы.",
                    )
                val codes =
                    s("amenities").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                val allowed =
                    Amenity.entries.map { it.name }.toSet() +
                        d.entries()
                            .filter { it.kind == "amenity" && it.active }
                            .map {
                                e.json
                                    .decodeFromString<Map<String, String>>(it.payload)
                                    .getValue("code")
                            }
                e.checkValue(allowed.containsAll(codes), "Неизвестный код удобства.")
                e.checkValue(
                    d.types().none {
                        it.id != key && it.hotelId == s("hotelId") && it.kind == s("kind")
                    },
                    "Такой тип номера уже есть.",
                )
                d.put(
                    LocalType(
                        key,
                        s("hotelId"),
                        s("kind"),
                        n("capacity").toInt(),
                        n("area").toInt(),
                        e.json.encodeToString(codes),
                        active,
                    )
                )
            }
            "room" -> {
                text("number")
                if (old != null)
                    e.checkValue(
                        old.fields["typeId"] == s("typeId"),
                        "Нельзя переносить физическую комнату в другой тип.",
                    )
                e.checkValue(
                    d.rooms().none {
                        it.id != key &&
                            d.types().first { t -> t.id == it.typeId }.hotelId ==
                                d.types().first { t -> t.id == s("typeId") }.hotelId &&
                            it.number == s("number")
                    },
                    "Номер комнаты уже есть.",
                )
                d.put(LocalRoom(key, s("typeId"), s("number"), active))
            }
            "rate" -> {
                dates()
                money()
                e.checkValue(n("freeCancelHours") in 0..720, "Отмена: от 0 до 720 часов.")
                e.checkValue(
                    s("demo") == "true" || s("atHotel") == "true",
                    "Нужен хотя бы один способ оплаты.",
                )
                val type = d.types().first { it.id == s("typeId") }
                val h =
                    e.json.decodeFromString<Hotel>(requireNotNull(d.entry(type.hotelId)).payload)
                val city = e.json.decodeFromString<City>(requireNotNull(d.entry(h.cityId)).payload)
                val country =
                    e.json.decodeFromString<Country>(
                        requireNotNull(d.entry(city.countryId)).payload
                    )
                e.checkValue(
                    country.currency == s("currency"),
                    "Валюта тарифа должна совпадать со страной.",
                )
                e.checkValue(
                    !active ||
                        d.rates().none {
                            it.active &&
                                it.id != key &&
                                it.typeId == s("typeId") &&
                                it.start < s("end") &&
                                it.end > s("start")
                        },
                    "Активные тарифы пересекаются.",
                )
                d.put(
                    LocalRate(
                        key,
                        type.id,
                        n("amount"),
                        s("currency"),
                        s("start"),
                        s("end"),
                        s("demo") == "true",
                        s("atHotel") == "true",
                        n("freeCancelHours").toInt(),
                        active,
                    )
                )
            }
            "service" -> {
                money()
                text("name")
                e.checkValue(
                    Regex("[a-z][a-z0-9_]{0,29}").matches(s("code")),
                    "Код услуги: строчные буквы, цифры, подчёркивание.",
                )
                if (old != null)
                    e.checkValue(
                        old.fields["code"] == s("code") && old.fields["hotelId"] == s("hotelId"),
                        "Код и гостиница услуги неизменяемы.",
                    )
                e.checkValue(
                    d.services().none {
                        it.id != key && it.hotelId == s("hotelId") && it.code == s("code")
                    },
                    "Код услуги уже существует.",
                )
                d.put(
                    LocalService(
                        key,
                        s("hotelId"),
                        s("code"),
                        s("name"),
                        s("description"),
                        n("amount"),
                        s("perNight") == "true",
                        active,
                    )
                )
            }
            "block" -> {
                dates()
                text("reason")
                if (active)
                    e.checkValue(
                        e.freeRooms(
                                d.rooms().first { it.id == s("roomId") }.typeId,
                                s("start"),
                                s("end"),
                                key,
                            )
                            .any { it.id == s("roomId") } || old != null && old.fields == values,
                        "Комната занята или уже закрыта на эти даты.",
                    )
                d.put(LocalBlock(key, s("roomId"), s("start"), s("end"), s("reason"), active))
            }
            "link" -> {
                val h =
                    e.json.decodeFromString<Hotel>(requireNotNull(d.entry(s("hotelId"))).payload)
                val p =
                    e.json.decodeFromString<Place>(requireNotNull(d.entry(s("placeId"))).payload)
                e.checkValue(h.cityId == p.cityId, "Место должно быть в том же городе.")
                if (old != null) removeLink(old.fields)
                if (active) d.put(LocalHotelPlace(h.id, p.id))
            }
        }
        if (kind !in setOf("country", "city", "hotel", "place", "amenity", "link"))
            d.put(CatalogEntry("version:$key", "version", kind, "{}", version = v))
        d.put(
            LocalAudit(
                e.uid(),
                actor.id,
                if (id == null) "CREATE:$kind" else "UPDATE:$kind",
                key,
                e.now(),
            )
        )
        key
    }

    private suspend fun removeLink(f: Map<String, String>) {
        val keep = d.links().filter { it.hotelId == f["hotelId"] && it.placeId != f["placeId"] }
        d.clearLinks(f.getValue("hotelId"))
        keep.forEach { d.put(it) }
    }

    suspend fun orders(): List<Booking> = e.transaction {
        e.admin()
        e.housekeeping()
        d.orders()
            .map { e.json.decodeFromString<Booking>(it.payload).copy(serverNow = e.now()) }
            .sortedByDescending { it.createdAt }
    }

    suspend fun cancel(id: String) = e.cancel(id, true)

    suspend fun audit(): List<LocalAudit> = e.transaction {
        e.admin()
        d.audit()
    }

    /** Фото — только внутренний относительный файл, не произвольный URI или путь пользователя. */
    suspend fun photo(kind: String, id: String, file: String?, remove: Boolean = false) =
        e.transaction {
            val actor = e.admin()
            e.checkValue(kind in setOf("hotel", "place"), "Фото доступны только гостинице и месту.")
            if (file != null)
                e.checkValue(Regex("[a-f0-9-]+\\.jpg").matches(file), "Неверное имя файла.")
            val row = d.entry(id) ?: e.fail("NOT_FOUND", "Объект не найден.")
            e.checkValue(row.kind == kind, "Тип объекта не совпадает.")
            val payload =
                if (kind == "hotel") {
                    val h = e.json.decodeFromString<Hotel>(row.payload)
                    e.checkValue(
                        remove || file in h.photoFiles || h.photoFiles.size < 12,
                        "Максимум 12 импортированных фото. Уберите одно перед добавлением.",
                    )
                    e.json.encodeToString(
                        h.copy(
                            photoFiles =
                                if (remove) h.photoFiles.filter { it != file }
                                else (listOfNotNull(file) + h.photoFiles).distinct()
                        )
                    )
                } else {
                    val p = e.json.decodeFromString<Place>(row.payload)
                    e.json.encodeToString(p.copy(photoFile = if (remove) null else file))
                }
            d.put(row.copy(payload = payload, version = row.version + 1))
            d.put(LocalAudit(e.uid(), actor.id, "PHOTO:$kind", id, e.now()))
        }
}
