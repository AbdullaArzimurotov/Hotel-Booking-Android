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
                    "Пустой список не имитирует историю аккаунта. Вход уже работает; SQL-заказы разрабатываются на следующих этапах."
                )
            }
        }
    }
}
