package ru.arzimurotov.hotel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import ru.arzimurotov.hotel.R
import ru.arzimurotov.hotel.domain.*

val TravelNavy = Color(0xFF132F4C)
val TravelGold = Color(0xFFDDA763)
val TravelMuted = Color(0xFF627285)
val TravelGreen = Color(0xFF26725C)
val TravelBackground = Color(0xFFF4F6F9)
val Russian: Locale = Locale.forLanguageTag("ru-RU")
private val ShortDate = DateTimeFormatter.ofPattern("d MMM", Russian)

fun dateLabel(q: SearchQuery) = "${q.checkIn.format(ShortDate)} — ${q.checkOut.format(ShortDate)}"

fun plural(n: Long, one: String, few: String, many: String): String =
    "$n " +
        when {
            n % 100 in 11..14 -> many
            n % 10 == 1L -> one
            n % 10 in 2..4 -> few
            else -> many
        }

fun guestLabel(q: SearchQuery) =
    "${plural((q.adults + q.children).toLong(), "гость", "гостя", "гостей")} · ${plural(q.rooms.toLong(), "номер", "номера", "номеров")}"

/** Форматирование целых денежных единиц на русском; валюты не конвертируются. */
fun money(value: Long, currency: String) =
    NumberFormat.getNumberInstance(Russian).apply { maximumFractionDigits = 2 }.format(java.math.BigDecimal.valueOf(value, 2)) +
        " " +
        when (currency) {
            "RUB" -> "₽"
            "TRY" -> "₺"
            else -> "сум"
        }

enum class Glyph {
    SEARCH,
    LOCATION,
    BOOKING,
    PROFILE,
    BACK,
    HEART,
    CALENDAR,
    PEOPLE,
    FILTER,
    SORT,
    STAR,
    PLUS,
    MINUS,
    CHECK,
    CLOSE,
    INFO,
    CHEVRON,
}

/** Собственные линейные иконки без внешнего иконочного шрифта или сетевого сервиса. */
@Composable
fun TravelIcon(
    icon: Glyph,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    description: String? = null,
    filled: Boolean = false,
) {
    Canvas(
        modifier
            .size(22.dp)
            .then(
                if (description != null) Modifier.semantics { contentDescription = description }
                else Modifier
            )
    ) {
        val s = size.minDimension / 24f
        val stroke = Stroke(1.8f * s)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x: Float, y: Float, xx: Float, yy: Float) =
            drawLine(color, p(x, y), p(xx, yy), 1.8f * s)
        fun path(vararg points: Pair<Float, Float>, close: Boolean = false) {
            val path =
                Path().apply {
                    moveTo(points[0].first * s, points[0].second * s)
                    points.drop(1).forEach { lineTo(it.first * s, it.second * s) }
                    if (close) close()
                }
            drawPath(path, color, style = stroke)
        }
        when (icon) {
            Glyph.SEARCH -> {
                drawCircle(color, 7 * s, p(10f, 10f), style = stroke)
                line(15f, 15f, 21f, 21f)
            }
            Glyph.LOCATION -> {
                val path =
                    Path().apply {
                        moveTo(12 * s, 22 * s)
                        cubicTo(0 * s, 11 * s, 4 * s, 3 * s, 12 * s, 3 * s)
                        cubicTo(20 * s, 3 * s, 24 * s, 11 * s, 12 * s, 22 * s)
                    }
                drawPath(path, color, style = stroke)
                drawCircle(color, 2.5f * s, p(12f, 10f), style = stroke)
            }
            Glyph.BOOKING -> {
                drawRoundRect(
                    color,
                    p(5f, 3f),
                    Size(14 * s, 18 * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * s),
                    style = stroke,
                )
                line(8f, 8f, 16f, 8f)
                line(8f, 12f, 16f, 12f)
                line(8f, 16f, 13f, 16f)
            }
            Glyph.PROFILE,
            Glyph.PEOPLE -> {
                drawCircle(color, 3.6f * s, p(12f, 7f), style = stroke)
                drawArc(color, 180f, 180f, false, p(4f, 13f), Size(16 * s, 14 * s), style = stroke)
            }
            Glyph.BACK -> {
                line(20f, 12f, 4f, 12f)
                path(11f to 5f, 4f to 12f, 11f to 19f)
            }
            Glyph.HEART -> {
                val path =
                    Path().apply {
                        moveTo(12 * s, 21 * s)
                        cubicTo(1 * s, 14 * s, 0 * s, 8 * s, 5 * s, 4 * s)
                        cubicTo(8 * s, 2 * s, 11 * s, 4 * s, 12 * s, 6 * s)
                        cubicTo(13 * s, 4 * s, 16 * s, 2 * s, 19 * s, 4 * s)
                        cubicTo(24 * s, 8 * s, 23 * s, 14 * s, 12 * s, 21 * s)
                        close()
                    }
                if (filled) drawPath(path, color) else drawPath(path, color, style = stroke)
            }
            Glyph.CALENDAR -> {
                drawRoundRect(
                    color,
                    p(3f, 5f),
                    Size(18 * s, 16 * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * s),
                    style = stroke,
                )
                line(3f, 10f, 21f, 10f)
                line(8f, 2f, 8f, 7f)
                line(16f, 2f, 16f, 7f)
                line(7f, 15f, 10f, 15f)
                line(14f, 15f, 17f, 15f)
            }
            Glyph.FILTER -> {
                line(3f, 6f, 21f, 6f)
                line(3f, 12f, 21f, 12f)
                line(3f, 18f, 21f, 18f)
                drawCircle(color, 2 * s, p(8f, 6f), style = stroke)
                drawCircle(color, 2 * s, p(16f, 12f), style = stroke)
                drawCircle(color, 2 * s, p(10f, 18f), style = stroke)
            }
            Glyph.SORT -> {
                line(5f, 5f, 5f, 20f)
                path(2f to 17f, 5f to 20f, 8f to 17f)
                line(11f, 5f, 21f, 5f)
                line(11f, 12f, 18f, 12f)
                line(11f, 19f, 15f, 19f)
            }
            Glyph.STAR -> {
                val path = Path()
                (0..9).forEach { i ->
                    val a = -Math.PI / 2 + i * Math.PI / 5
                    val r = if (i % 2 == 0) 10.0 else 4.5
                    val x = (12 + cos(a) * r).toFloat() * s
                    val y = (12 + sin(a) * r).toFloat() * s
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, color)
            }
            Glyph.PLUS -> {
                line(4f, 12f, 20f, 12f)
                line(12f, 4f, 12f, 20f)
            }
            Glyph.MINUS -> line(4f, 12f, 20f, 12f)
            Glyph.CHECK -> path(4f to 12f, 9f to 17f, 20f to 6f)
            Glyph.CLOSE -> {
                line(5f, 5f, 19f, 19f)
                line(19f, 5f, 5f, 19f)
            }
            Glyph.INFO -> {
                drawCircle(color, 9 * s, p(12f, 12f), style = stroke)
                drawCircle(color, s, p(12f, 7f))
                line(12f, 11f, 12f, 17f)
            }
            Glyph.CHEVRON -> path(9f to 5f, 16f to 12f, 9f to 19f)
        }
    }
}

