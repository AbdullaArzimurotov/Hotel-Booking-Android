package ru.arzimurotov.hotel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ru.arzimurotov.hotel.data.local.*
import ru.arzimurotov.hotel.domain.Booking

data class AdminState(
    val kind: String = "hotel",
    val items: List<AdminItem> = emptyList(),
    val references: Map<String, List<AdminItem>> = emptyMap(),
    val orders: List<Booking> = emptyList(),
    val audit: List<LocalAudit> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val revision: Int = 0,
)

@HiltViewModel
class LocalAdminViewModel @Inject constructor(engine: LocalEngine) : ViewModel() {
    private val repository = LocalAdmin(engine)
    private val mutable = MutableStateFlow(AdminState())
    val state = mutable.asStateFlow()

    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: "Не удалось сохранить.") }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun loadData(kind: String) {
        val refs = AdminSchema.kinds.associate { it.key to repository.list(it.key) }
        mutable.update {
            it.copy(
                kind = kind,
                items = refs[kind].orEmpty(),
                references = refs,
                orders = if (kind == "orders") repository.orders() else emptyList(),
                audit = if (kind == "audit") repository.audit() else emptyList(),
            )
        }
    }

    fun load(kind: String = state.value.kind) = action { loadData(kind) }

    fun save(
        kind: String,
        item: AdminItem?,
        fields: Map<String, String>,
        active: Boolean,
        done: () -> Unit,
    ) = action {
        repository.save(kind, item?.id, fields, active, item?.version ?: 0)
        loadData(kind)
        mutable.update { it.copy(revision = it.revision + 1) }
        done()
    }

    fun cancel(id: String) = action {
        repository.cancel(id)
        loadData("orders")
        mutable.update { it.copy(revision = it.revision + 1) }
    }

    fun photo(context: android.content.Context, kind: String, id: String, uri: android.net.Uri) =
        action {
            val file = LocalMedia.import(context, uri)
            try {
                repository.photo(kind, id, file)
            } catch (e: Exception) {
                LocalMedia.file(context, file).delete()
                throw e
            }
            loadData(kind)
            mutable.update { it.copy(revision = it.revision + 1) }
        }

    fun firstPhoto(kind: String, id: String, file: String) = action {
        repository.photo(kind, id, file)
        loadData(kind)
        mutable.update { it.copy(revision = it.revision + 1) }
    }

    fun removePhoto(kind: String, id: String, file: String?) = action {
        repository.photo(kind, id, file, true)
        loadData(kind)
        mutable.update { it.copy(revision = it.revision + 1) }
    }
}

