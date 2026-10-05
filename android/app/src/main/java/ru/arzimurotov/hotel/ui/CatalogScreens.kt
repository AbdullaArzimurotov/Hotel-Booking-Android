@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ru.arzimurotov.hotel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.arzimurotov.hotel.domain.*

/**
 * Общая форма главного экрана и результатов. Диалоги редактируют черновик, подтверждённые значения
 * передаются через onQuery; поиск требует валидных дат/гостей. Сам компонент не обращается к HTTP и
 * не хранит окончательные цены.
 */
@Composable
fun SearchForm(
    catalog: Catalog,
    query: SearchQuery,
    onChange: (SearchQuery) -> Unit,
    onSearch: () -> Unit,
) {
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FormField(
                "Направление",
                query.cityId?.let { catalog.city(it).name }
                    ?: "Все города · ${catalog.country(query.countryId).name}",
                Glyph.LOCATION,
                "destination",
                Modifier.fillMaxWidth(),
            ) {
                dialog = "destination"
            }
            BoxWithConstraints {
                if (maxWidth > 500.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FormField(
                            "Заезд — выезд",
                            dateLabel(query),
                            Glyph.CALENDAR,
                            "dates",
                            Modifier.weight(1f),
                        ) {
                            dialog = "dates"
                        }
                        FormField(
                            "Гости и номера",
                            guestLabel(query),
                            Glyph.PEOPLE,
                            "guests",
                            Modifier.weight(1f),
                        ) {
                            dialog = "guests"
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FormField(
                            "Заезд — выезд",
                            dateLabel(query),
                            Glyph.CALENDAR,
                            "dates",
                            Modifier.fillMaxWidth(),
                        ) {
                            dialog = "dates"
                        }
                        FormField(
                            "Гости и номера",
                            guestLabel(query),
                            Glyph.PEOPLE,
                            "guests",
                            Modifier.fillMaxWidth(),
                        ) {
                            dialog = "guests"
                        }
                    }
                }
            }
            if (error != null)
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            Button(
                {
                    error = query.validationError(catalog)
                    if (error == null) onSearch()
                },
                Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("search_submit"),
                shape = RoundedCornerShape(12.dp),
            ) {
                TravelIcon(Glyph.SEARCH, color = Color.White)
                Spacer(Modifier.width(10.dp))
                Text("Найти гостиницы", fontWeight = FontWeight.SemiBold)
            }
        }
    }
    when (dialog) {
        "destination" ->
            DestinationDialog(catalog, query, { dialog = null }) {
                onChange(it)
                dialog = null
                error = null
            }
        "dates" ->
            DatesDialog(query, { dialog = null }) {
                onChange(it)
                dialog = null
                error = null
            }
        "guests" ->
            GuestsDialog(catalog, query, { dialog = null }) {
                onChange(it)
                dialog = null
                error = null
            }
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    icon: Glyph,
    tag: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.testTag(tag),
        shape = RoundedCornerShape(12.dp),
        color = TravelBackground,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TravelIcon(icon)
            Column(Modifier.weight(1f)) {
                Text(label, color = TravelMuted, style = MaterialTheme.typography.labelSmall)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            TravelIcon(Glyph.CHEVRON, Modifier.size(16.dp), TravelMuted)
        }
    }
}