/** Выбирает встроенный drawable по доменному Photo: HTTP-изображений в прототипе нет. */
@Composable
fun TravelPhoto(
    photo: Photo,
    modifier: Modifier = Modifier,
    description: String? = "Иллюстрация вымышленной гостиницы",
) {
    Image(
        painterResource(
            when (photo) {
                Photo.EXTERIOR -> R.drawable.hotel_exterior
                Photo.ROOM -> R.drawable.hotel_room
                Photo.POOL -> R.drawable.hotel_pool
            }
        ),
        description,
        modifier,
        contentScale = ContentScale.Crop,
    )
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(TravelNavy)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onBack, Modifier.testTag("back")) {
                TravelIcon(Glyph.BACK, color = Color.White, description = "Назад")
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null)
                Text(
                    subtitle,
                    color = Color(0xFFD0DCE8),
                    style = MaterialTheme.typography.bodySmall,
                )
        }
        action?.invoke()
    }
}

@Composable
fun DemoNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0xFFEBF0F5), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TravelIcon(Glyph.INFO, Modifier.size(18.dp), TravelMuted)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = TravelMuted,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable fun DemoNote() = DemoNote("Учебный каталог. Наличие и итоговая цена проверяются сервером для выбранных дат.")

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (subtitle != null)
            Text(subtitle, color = TravelMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Stars(count: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier =
            Modifier.semantics {
                contentDescription = plural(count.toLong(), "звезда", "звезды", "звёзд")
            },
    ) {
        repeat(count) { TravelIcon(Glyph.STAR, Modifier.size(13.dp), TravelGold) }
    }
}

@Composable
fun FavoriteButton(selected: Boolean, id: String, onClick: () -> Unit) {
    FilledIconButton(
        onClick,
        Modifier.size(44.dp).testTag("favorite_$id"),
        shape = CircleShape,
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White.copy(alpha = .96f)
            ),
    ) {
        TravelIcon(
            Glyph.HEART,
            color = if (selected) Color(0xFFB33F58) else TravelNavy,
            filled = selected,
            description = if (selected) "Убрать из избранного" else "В избранное",
        )
    }
}

/**
 * Общая карточка поиска/рекомендаций/избранного. Звёзды, рейтинг и валюта разделены. При наличии
 * query отображает цену подходящего номера; сердечко — отдельное действие.
 */
@Composable
fun HotelCard(
    hotel: Hotel,
    city: City,
    currency: String,
    price: Long,
    favorite: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth().testTag("hotel_${hotel.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Box {
            TravelPhoto(hotel.photos.first(), Modifier.fillMaxWidth().height(178.dp))
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                FavoriteButton(favorite, hotel.id, onFavorite)
            }
            Surface(
                color = TravelNavy.copy(alpha = .9f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            ) {
                Text(
                    "ДЕМО · ${hotel.stars}★",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Stars(hotel.stars)
            Text(
                hotel.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${city.name} · ${String.format(Russian,"%.1f",hotel.distanceKm)} км от центра",
                style = MaterialTheme.typography.bodySmall,
                color = TravelMuted,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Surface(
                    color = TravelNavy,
                    shape = RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp, bottomEnd = 7.dp),
                ) {
                    Text(
                        String.format(Russian, "%.1f", hotel.rating),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    if (hotel.rating >= 9) "Превосходно" else "Очень хорошо",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("демооценка", style = MaterialTheme.typography.labelSmall, color = TravelMuted)
            }
            HorizontalDivider(color = Color(0xFFEEF1F4))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "от ${money(price,currency)}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "за ночь / номер",
                        style = MaterialTheme.typography.bodySmall,
                        color = TravelMuted,
                    )
                }
                TravelIcon(Glyph.CHEVRON)
            }
        }
    }
}

/** Переиспользуемое пустое/ошибочное состояние с необязательным действием восстановления. */
@Composable
fun EmptyPanel(
    title: String,
    text: String,
    icon: Glyph = Glyph.SEARCH,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(shape = CircleShape, color = Color(0xFFE5ECF3)) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                TravelIcon(icon, Modifier.size(30.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            text,
            color = TravelMuted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null)
            Button(onAction, shape = RoundedCornerShape(12.dp)) { Text(actionLabel) }
    }
}