/** Адаптивные формы с понятными ссылками на родителей. Пароли/хеши здесь не отображаются. */
@Composable
fun LocalAdminScreen(vm: LocalAdminViewModel, onBack: () -> Unit, onChanged: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var editor by remember { mutableStateOf<AdminItem?>(null) }
    var editing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var kindsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.revision) { if (state.revision > 0) onChanged() }
    val schema = AdminSchema.kinds.find { it.key == state.kind }
    Column(Modifier.fillMaxSize().testTag("local_admin")) {
        ScreenHeader(
            "Администратор",
            if (editing) "Редактирование" else "SQL на этом устройстве",
            { if (editing) editing = false else onBack() },
        )
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {
            Text(
                it,
                Modifier.padding(12.dp).testTag("admin_error"),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (editing && schema != null)
            key(state.kind, editor?.id) {
                val fields = remember {
                    mutableStateMapOf<String, String>().apply {
                        schema.fields.forEach {
                            put(it.key, editor?.fields?.get(it.key) ?: it.default)
                        }
                    }
                }
                var active by remember { mutableStateOf(editor?.active ?: true) }
                val latest = state.items.find { it.id == editor?.id } ?: editor
                val photo =
                    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
                        uri ->
                        if (uri != null && editor != null)
                            vm.photo(context, state.kind, editor!!.id, uri)
                    }
                LazyColumn(
                    Modifier.weight(1f).imePadding(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            if (editor == null) "Новая запись" else editor!!.title,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    items(schema.fields, key = { it.key }) { field ->
                        var open by remember { mutableStateOf(false) }
                        val candidates =
                            if (field.reference != null)
                                state.references[field.reference]
                                    .orEmpty()
                                    .filter { it.active }
                                    .map { it.id to it.title }
                            else field.choices.map { it to it }
                        if (candidates.isNotEmpty() || field.reference != null) {
                            Box {
                                OutlinedButton(
                                    { open = true },
                                    Modifier.fillMaxWidth().testTag("admin_field_${field.key}"),
                                    enabled = !state.busy,
                                ) {
                                    Text(
                                        field.label +
                                            ": " +
                                            (candidates
                                                .find { it.first == fields[field.key] }
                                                ?.second ?: "Выбрать")
                                    )
                                }
                                DropdownMenu(
                                    open,
                                    { open = false },
                                    Modifier.heightIn(max = 300.dp),
                                ) {
                                    candidates.forEach { (id, title) ->
                                        DropdownMenuItem(
                                            { Text(title) },
                                            {
                                                fields[field.key] = id
                                                open = false
                                            },
                                        )
                                    }
                                }
                            }
                        } else
                            OutlinedTextField(
                                fields[field.key].orEmpty(),
                                { fields[field.key] = it },
                                label = { Text(field.label) },
                                enabled = !state.busy,
                                modifier =
                                    Modifier.fillMaxWidth().testTag("admin_field_${field.key}"),
                                minLines = if (field.key == "description") 3 else 1,
                            )
                    }
                    item {
                        Row {
                            Switch(active, { active = it }, enabled = !state.busy)
                            Text("Активна (выключить = архив)", Modifier.padding(12.dp))
                        }
                    }
                    if (state.kind in setOf("hotel", "place") && editor != null) {
                        item {
                            OutlinedButton(
                                {
                                    photo.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                enabled = !state.busy,
                                modifier = Modifier.testTag("admin_pick_photo"),
                            ) {
                                Text("Добавить фото из галереи (до 5 МиБ)")
                            }
                            Text(
                                "Новое фото становится первым. Кнопка «Первым» изменяет порядок без повторного импорта.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        items(
                            latest?.fields?.get("photoFiles").orEmpty().split(',').filter {
                                it.isNotBlank()
                            },
                            key = { it },
                        ) { file ->
                            Column {
                                TravelPhoto(
                                    ru.arzimurotov.hotel.domain.Photo.EXTERIOR,
                                    Modifier.fillMaxWidth().height(140.dp),
                                    fileName = file,
                                )
                                Row {
                                    if (state.kind == "hotel")
                                        TextButton(
                                            { vm.firstPhoto(state.kind, editor!!.id, file) },
                                            enabled = !state.busy,
                                        ) {
                                            Text("Первым")
                                        }
                                    TextButton(
                                        { vm.removePhoto(state.kind, editor!!.id, file) },
                                        enabled = !state.busy,
                                    ) {
                                        Text("Убрать фото")
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Button(
                            {
                                vm.save(state.kind, latest, fields.toMap(), active) {
                                    editing = false
                                }
                            },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().testTag("admin_save"),
                        ) {
                            Text("Сохранить")
                        }
                    }
                    item {
                        DemoNote(
                            "Черновая гостиница без типа номера и тарифа не появится в каталоге. Старые заказы не меняются."
                        )
                    }
                }
            }
        else {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton({ kindsOpen = true }, Modifier.testTag("admin_kind")) {
                        Text(
                            schema?.title
                                ?: if (state.kind == "orders") "Бронирования" else "Журнал"
                        )
                    }
                    DropdownMenu(kindsOpen, { kindsOpen = false }) {
                        (AdminSchema.kinds.map { it.key to it.title } +
                                listOf("orders" to "Бронирования", "audit" to "Журнал изменений"))
                            .forEach { (key, title) ->
                                DropdownMenuItem(
                                    { Text(title) },
                                    {
                                        vm.load(key)
                                        kindsOpen = false
                                        query = ""
                                    },
                                )
                            }
                    }
                }
                if (schema != null)
                    Button(
                        {
                            editor = null
                            editing = true
                        },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("admin_add"),
                    ) {
                        Text("Добавить")
                    }
            }
            OutlinedTextField(
                query,
                { query = it },
                label = { Text("Поиск по названию / номеру") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.items.filter { it.title.contains(query, true) }, key = { it.id }) { item
                    ->
                    Card(
                        onClick = {
                            editor = item
                            editing = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (item.active) "Активна · редактировать"
                                else "Архив · редактировать"
                            )
                        }
                    }
                }
                items(
                    state.orders.filter {
                        it.number.contains(query, true) ||
                            it.snapshot.customer.contains(query, true)
                    },
                    key = { it.id },
                ) { b ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(b.number + " · " + b.snapshot.customer)
                            Text(b.snapshot.hotelName)
                            Text("${b.checkIn} - ${b.checkOut} · ${b.status}")
                            Text(money(b.total, b.currency))
                            b.snapshot.services.forEach { Text(it.name) }
                            if (
                                b.status == "PENDING_PAYMENT" ||
                                    b.status == "CONFIRMED" && b.serverNow < b.cancelUntil
                            )
                                OutlinedButton({ vm.cancel(b.id) }, enabled = !state.busy) {
                                    Text("Отменить по правилам тарифа")
                                }
                        }
                    }
                }
                items(state.audit, key = { it.id }) { a ->
                    Text(
                        java.time.Instant.ofEpochSecond(a.createdAt).toString() +
                            "\n" +
                            a.action +
                            " · " +
                            a.target
                    )
                }
            }
        }
    }
}
