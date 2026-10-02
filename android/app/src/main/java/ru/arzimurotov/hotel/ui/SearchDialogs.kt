@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ru.arzimurotov.hotel.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import ru.arzimurotov.hotel.domain.*

@Composable
private fun FormDialog(
    title: String,
    dismiss: () -> Unit,
    confirm: () -> Unit,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            modifier =
                Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 700.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(dismiss) { Text("Отмена") }
                    Button(
                        confirm,
                        enabled = enabled,
                        modifier = Modifier.testTag("dialog_confirm"),
                    ) {
                        Text("Готово")
                    }
                }
            }
        }
    }
}

/**
 * Выбор страны, конкретного города или всех городов страны. Черновик направления не меняет общую
 * форму до нажатия подтверждения.
 */
@Composable
fun DestinationDialog(
    catalog: Catalog,
    query: SearchQuery,
    dismiss: () -> Unit,
    confirm: (SearchQuery) -> Unit,
) {
    var country by rememberSaveable { mutableStateOf(query.countryId) }
    var city by rememberSaveable { mutableStateOf(query.cityId) }
    FormDialog(
        "Куда отправимся?",
        dismiss,
        {
            confirm(
                query.copy(countryId = country, cityId = city, filters = SearchFilters(), name = "")
            )
        },
    ) {
        Text("Страна", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            catalog.countries.forEach { item ->
                FilterChip(
                    country == item.id,
                    {
                        country = item.id
                        city = null
                    },
                    { Text(item.name) },
                    modifier = Modifier.testTag("country_${item.id}"),
                )
            }
        }
        Text("Город", style = MaterialTheme.typography.labelLarge)
        FilterChip(
            city == null,
            { city = null },
            { Text("Все города страны") },
            modifier = Modifier.testTag("city_all"),
        )
        catalog.cities
            .filter { it.countryId == country }
            .forEach { item ->
                OutlinedCard(
                    onClick = { city = item.id },
                    modifier = Modifier.fillMaxWidth().testTag("city_${item.id}"),
                    colors =
                        CardDefaults.outlinedCardColors(
                            containerColor =
                                if (city == item.id) ColorTokens.Selected
                                else MaterialTheme.colorScheme.surface
                        ),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                item.caption,
                                style = MaterialTheme.typography.bodySmall,
                                color = TravelMuted,
                            )
                        }
                        if (city == item.id) TravelIcon(Glyph.CHECK)
                    }
                }
            }
    }
}

private object ColorTokens {
    val Selected = androidx.compose.ui.graphics.Color(0xFFEBF0F6)
}

/**
 * Счётчики взрослых, детей и номеров. Проверяет ограничения SearchQuery, чтобы на каждый номер
 * приходился взрослый и общая вместимость не превышалась.
 */
@Composable
fun GuestsDialog(
    catalog: Catalog,
    query: SearchQuery,
    dismiss: () -> Unit,
    confirm: (SearchQuery) -> Unit,
) {
    var adults by rememberSaveable { mutableIntStateOf(query.adults) }
    var children by rememberSaveable { mutableIntStateOf(query.children) }
    var rooms by rememberSaveable { mutableIntStateOf(query.rooms) }
    val updated = query.copy(adults = adults, children = children, rooms = rooms)
    val error = updated.validationError(catalog)
    FormDialog("Гости и номера", dismiss, { confirm(updated) }, error == null) {
        CounterRow("Взрослые", "От 18 лет", adults, 1, 8, "adults") { adults = it }
        CounterRow("Дети", "Учебный расчёт без возрастных тарифов", children, 0, 4, "children") {
            children = it
        }
        CounterRow("Номера", "До 4 гостей в одном номере", rooms, 1, 4, "rooms") { rooms = it }
        if (error != null)
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
    }
}

@Composable
private fun CounterRow(
    title: String,
    caption: String,
    value: Int,
    min: Int,
    max: Int,
    tag: String,
    change: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = TravelMuted)
        }
        IconButton(
            { change(value - 1) },
            enabled = value > min,
            modifier = Modifier.testTag("${tag}_minus"),
        ) {
            TravelIcon(
                Glyph.MINUS,
                description = "Уменьшить: $title",
                color = if (value > min) TravelNavy else TravelMuted.copy(alpha = .3f),
            )
        }
        Text(
            value.toString(),
            Modifier.width(20.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(
            { change(value + 1) },
            enabled = value < max,
            modifier = Modifier.testTag("${tag}_plus"),
        ) {
            TravelIcon(
                Glyph.PLUS,
                description = "Увеличить: $title",
                color = if (value < max) TravelNavy else TravelMuted.copy(alpha = .3f),
            )
        }
    }
}

/**
 * Календарь диапазона на русском языке. Material хранит даты как миллисекунды UTC: преобразование
 * через ZoneOffset.UTC предотвращает смещение на день из-за часового пояса. Нельзя подтвердить
 * прошлую дату, пустой диапазон или проживание более 90 ночей.
 */