/** Главный экран: форма, быстрые направления и рекомендации из локального каталога. */
@Composable
fun SearchScreen(
    state: BrowseState,
    wide: Boolean,
    onQuery: (SearchQuery) -> Unit,
    onResults: () -> Unit,
    onHotel: (String) -> Unit,
    onFavorite: (String) -> Unit,
) {
    val catalog = state.catalog ?: return
    val query = state.query
    val hotels =
        catalog.hotels
            .filter { h ->
                val city = catalog.city(h.cityId)
                city.countryId == query.countryId &&
                    (query.cityId == null || query.cityId == city.id)
            }
            .take(4)
    LazyVerticalGrid(
        GridCells.Fixed(if (wide) 2 else 1),
        modifier = Modifier.fillMaxSize().testTag("search_screen"),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().height(if (wide) 250.dp else 240.dp)) {
                TravelPhoto(Photo.EXTERIOR, Modifier.matchParentSize(), null)
                Box(
                    Modifier.matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(TravelNavy.copy(alpha = .3f), TravelNavy.copy(alpha = .95f))
                            )
                        )
                )
                Column(
                    Modifier.align(Alignment.BottomStart).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "ГОСТИНИЦА  /  ВАШ ПУТЕВОДИТЕЛЬ",
                        color = TravelGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                    )
                    Text(
                        "Найдите своё\nместо для отдыха",
                        color = Color.White,
                        fontSize = if (wide) 36.sp else 30.sp,
                        lineHeight = if (wide) 42.sp else 36.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Три страны. Шесть городов. Новые впечатления.",
                        color = Color(0xFFD9E4EE),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.padding(horizontal = 16.dp)) {
                SearchForm(catalog, query, onQuery, onResults)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DemoNote()
                Text(
                    "Обзор каталога. Свободные номера и цены на даты проверяются после поиска.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SectionTitle("Откройте новый город", "Быстрый выбор направления")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    catalog.cities.forEach { city ->
                        FilterChip(
                            query.cityId == city.id,
                            {
                                onQuery(
                                    query.copy(
                                        countryId = city.countryId,
                                        cityId = city.id,
                                        filters = SearchFilters(),
                                        name = "",
                                    )
                                )
                            },
                            { Text(city.name) },
                            modifier = Modifier.testTag("quick_${city.id}"),
                        )
                    }
                }
                SectionTitle(
                    "Для вашего путешествия",
                    query.cityId?.let { catalog.city(it).name }
                        ?: catalog.country(query.countryId).name,
                )
            }
        }
        items(hotels, key = { it.id }) { hotel ->
            Box(
                Modifier.padding(
                    start = if (wide && hotels.indexOf(hotel) % 2 == 1) 0.dp else 16.dp,
                    end = if (wide && hotels.indexOf(hotel) % 2 == 0) 0.dp else 16.dp,
                )
            ) {
                HotelCard(
                    hotel,
                    catalog.city(hotel.cityId),
                    catalog.country(query.countryId).currency,
                    hotel.startingPrice,
                    hotel.id in state.favorites,
                    { onHotel(hotel.id) },
                    { onFavorite(hotel.id) },
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            if (hotels.isEmpty())
                EmptyPanel(
                    "Нет подходящих вариантов",
                    "Попробуйте изменить состав гостей или фильтры.",
                    actionLabel = "Сбросить фильтры",
                    onAction = { onQuery(query.copy(filters = SearchFilters(), name = "")) },
                )
            else
                TextButton(
                    onResults,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("all_hotels"),
                ) {
                    Text("Все подходящие гостиницы")
                    Spacer(Modifier.width(8.dp))
                    TravelIcon(Glyph.CHEVRON, Modifier.size(16.dp))
                }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Названия, адреса, фотографии и оценки гостиниц — учебные примеры.",
                Modifier.padding(horizontal = 16.dp),
                color = TravelMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Результаты Catalog.search с поиском по имени, фильтрами и сортировкой. В режиме favoritesOnly
 * показывает выбранные гостиницы независимо от страны: общей сортировки по смешанным валютам нет.
 * wide включает двухколоночную сетку. Нулевой результат — отдельное состояние с возможностью сброса
 * ограничений.
 */
@Composable
fun ResultsScreen(
    state: BrowseState,
    wide: Boolean,
    onQuery: (SearchQuery) -> Unit,
    onBack: () -> Unit,
    onHotel: (String) -> Unit,
    onFavorite: (String) -> Unit,
    favoritesOnly: Boolean = false,
    server: TravelState? = null,
    onMore: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val catalog = state.catalog ?: return
    val query = state.query
    var edit by rememberSaveable { mutableStateOf(false) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var sort by remember { mutableStateOf(false) }
    val hotels =
        if (favoritesOnly) catalog.hotels.filter { it.id in state.favorites }
        else if (server != null)
            server.search?.items.orEmpty().mapNotNull { catalog.hotel(it.hotelId) }
        else catalog.search(query)
    Column(
        Modifier.fillMaxSize().testTag(if (favoritesOnly) "favorites_screen" else "results_screen")
    ) {
        ScreenHeader(
            if (favoritesOnly) "Избранное"
            else
                query.cityId?.let { catalog.city(it).name }
                    ?: catalog.country(query.countryId).name,
            if (favoritesOnly) "Сохранено только в этом сеансе приложения"
            else "${dateLabel(query)} · ${guestLabel(query)}",
            onBack,
        )
        LazyVerticalGrid(
            GridCells.Fixed(if (wide) 2 else 1),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f).testTag("results_grid"),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!favoritesOnly) {
                        OutlinedButton({ edit = !edit }, Modifier.fillMaxWidth()) {
                            TravelIcon(Glyph.SEARCH)
                            Spacer(Modifier.width(8.dp))
                            Text(if (edit) "Скрыть параметры" else "Изменить параметры поиска")
                        }
                        if (edit) SearchForm(catalog, query, onQuery, { edit = false })
                        OutlinedTextField(
                            query.name,
                            { onQuery(query.copy(name = it)) },
                            modifier = Modifier.fillMaxWidth().testTag("hotel_name_search"),
                            singleLine = true,
                            label = { Text("Название гостиницы") },
                            leadingIcon = { TravelIcon(Glyph.SEARCH) },
                            shape = RoundedCornerShape(12.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                { filters = true },
                                Modifier.weight(1f).testTag("filters_open"),
                            ) {
                                TravelIcon(Glyph.FILTER, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Фильтры${if(query.filters.activeCount>0) " · ${query.filters.activeCount}" else ""}"
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(
                                    { sort = true },
                                    Modifier.fillMaxWidth().testTag("sort_open"),
                                ) {
                                    TravelIcon(Glyph.SORT, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Порядок")
                                }
                                DropdownMenu(sort, { sort = false }) {
                                    SortOrder.entries.forEach { order ->
                                        DropdownMenuItem(
                                            text = { Text(order.title) },
                                            onClick = {
                                                onQuery(query.copy(sort = order))
                                                sort = false
                                            },
                                            trailingIcon = {
                                                if (query.sort == order) TravelIcon(Glyph.CHECK)
                                            },
                                            modifier = Modifier.testTag("sort_${order.name}"),
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            query.sort.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = TravelMuted,
                        )
                    }
                    SectionTitle(plural(hotels.size.toLong(), "вариант", "варианта", "вариантов"))
                    DemoNote(
                        if (favoritesOnly)
                            "Избранное не синхронизируется с аккаунтом. Наличие и цены — демонстрационные."
                        else if (server != null)
                            "Доступность проверена системой. Бронирование ещё не создано."
                        else "Это подбор по учебному каталогу, а не список свободных номеров."
                    )
                    if (server?.searching == true)
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().testTag("server_search_loading")
                        )
                    if (server?.search != null && !server.searching && server.searchError == null)
                        Text(
                            "Найдено: ${server.search.total}",
                            Modifier.testTag("server_search_success"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    server?.searchError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        TextButton(onRetry) { Text("Повторить поиск") }
                    }
                }
            }
            items(hotels, key = { it.id }) { hotel ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HotelCard(
                        hotel,
                        catalog.city(hotel.cityId),
                        catalog.country(catalog.city(hotel.cityId).countryId).currency,
                        if (favoritesOnly) hotel.startingPrice
                        else if (server != null)
                            server.search
                                ?.items
                                ?.find { it.hotelId == hotel.id }
                                ?.offers
                                ?.minOfOrNull { it.nightlyPrice } ?: 0
                        else hotel.matchingPrice(query),
                        hotel.id in state.favorites,
                        { onHotel(hotel.id) },
                        { onFavorite(hotel.id) },
                    )
                    if (server != null && !favoritesOnly)
                        server.search
                            ?.items
                            ?.find { it.hotelId == hotel.id }
                            ?.offers
                            ?.minByOrNull { it.stayTotal }
                            ?.let { o ->
                                Text(
                                    "От ${money(o.stayTotal,o.currency)} за весь период · ${query.rooms} номер(а). Без дополнительных услуг.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                )
                            }
                }
            }
            if (server != null && !favoritesOnly && hotels.size < (server.search?.total ?: 0))
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(
                        onMore,
                        enabled = !server.searching,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Показать ещё")
                    }
                }
            if (hotels.isEmpty() && server?.searching != true && server?.searchError == null)
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyPanel(
                        if (favoritesOnly) "Сохраните понравившиеся места" else "Ничего не найдено",
                        if (favoritesOnly) "Нажмите на сердечко на карточке гостиницы."
                        else "Измените название или сбросьте фильтры.",
                        Glyph.HEART,
                        if (favoritesOnly) null else "Сбросить фильтры",
                        { onQuery(query.copy(filters = SearchFilters(), name = "")) },
                    )
                }
        }
    }
    if (filters)
        FiltersDialog(query, catalog.country(query.countryId).currency, { filters = false }) {
            onQuery(it)
            filters = false
        }
}

/**
 * Информационный каталог мест: направление и категория ограничивают локальный список.
 * Координаты/карта и продажа билетов в этом этапе отсутствуют; карточка открывает описание.
 */
@Composable
fun PlacesScreen(
    catalog: Catalog,
    query: SearchQuery,
    wide: Boolean,
    onQuery: (SearchQuery) -> Unit,
    onPlace: (String) -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(false) }
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val category = PlaceCategory.entries.find { it.name == categoryName }
    val places =
        catalog.places.filter {
            (query.cityId == null && catalog.city(it.cityId).countryId == query.countryId ||
                it.cityId == query.cityId) && (category == null || it.category == category)
        }
    Column(Modifier.fillMaxSize().testTag("places_screen")) {
        ScreenHeader("Места и впечатления", "Изучайте город до поездки")
        LazyVerticalGrid(
            GridCells.Fixed(if (wide) 2 else 1),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        { destination = true },
                        Modifier.fillMaxWidth().testTag("places_destination"),
                    ) {
                        TravelIcon(Glyph.LOCATION)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            query.cityId?.let { catalog.city(it).name }
                                ?: catalog.country(query.countryId).name
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(category == null, { categoryName = null }, { Text("Все") })
                        PlaceCategory.entries.forEach { item ->
                            FilterChip(
                                category == item,
                                { categoryName = item.name },
                                { Text(item.title) },
                                modifier = Modifier.testTag("place_category_${item.name}"),
                            )
                        }
                    }
                    DemoNote(
                        "Места вымышлены. Карта и реальные данные будут подключены позже. Билеты и заказы здесь не оформляются."
                    )
                }
            }
            items(places, key = { it.id }) { place ->
                PlaceCard(place, catalog.city(place.cityId).name) { onPlace(place.id) }
            }
        }
    }
    if (destination)
        DestinationDialog(catalog, query, { destination = false }) {
            onQuery(it)
            destination = false
        }
}

/** Карточка городского места с явно условной иллюстрацией и переходом к деталям. */
@Composable
fun PlaceCard(place: Place, cityName: String, onClick: () -> Unit) {
    Card(
        onClick,
        Modifier.fillMaxWidth().testTag("place_${place.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        TravelPhoto(
            place.photo,
            Modifier.fillMaxWidth().height(140.dp),
            "Учебная иллюстрация места, не фотография объекта",
            fileName = place.photoFile,
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${place.category.title} · $cityName",
                color = TravelMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                place.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text("Подробнее", style = MaterialTheme.typography.bodySmall, color = TravelGreen)
        }
    }
}
