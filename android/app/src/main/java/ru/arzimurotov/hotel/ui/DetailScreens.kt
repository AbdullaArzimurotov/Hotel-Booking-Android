@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ru.arzimurotov.hotel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.arzimurotov.hotel.domain.*

/** Локальная HorizontalPager-галерея; тап передаёт индекс для полноэкранного просмотра. */
@Composable
fun PhotoGallery(hotel: Hotel, onFull: (Int) -> Unit) {
    val count = (hotel.photoFiles.size + hotel.photos.size).coerceAtLeast(1)
    val pager = rememberPagerState(pageCount = { count })
    Box {
        HorizontalPager(pager, Modifier.fillMaxWidth().height(260.dp).testTag("hotel_gallery")) {
            page ->
            TravelPhoto(
                hotel.photos.getOrNull(page - hotel.photoFiles.size) ?: Photo.EXTERIOR,
                Modifier.fillMaxSize().clickable { onFull(page) },
                "Фото ${page+1} из $count. Открыть галерею",
                fileName = hotel.photoFiles.getOrNull(page),
            )
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = TravelNavy.copy(alpha = .85f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Text(
                "${pager.currentPage+1} / $count",
                color = Color.White,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * Описание гостиницы, удобства, типы номеров, услуги и места этого города. Вместимость сравнивается
 * с guestsPerRoom; неподходящий тип нельзя выбрать для расчёта. Удобства — информационные метки, а
 * не неработающие кнопки. onRoom открывает checkout, не создаёт бронь; наличие повторно
 * проверяет repository. live=false сохранён для предварительного test-only макета.
 */
@Composable
fun HotelDetailScreen(
    catalog: Catalog,
    hotel: Hotel,
    query: SearchQuery,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onBack: () -> Unit,
    onGallery: (Int) -> Unit,
    onRoom: (RoomKind) -> Unit,
    onPlace: (String) -> Unit,
    live: Boolean = false,
    checking: Boolean = false,
    availabilityError: String? = null,
    onRetry: () -> Unit = {},
) {
    val currency = catalog.country(catalog.city(hotel.cityId).countryId).currency
    Column(Modifier.fillMaxSize().testTag("hotel_detail")) {
        ScreenHeader(hotel.name, catalog.city(hotel.cityId).name, onBack) {
            FavoriteButton(favorite, hotel.id, onFavorite)
        }
        LazyColumn(
            Modifier.weight(1f).testTag("hotel_detail_list"),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { PhotoGallery(hotel, onGallery) }
            item {
                Column(
                    Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Stars(hotel.stars)
                    SectionTitle(hotel.name, "${hotel.address} · учебный адрес")
                    if (hotel.latitude != null && hotel.longitude != null) {
                        Text(
                            "Демо-координаты: ${hotel.latitude}, ${hotel.longitude}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (
                            !Geo.hasMapCoverage(
                                catalog.city(hotel.cityId).legacyId,
                                hotel.latitude,
                                hotel.longitude,
                            )
                        )
                            Text(
                                "Объект вне встроенного района карты. Описание доступно offline.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(color = TravelNavy, shape = RoundedCornerShape(8.dp)) {
                            Text(
                                String.format(Russian, "%.1f", hotel.rating),
                                color = Color.White,
                                modifier = Modifier.padding(8.dp),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            "Демонстрационный рейтинг",
                            style = MaterialTheme.typography.bodySmall,
                            color = TravelMuted,
                        )
                    }
                    Text(hotel.description, style = MaterialTheme.typography.bodyMedium)
                    DemoNote(
                        "Все изображения — созданные иллюстрации, общие для учебного каталога. Реальные объекты ими не представлены."
                    )
                    SectionTitle("Удобства")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        (hotel.amenityLabels.ifEmpty { hotel.amenities.map { it.title } })
                            .forEach { amenity ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color.White,
                                    border =
                                        androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            Color(0xFFDFE5EC),
                                        ),
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        TravelIcon(Glyph.CHECK, Modifier.size(14.dp), TravelGreen)
                                        Text(amenity, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                    }
                    Text(
                        "Перечень описывает возможности гостиницы. Завтрак и другие услуги не включены в базовую цену номера.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TravelMuted,
                    )
                    SectionTitle("Номера", "${dateLabel(query)} · ${guestLabel(query)}")
                    DemoNote(
                        if (live)
                            "Доступность на выбранные даты проверяется системой. Заказ ещё не создан."
                        else
                            "Выберите тип для предварительного расчёта. Наличие и бронирование будут проверяться сервером на следующем этапе."
                    )
                    if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                    availabilityError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        TextButton(onRetry) { Text("Повторить") }
                    }
                    if (live && !checking && hotel.rooms.isEmpty() && availabilityError == null)
                        Text("На эти даты нет подходящих свободных номеров.")
                }
            }
            items(hotel.rooms, key = { it.kind.name }) { room ->
                Card(
                    Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                room.kind.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${room.area} м² · до ${room.capacity} гостей",
                                style = MaterialTheme.typography.bodySmall,
                                color = TravelMuted,
                            )
                            Text(
                                money(room.pricePerNight, currency),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "за ночь / номер",
                                style = MaterialTheme.typography.bodySmall,
                                color = TravelMuted,
                            )
                            Button(
                                { onRoom(room.kind) },
                                enabled = room.capacity >= query.guestsPerRoom,
                                modifier = Modifier.testTag("room_${room.kind.name}"),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(if (live) "Выбрать и оформить" else "Рассчитать")
                            }
                            if (room.capacity < query.guestsPerRoom)
                                Text(
                                    "Не подходит для выбранного состава гостей",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                        }
                        TravelPhoto(Photo.ROOM, Modifier.width(90.dp).height(110.dp), null)
                    }
                }
            }
            item {
                Column(
                    Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionTitle("Дополнительные услуги")
                    hotel.services.forEach { service ->
                        Text(service.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${money(service.price,currency)} · ${if(service.perNight) "за ночь / номер" else "разово на заказ"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            service.description,
                            color = TravelMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        HorizontalDivider()
                    }
                    SectionTitle(
                        "Рядом с гостиницей",
                        "Демонстрационные координаты; расстояние по прямой",
                    )
                }
            }
            items(
                catalog.places.filter {
                    it.cityId == hotel.cityId &&
                        (hotel.nearbyPlaceIds == null || it.id in hotel.nearbyPlaceIds)
                },
                key = { it.id },
            ) { place ->
                Box(Modifier.padding(horizontal = 20.dp)) {
                    Column {
                        PlaceCard(place, catalog.city(place.cityId).name) { onPlace(place.id) }
                        if (
                            hotel.latitude != null &&
                                hotel.longitude != null &&
                                place.latitude != null &&
                                place.longitude != null
                        )
                            Text(
                                "По прямой: " +
                                    String.format(
                                        Russian,
                                        "%.2f км",
                                        Geo.distanceKm(
                                            hotel.latitude,
                                            hotel.longitude,
                                            place.latitude,
                                            place.longitude,
                                        ),
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                }
            }
        }
    }
}

/** Полноэкранные оригинальные иллюстрации с ContentScale.Fit, без обрезки изображения. */
@Composable
fun GalleryScreen(hotel: Hotel, initialPage: Int, onBack: () -> Unit) {
    val count = (hotel.photoFiles.size + hotel.photos.size).coerceAtLeast(1)
    val pager =
        rememberPagerState(
            initialPage = initialPage.coerceIn(0 until count),
            pageCount = { count },
        )
    Column(Modifier.fillMaxSize().background(TravelNavy).testTag("gallery_screen")) {
        ScreenHeader(
            "Фотогалерея",
            "${hotel.name} · ${pager.currentPage+1} / $count",
            onBack,
        )
        HorizontalPager(pager, Modifier.weight(1f).testTag("full_gallery")) { page ->
            TravelPhoto(
                hotel.photos.getOrNull(page - hotel.photoFiles.size) ?: Photo.EXTERIOR,
                Modifier.fillMaxSize(),
                "Фото ${page+1}",
                fileName = hotel.photoFiles.getOrNull(page),
                scale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
            (0 until count).forEach { i ->
                Box(
                    Modifier.padding(4.dp)
                        .size(if (i == pager.currentPage) 10.dp else 8.dp)
                        .background(
                            if (i == pager.currentPage) TravelGold
                            else Color.White.copy(alpha = .4f),
                            CircleShape,
                        )
                )
            }
        }
        Text(
            "Созданные иллюстрации вымышленной гостиницы",
            color = Color.White.copy(alpha = .7f),
            style = MaterialTheme.typography.bodySmall,
            modifier =
                Modifier.align(Alignment.CenterHorizontally)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

/**
 * Предварительный расчёт выбранного типа номера и дополнительных услуг. Выбранные id сопоставляются
 * со списком hotel.services, сумма считает previewTotal. Подтверждение показывает объяснение
 * будущего процесса, а не поддельную успешную бронь. Нет платёжных реквизитов, вызова booking API
 * или записи заказа в SQL.
 */
@Composable
fun QuoteScreen(
    catalog: Catalog,
    hotel: Hotel,
    room: RoomOffer,
    query: SearchQuery,
    onBack: () -> Unit,
) {
    var selectedIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var explain by rememberSaveable { mutableStateOf(false) }
    val services = hotel.services.filter { it.id in selectedIds }
    val currency = catalog.country(catalog.city(hotel.cityId).countryId).currency
    Column(Modifier.fillMaxSize().testTag("quote_screen")) {
        ScreenHeader("Предварительный расчёт", hotel.name, onBack)
        LazyColumn(
            Modifier.weight(1f).testTag("quote_list"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                TravelPhoto(Photo.ROOM, Modifier.fillMaxWidth().height(200.dp))
                Spacer(Modifier.height(16.dp))
                SectionTitle(
                    room.kind.title,
                    "${room.area} м² · до ${room.capacity} гостей на номер",
                )
            }
            item {
                SectionTitle(
                    dateLabel(query),
                    "${plural(query.nights,"ночь","ночи","ночей")} · ${guestLabel(query)}",
                )
            }
            item {
                DemoNote(
                    "Это только расчёт интерфейса. Доступность, окончательная цена и правила отмены ещё не подтверждены."
                )
            }
            item {
                SectionTitle("Добавьте комфорт", "Услуги включаются только в предварительную сумму")
            }
            items(hotel.services, key = { it.id }) { service ->
                OutlinedCard(
                    onClick = {
                        selectedIds =
                            if (service.id in selectedIds) selectedIds - service.id
                            else selectedIds + service.id
                    },
                    modifier = Modifier.fillMaxWidth().testTag("service_${service.id}"),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(service.id in selectedIds, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(service.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${money(service.price,currency)} · ${if(service.perNight) "за ночь / номер" else "разово"}",
                                color = TravelMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                service.description,
                                color = TravelMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Проживание: ${money(room.pricePerNight*query.nights*query.rooms,currency)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Услуги: ${money(previewTotal(room,query,services)-room.pricePerNight*query.nights*query.rooms,currency)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        HorizontalDivider()
                        Text("Итого предварительно", color = TravelMuted)
                        Text(
                            money(previewTotal(room, query, services), currency),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("quote_total"),
                        )
                        Text(
                            "Налоги и специальные тарифы в учебном расчёте не моделируются. Деньги не списываются, номер не резервируется.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TravelMuted,
                        )
                        Button(
                            { explain = true },
                            Modifier.fillMaxWidth().testTag("booking_info"),
                        ) {
                            Text("Как будет оформляться бронь")
                        }
                    }
                }
            }
        }
    }
    if (explain)
        AlertDialog(
            { explain = false },
            title = { Text("Бронирование — следующий этап") },
            text = {
                Text(
                    "После подключения входа и SQL-каталога сервер повторно проверит наличие номера и рассчитает окончательную стоимость. Для трансфера потребуется указать аэропорт, рейс и время. Затем можно будет выбрать демооплату или оплату в гостинице. Сейчас никакая бронь не создаётся."
                )
            },
            confirmButton = { TextButton({ explain = false }) { Text("Понятно") } },
        )
}

/** Описание вымышленного места; не выдаёт билет и не обещает реальную услугу. */
@Composable
fun PlaceDetailScreen(place: Place, onBack: () -> Unit, cityKey: String? = null) {
    Column(Modifier.fillMaxSize().testTag("place_detail")) {
        ScreenHeader(place.category.title, "Учебный городской каталог", onBack)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                TravelPhoto(
                    place.photo,
                    Modifier.fillMaxWidth().height(250.dp),
                    "Иллюстрация, не фотография реального места",
                    fileName = place.photoFile,
                )
            }
            item {
                Column(
                    Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SectionTitle(place.name, place.address)
                    Text(place.description)
                    if (place.latitude != null && place.longitude != null) {
                        Text(
                            "Демо-координаты: ${place.latitude}, ${place.longitude}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!Geo.hasMapCoverage(cityKey, place.latitude, place.longitude))
                            Text(
                                "Для этих координат нет встроенного покрытия. Список и описание доступны без карты.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                    DemoNote(
                        "Изображение служит иллюстрацией. Часы работы, маршруты и координаты реальных объектов здесь не представлены."
                    )
                    SectionTitle("Планирование без спешки")
                    Text(
                        "Места и их демонстрационные координаты доступны в списке и на встроенной offline-карте центральных районов. Рестораны — информационные объекты; билеты для музеев и мероприятий в эту версию не входят.",
                        color = TravelMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
