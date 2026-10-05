package ru.arzimurotov.hotel.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.arzimurotov.hotel.data.wire
import ru.arzimurotov.hotel.domain.*

/**
 * Реальный checkout: серверная цена и наличие, выбор услуг, два альтернативных способа оплаты.
 * Никакого поля номера банковской карты. Данные трансфера сохраняются только в учебном заказе.
 */
@Composable
fun CheckoutScreen(
    catalog: Catalog,
    hotel: Hotel,
    query: SearchQuery,
    kind: String,
    travel: TravelState,
    auth: AuthState,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onSubmit: (CreateBooking) -> Unit,
) {
    var selected by rememberSaveable(hotel.id) { mutableStateOf<List<String>>(emptyList()) }
    val requestKey =
        rememberSaveable(hotel.id, query.checkIn, query.checkOut, kind) {
            java.util.UUID.randomUUID().toString()
        }
    var method by rememberSaveable { mutableStateOf("ONLINE_DEMO") }
    var airport by rememberSaveable { mutableStateOf("") }
    var flight by rememberSaveable { mutableStateOf("") }
    var pickup by rememberSaveable { mutableStateOf(query.checkIn.toString() + "T12:00") }
    var phone by rememberSaveable { mutableStateOf(auth.user?.phone.orEmpty()) }
    val offer =
        travel.availability
            ?.takeIf { it.hotelId == hotel.id }
            ?.offers
            ?.firstOrNull { it.kind == kind }
    val offeredServices = travel.availability?.takeIf { it.hotelId == hotel.id }?.services.orEmpty()
    val services = offeredServices.filter { it.id in selected }
    LaunchedEffect(offeredServices) {
        selected = selected.filter { id -> offeredServices.any { it.id == id } }
    }
    val total = offer?.let {
        it.stayTotal +
            services.sumOf { s ->
                if (s.perNight) s.price * query.nights * query.rooms else s.price
            }
    }
    val currency = catalog.country(catalog.city(hotel.cityId).countryId).currency
    Column(Modifier.fillMaxSize().testTag("checkout_screen")) {
        ScreenHeader("Оформление бронирования", hotel.name, onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("${dateLabel(query)} · ${guestLabel(query)}")
            if (travel.checking) CircularProgressIndicator()
            travel.availabilityError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (offer == null) {
                Text("Нет актуального предложения. Обновите наличие или вернитесь к выбору номера.")
                OutlinedButton(onRefresh, enabled = !travel.checking) { Text("Обновить наличие") }
            } else {
                SectionTitle(
                    RoomKind.valueOf(offer.kind).title,
                    "Свободно: ${offer.availableCount}; нужно: ${query.rooms}",
                )
                Text("${money(offer.nightlyPrice,currency)} за ночь / номер")
                Text("Проживание: ${money(offer.stayTotal,currency)}")
                SectionTitle("Дополнительные услуги")
                offeredServices.forEach { s ->
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(
                            s.id in selected,
                            { checked ->
                                selected = if (checked) selected + s.id else selected - s.id
                            },
                            enabled = !travel.busy,
                            modifier = Modifier.testTag("service_${s.id}"),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(s.name)
                            Text(
                                "${money(s.price,currency)} · " +
                                    if (s.perNight) "за ночь / номер" else "один раз на заказ",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (services.any { it.code == "transfer" }) {
                    SectionTitle("Встреча в аэропорту")
                    Text(
                        "Время местное для города гостиницы. Реальная машина не вызывается.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        airport,
                        { airport = it },
                        label = { Text("Аэропорт") },
                        modifier = Modifier.fillMaxWidth().testTag("transfer_airport"),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        flight,
                        { flight = it },
                        label = { Text("Рейс") },
                        modifier = Modifier.fillMaxWidth().testTag("transfer_flight"),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        pickup,
                        { pickup = it },
                        label = { Text("ГГГГ-ММ-ДДTЧЧ:ММ") },
                        modifier = Modifier.fillMaxWidth().testTag("transfer_time"),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        phone,
                        { phone = it },
                        label = { Text("Телефон") },
                        modifier = Modifier.fillMaxWidth().testTag("transfer_phone"),
                        singleLine = true,
                    )
                }
                SectionTitle("Способ оплаты")
                listOf(
                        "ONLINE_DEMO" to "Онлайн - демонстрационная оплата",
                        "PAY_AT_HOTEL" to "Оплата в гостинице",
                    )
                    .forEach { (key, label) ->
                        val allowed =
                            if (key == "ONLINE_DEMO") offer.allowDemo else offer.allowAtHotel
                        Row {
                            RadioButton(
                                method == key,
                                { method = key },
                                enabled = allowed && !travel.busy,
                                modifier = Modifier.testTag("method_$key"),
                            )
                            Text(label, Modifier.padding(top = 12.dp))
                        }
                    }
                val zone = catalog.city(hotel.cityId).name
                Text(
                    "Заезд 14:00; выезд 12:00 ($zone). Отмена подтверждённого заказа не позднее чем за ${offer.freeCancelHours} ч до заезда.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Итого: ${money(total!!,currency)}",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag("checkout_total"),
                )
                DemoNote(
                    "Учебные гостиницы и услуги. Деньги не списываются. Онлайн-резерв действует 15 минут; при оплате в гостинице заказ сразу подтверждается."
                )
                if (auth.user == null)
                    Button(onLogin, Modifier.fillMaxWidth().testTag("checkout_login")) {
                        Text("Войти для бронирования")
                    }
                else {
                    Text("Клиент: ${auth.user.fullName} · ${auth.user.email}")
                    Button(
                        {
                            onSubmit(
                                CreateBooking(
                                    hotel.id,
                                    offer.roomTypeId,
                                    offer.rateId,
                                    query.wire(),
                                    selected.toSet(),
                                    if (services.any { it.code == "transfer" })
                                        TransferDetails(
                                            airport.trim(),
                                            flight.trim(),
                                            pickup.trim(),
                                            phone.trim(),
                                        )
                                    else null,
                                    method,
                                    total,
                                    requestKey,
                                )
                            )
                        },
                        Modifier.fillMaxWidth().testTag("booking_submit"),
                        enabled =
                            !travel.busy &&
                                (if (method == "ONLINE_DEMO") offer.allowDemo
                                else offer.allowAtHotel),
                    ) {
                        Text(if (travel.busy) "Оформляем…" else "Подтвердить бронирование")
                    }
                }
            }
            travel.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("booking_error"),
                )
                TextButton(onRefresh) { Text("Обновить расчёт") }
            }
        }
    }
}

private fun status(b: Booking) =
    when (b.status) {
        "PENDING_PAYMENT" -> "Ожидает демооплаты"
        "CONFIRMED" -> "Подтверждено"
        "COMPLETED" -> "Завершено"
        else -> if (b.cancellationReason == "EXPIRED") "Резерв истёк" else "Отменено"
    }

/**
 * Активные заказы/история приходят из SQL. Таймер информативный: окончательное решение у сервера.
 */
@Composable
fun OrdersScreen(
    auth: AuthState,
    travel: TravelState,
    onLogin: () -> Unit,
    onSearch: () -> Unit,
    onLoad: (Boolean) -> Unit,
    onPay: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRefresh: (String) -> Unit,
    onReceipt: (String) -> Unit,
    onPdfConsumed: () -> Unit,
    focusBookingId: String? = null,
    standalone: Boolean = false,
) {
    var localHtml by remember { mutableStateOf<ByteArray?>(null) }
    var history by rememberSaveable { mutableStateOf(false) }
    var handledFocus by rememberSaveable { mutableStateOf<String?>(null) }
    // После создания показываем именно активные заказы, даже если вкладка восстановила
    // прежнюю «Историю». При обычном возвращении выбор пользователя сохраняется.
    LaunchedEffect(focusBookingId) {
        if (focusBookingId != null && handledFocus != focusBookingId) {
            history = false
            handledFocus = focusBookingId
        }
    }
    var confirmCancel by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick = android.os.SystemClock.elapsedRealtime()
        }
    }
    LaunchedEffect(auth.user?.id) {
        localHtml = null
        confirmCancel = null
        if (auth.user != null) onLoad(false)
    }
    Column(Modifier.fillMaxSize().testTag("orders_screen")) {
        ScreenHeader(
            "Бронирования",
            if (standalone) "Заказы хранятся в SQLite на телефоне"
            else "Заказы хранятся в PostgreSQL",
        )
        if (auth.user == null)
            EmptyPanel(
                "Войдите в аккаунт",
                "Собственные бронирования доступны после входа.",
                actionLabel = "Войти",
                onAction = onLogin,
            )
        else
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        !history,
                        { history = false },
                        { Text("Активные") },
                        modifier = Modifier.testTag("orders_active"),
                    )
                    FilterChip(
                        history,
                        { history = true },
                        { Text("История") },
                        modifier = Modifier.testTag("orders_history"),
                    )
                    TextButton({ onLoad(false) }, enabled = !travel.busy) { Text("Обновить") }
                }
                travel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (travel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val shown =
                    travel.bookings.filter {
                        (it.status in setOf("CANCELLED", "COMPLETED")) == history
                    }
                if (shown.isEmpty() && !travel.busy)
                    EmptyPanel(
                        "Пока нет заказов",
                        "Оформите проживание в разделе поиска.",
                        actionLabel = "Найти гостиницу",
                        onAction = onSearch,
                    )
                shown.forEach { b ->
                    key(b.id) {
                        val receivedAt =
                            remember(b.serverNow) { android.os.SystemClock.elapsedRealtime() }
                        Card(Modifier.fillMaxWidth().testTag("order_${b.id}")) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SectionTitle(b.snapshot.hotelName, b.number)
                                Text(status(b), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${b.checkIn} - ${b.checkOut}; ${RoomKind.valueOf(b.snapshot.kind).title}; номера ${b.snapshot.roomNumbers.joinToString()}"
                                )
                                Text("Взрослых ${b.adults}, детей ${b.children}")
                                b.snapshot.services.forEach {
                                    Text("${it.name}: ${money(it.total,b.currency)}")
                                    it.transfer?.let { t ->
                                        Text("${t.airport}, ${t.flight}, ${t.pickupAt}")
                                    }
                                }
                                Text(
                                    money(b.total, b.currency),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    when (b.paymentStatus) {
                                        "PAID" -> "Демооплата выполнена"
                                        "REVERSED" ->
                                            "Демооплата аннулирована (не банковский возврат)"
                                        else ->
                                            if (b.paymentMethod == "PAY_AT_HOTEL")
                                                "Оплата в гостинице - ещё не оплачено"
                                            else "Не оплачено"
                                    }
                                )
                                val now =
                                    b.serverNow + ((tick - receivedAt) / 1000).coerceAtLeast(0)
                                if (b.status == "PENDING_PAYMENT") {
                                    val left = ((b.expiresAt ?: now) - now).coerceAtLeast(0)
                                    Text(
                                        "Резерв: ${left/60}:${(left%60).toString().padStart(2,'0')}",
                                        Modifier.testTag("reserve_timer"),
                                    )
                                    if (left > 0)
                                        Button(
                                            { onPay(b.id) },
                                            enabled = !travel.busy,
                                            modifier = Modifier.testTag("pay_${b.id}"),
                                        ) {
                                            Text("Выполнить демооплату")
                                        }
                                    else
                                        TextButton({ onRefresh(b.id) }, enabled = !travel.busy) {
                                            Text("Проверить истёкший резерв")
                                        }
                                }
                                if (
                                    b.status == "PENDING_PAYMENT" ||
                                        b.status == "CONFIRMED" && now < b.cancelUntil
                                )
                                    OutlinedButton(
                                        { confirmCancel = b.id },
                                        enabled = !travel.busy,
                                        modifier = Modifier.testTag("cancel_${b.id}"),
                                    ) {
                                        Text("Отменить бронирование")
                                    }
                                b.receiptId?.let { id ->
                                    OutlinedButton(
                                        { onReceipt(id) },
                                        enabled = !travel.busy,
                                        modifier = Modifier.testTag("receipt_${b.id}"),
                                    ) {
                                        Text(
                                            if (standalone) "Подтверждение демооплаты"
                                            else "PDF-подтверждение"
                                        )
                                    }
                                }
                                if (standalone && b.paymentMethod == "PAY_AT_HOTEL")
                                    OutlinedButton(
                                        {
                                            localHtml =
                                                ru.arzimurotov.hotel.data.local.ConfirmationHtml
                                                    .render(b)
                                                    .toByteArray()
                                        },
                                        modifier = Modifier.testTag("confirmation_${b.id}"),
                                    ) {
                                        Text("Подтверждение бронирования")
                                    }
                            }
                        }
                    }
                }
                if (travel.bookings.size < travel.bookingTotal)
                    TextButton({ onLoad(true) }, enabled = !travel.busy) {
                        Text("Загрузить ещё заказы")
                    }
                key(auth.user.id) {
                    if (standalone) {
                        OfflineConfirmation(travel.pdf, onPdfConsumed, auth.user.email)
                        OfflineConfirmation(localHtml, { localHtml = null }, auth.user.email)
                    } else ReceiptActions(travel.pdf, onPdfConsumed)
                }
            }
    }
    confirmCancel?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmCancel = null },
            title = { Text("Отменить заказ?") },
            text = {
                Text(
                    "Номера будут освобождены. Демооплата будет аннулирована без реального банковского возврата."
                )
            },
            confirmButton = {
                TextButton({
                    confirmCancel = null
                    onCancel(id)
                }) {
                    Text("Да, отменить")
                }
            },
            dismissButton = { TextButton({ confirmCancel = null }) { Text("Оставить") } },
        )
    }
}

