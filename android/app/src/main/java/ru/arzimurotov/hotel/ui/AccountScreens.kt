package ru.arzimurotov.hotel.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import ru.arzimurotov.hotel.BuildConfig

/**
 * Честное пустое состояние будущего списка заказов. Демо-«успешные брони» не создаются.
 * Единственное действие возвращает к поиску; SQL-история будет подключена на этапе 6.
 */
@Composable
fun BookingsScreen(onSearch: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag("bookings_screen")) {
        ScreenHeader("Бронирования", "Все ваши поездки в одном месте")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
            item {
                EmptyPanel(
                    "Ваши поездки будут здесь",
                    "После подключения бронирования здесь появятся активные заказы, история, статусы оплаты и PDF-подтверждения. Сейчас заказы не создаются.",
                    Glyph.BOOKING,
                    "Выбрать гостиницу",
                    onSearch,
                )
            }
            item {
                DemoNote(
                    "Пустой список не имитирует историю аккаунта. Вход и SQL-заказы разрабатываются на следующих этапах."
                )
            }
        }
    }
}

/**
 * Гостевой профиль: число избранных, переходы к каталогу и диагностике, сведения о проекте.
 * Вход/регистрация пока не реализованы: информационный диалог не выдаёт гостю фиктивный аккаунт.
 */
@Composable
fun ProfileScreen(favorites: Int, onFavorites: () -> Unit, onDiagnostics: () -> Unit) {
    var information by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().testTag("profile_screen")) {
        ScreenHeader("Профиль", "Путешествуйте в своём ритме")
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(color = Color(0xFFE5ECF3), shape = CircleShape) {
                            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                TravelIcon(Glyph.PROFILE, Modifier.size(34.dp))
                            }
                        }
                        Text(
                            "Вы смотрите как гость",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Исследуйте направления и выбирайте гостиницы. Вход будет подключён на этапе 3.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TravelMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        OutlinedButton(
                            {
                                information =
                                    "Вход и регистрация появятся вместе с JWT-авторизацией, профилем пользователя и SQL-базой каталога. В этой версии фиктивный аккаунт не создаётся."
                            },
                            Modifier.testTag("login_info"),
                        ) {
                            Text("О будущем аккаунте")
                        }
                    }
                }
            }
            item {
                ProfileAction(
                    "Избранное",
                    "$favorites сохранено в сеансе приложения",
                    Glyph.HEART,
                    "open_favorites",
                    onFavorites,
                )
            }
            item {
                ProfileAction(
                    "Диагностика системы",
                    "Реальное соединение с Ktor и PostgreSQL",
                    Glyph.INFO,
                    "open_diagnostics",
                    onDiagnostics,
                )
            }
            item {
                ProfileAction(
                    "О проекте",
                    "Версия ${BuildConfig.VERSION_NAME} · этап 2",
                    Glyph.BOOKING,
                    "about_project",
                ) {
                    information =
                        "Учебное Android-приложение «Гостиница». Kotlin, Jetpack Compose, Material 3, MVVM и Navigation Compose. В каталоге 36 вымышленных гостиниц в шести реальных городах. Каталог сейчас локальный; подключение к Ktor/PostgreSQL проверяется отдельно. Реальные платежи, брони и заказы внешним компаниям не выполняются. Изображения созданы для проекта и не представляют реальные гостиницы."
                }
            }
            item {
                DemoNote(
                    "Демонстрационный режим. Избранное восстанавливается после пересоздания экрана, но не хранится в SQL и не синхронизируется с аккаунтом."
                )
            }
            item {
                Text(
                    "Курсовая работа · Арзимуротов А. М.\nИнтерфейс и навигация",
                    color = TravelMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (information != null)
        AlertDialog(
            { information = null },
            title = { Text("Гостиница") },
            text = { Text(information!!) },
            confirmButton = { TextButton({ information = null }) { Text("Понятно") } },
        )
}

@Composable
private fun ProfileAction(
    title: String,
    caption: String,
    icon: Glyph,
    tag: String,
    onClick: () -> Unit,
) {
    Card(
        onClick,
        Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TravelIcon(icon)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(caption, color = TravelMuted, style = MaterialTheme.typography.bodySmall)
            }
            TravelIcon(Glyph.CHEVRON, Modifier.size(18.dp), TravelMuted)
        }
    }
}