@Composable
fun DatesDialog(query: SearchQuery, dismiss: () -> Unit, confirm: (SearchQuery) -> Unit) {
    val today = remember { LocalDate.now() }
    fun epoch(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state =
        rememberDateRangePickerState(
            initialSelectedStartDateMillis = epoch(query.checkIn),
            initialSelectedEndDateMillis = epoch(query.checkOut),
            yearRange = today.year..today.plusYears(2).year,
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        utcTimeMillis >= epoch(today)
                },
        )
    val start =
        state.selectedStartDateMillis?.let {
            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
        }
    val end =
        state.selectedEndDateMillis?.let {
            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
        }
    val valid =
        start != null &&
            end != null &&
            end > start &&
            java.time.temporal.ChronoUnit.DAYS.between(start, end) <= 90
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.padding(12.dp).widthIn(max = 560.dp).fillMaxWidth(),
        ) {
            Column {
                DateRangePicker(
                    state,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(max = 500.dp).weight(1f, fill = false),
                    title = { Text("Даты поездки", Modifier.padding(start = 24.dp, top = 20.dp)) },
                    headline = {
                        val format =
                            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy", Russian)
                        Row(
                            Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Заезд",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TravelMuted,
                                )
                                Text(
                                    start?.format(format) ?: "Выберите",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TravelNavy,
                                )
                            }
                            TravelIcon(Glyph.CHEVRON, Modifier.size(16.dp), TravelMuted)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Выезд",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TravelMuted,
                                )
                                Text(
                                    end?.format(format) ?: "Выберите",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TravelNavy,
                                )
                            }
                        }
                    },
                    showModeToggle = true,
                    colors =
                        DatePickerDefaults.colors(
                            containerColor = androidx.compose.ui.graphics.Color.White,
                            titleContentColor = TravelMuted,
                            headlineContentColor = TravelNavy,
                            dayInSelectionRangeContainerColor =
                                MaterialTheme.colorScheme.secondaryContainer,
                            dayInSelectionRangeContentColor = TravelNavy,
                            dividerColor = androidx.compose.ui.graphics.Color(0xFFDFE5EC),
                        ),
                )
                Text(
                    "От 1 до 90 ночей. Даты не подтверждают наличие номеров.",
                    Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TravelMuted,
                )
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(dismiss) { Text("Отмена") }
                    Button(
                        { confirm(query.copy(checkIn = start!!, checkOut = end!!)) },
                        enabled = valid,
                        modifier = Modifier.testTag("dialog_confirm"),
                    ) {
                        Text("Готово")
                    }
                }
            }
        }
    }
}

/**
 * Черновики пяти групп фильтров сохраняются rememberSaveable при пересоздании UI. Бюджет вводится
 * целым числом в валюте выбранной страны. «Применить» передаёт новый SearchFilters; закрытие без
 * подтверждения не меняет исходный запрос.
 */
@Composable
fun FiltersDialog(
    query: SearchQuery,
    currency: String,
    dismiss: () -> Unit,
    confirm: (SearchQuery) -> Unit,
) {
    var stars by rememberSaveable { mutableStateOf(query.filters.stars) }
    var rating by rememberSaveable { mutableDoubleStateOf(query.filters.minRating) }
    var price by rememberSaveable { mutableStateOf(query.filters.maxPrice?.div(100)?.toString().orEmpty()) }
    var amenities by rememberSaveable { mutableStateOf(query.filters.amenities) }
    var roomKind by rememberSaveable { mutableStateOf(query.filters.roomKind) }
    var distance by rememberSaveable {mutableStateOf(query.filters.maxDistanceKm?.toString().orEmpty())}
    val priceValid = (price.isEmpty() || (price.toLongOrNull()?.let { it in 1..9999999999L } == true)) &&
        (distance.isEmpty() || distance.toDoubleOrNull()?.let {it.isFinite()&&it in 0.0..1000.0}==true)
    FormDialog(
        "Фильтры",
        dismiss,
        {
            confirm(
                query.copy(
                    filters =
                        SearchFilters(stars, rating, price.toLongOrNull()?.times(100), amenities, roomKind,distance.toDoubleOrNull())
                )
            )
        },
        priceValid,
    ) {
        TextButton(
            {
                stars = emptySet()
                rating = 0.0
                price = ""
                amenities = emptySet()
                roomKind = null
                distance = ""
            },
            Modifier.testTag("reset_filters"),
        ) {
            Text("Сбросить всё")
        }
        Text("Категория гостиницы", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (3..5).forEach { n ->
                FilterChip(
                    n in stars,
                    { stars = if (n in stars) stars - n else stars + n },
                    { Text(plural(n.toLong(), "звезда", "звезды", "звёзд")) },
                    modifier = Modifier.testTag("stars_$n"),
                )
            }
        }
        Text("Минимальный рейтинг", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.0, 8.5, 9.0).forEach { n ->
                FilterChip(
                    rating == n,
                    { rating = n },
                    { Text(if (n == 0.0) "Любой" else String.format(Russian, "%.1f+", n)) },
                    modifier = Modifier.testTag("rating_$n"),
                )
            }
        }
        OutlinedTextField(
            price,
            { price = it.filter(Char::isDigit).take(12) },
            label = { Text("До, за ночь / номер ($currency)") },
            singleLine = true,
            isError = !priceValid,
            modifier = Modifier.fillMaxWidth().testTag("price_filter"),
            keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
        )
        OutlinedTextField(distance,{distance=it.replace(',','.').take(8)},label={Text("До центра, максимум км")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("distance_filter"))
        Text("Удобства", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Amenity.entries.forEach { a ->
                FilterChip(
                    a in amenities,
                    { amenities = if (a in amenities) amenities - a else amenities + a },
                    { Text(a.title) },
                    modifier = Modifier.testTag("amenity_${a.name}"),
                )
            }
        }
        Text("Тип номера", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(roomKind == null, { roomKind = null }, { Text("Любой") })
            RoomKind.entries.forEach { k ->
                FilterChip(roomKind == k, { roomKind = k }, { Text(k.title) })
            }
        }
    }
}