/**
 * SAF сохраняет PDF без разрешения на всё хранилище. Печать использует системный диалог Android.
 */
@Composable
private fun ReceiptActions(bytes: ByteArray?, consumed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Навигация может оборачивать LocalContext в ContextWrapper. PrintManager из такого
    // контекста не привязан к Activity и падает: "Can print only from an activity".
    val activity = LocalActivity.current
    var document by remember { mutableStateOf<ByteArray?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val save =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/pdf")
        ) { uri ->
            if (uri != null)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use {
                                it.write(requireNotNull(document))
                            } ?: error("No output")
                        }
                        notice = "Документ сохранён"
                    } catch (_: Exception) {
                        notice = "Не удалось сохранить. Повторите."
                    }
                }
        }
    LaunchedEffect(bytes) {
        if (bytes != null) {
            document = bytes
            consumed()
        }
    }
    document?.let { pdf ->
        SectionTitle("PDF готов")
        Button(
            { save.launch("Гостиница_демооплата.pdf") },
            modifier = Modifier.testTag("pdf_save"),
        ) {
            Text("Сохранить PDF")
        }
        OutlinedButton(
            {
                if (activity == null) {
                    notice = "Печать недоступна. Сохраните PDF."
                    return@OutlinedButton
                }
                val manager =
                    activity.getSystemService(android.content.Context.PRINT_SERVICE)
                        as? android.print.PrintManager
                if (manager == null) {
                    notice = "Служба печати недоступна. Сохраните PDF."
                    return@OutlinedButton
                }
                try {
                    manager.print(
                        "Гостиница - демооплата",
                        object : android.print.PrintDocumentAdapter() {
                            override fun onLayout(
                                old: android.print.PrintAttributes?,
                                new: android.print.PrintAttributes?,
                                signal: android.os.CancellationSignal?,
                                callback: LayoutResultCallback?,
                                extras: android.os.Bundle?,
                            ) {
                                if (signal?.isCanceled == true) callback?.onLayoutCancelled()
                                else
                                    callback?.onLayoutFinished(
                                        android.print.PrintDocumentInfo.Builder("Подтверждение.pdf")
                                            .setContentType(
                                                android.print.PrintDocumentInfo
                                                    .CONTENT_TYPE_DOCUMENT
                                            )
                                            .build(),
                                        true,
                                    )
                            }

                            override fun onWrite(
                                pages: Array<out android.print.PageRange>?,
                                destination: android.os.ParcelFileDescriptor?,
                                signal: android.os.CancellationSignal?,
                                callback: WriteResultCallback?,
                            ) {
                                if (signal?.isCanceled == true) callback?.onWriteCancelled()
                                else
                                    try {
                                        java.io
                                            .FileOutputStream(
                                                requireNotNull(destination).fileDescriptor
                                            )
                                            .use { it.write(pdf) }
                                        callback?.onWriteFinished(
                                            arrayOf(android.print.PageRange.ALL_PAGES)
                                        )
                                    } catch (_: Exception) {
                                        callback?.onWriteFailed("Не удалось подготовить PDF")
                                    }
                            }
                        },
                        null,
                    )
                } catch (_: IllegalStateException) {
                    notice = "Не удалось открыть печать. Сохраните PDF."
                }
            },
            modifier = Modifier.testTag("pdf_print"),
        ) {
            Text("Печать")
        }
    }
    notice?.let { Text(it) }
}
