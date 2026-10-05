package ru.arzimurotov.hotel.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Код показывается только при создании/ротации, не включается в логи или Bundle. */
@Composable
fun RecoveryNotice(code: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Сохраните код восстановления") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Он нужен для восстановления пароля без интернета. Старый код после смены пароля недействителен."
                )
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(code, Modifier.testTag("recovery_code"))
                }
                OutlinedButton({
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager)
                        .setPrimaryClip(
                            android.content.ClipData.newPlainText("Код восстановления", code)
                        )
                }) {
                    Text("Скопировать код")
                }
                Text("Сохраните код вне приложения. Без него восстановление невозможно.")
            }
        },
        confirmButton = {
            TextButton(onDismiss, Modifier.testTag("recovery_saved")) { Text("Я сохранил код") }
        },
    )
}

@Composable
fun PasswordScreen(
    state: AuthState,
    reset: Boolean,
    onBack: () -> Unit,
    onChange: (String, String, String) -> Unit,
    onReset: (String, String, String, String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var old by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().testTag("password_screen")) {
        ScreenHeader(
            if (reset) "Восстановление пароля" else "Смена пароля",
            "Локальный аккаунт устройства",
            onBack,
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (reset) {
                OutlinedTextField(
                    email,
                    { email = it },
                    label = { Text("Email аккаунта") },
                    modifier = Modifier.fillMaxWidth().testTag("reset_email"),
                    singleLine = true,
                )
                OutlinedTextField(
                    code,
                    { code = it },
                    label = { Text("Сохранённый код восстановления") },
                    modifier = Modifier.fillMaxWidth().testTag("reset_code"),
                    singleLine = true,
                )
            } else
                OutlinedTextField(
                    old,
                    { old = it },
                    label = { Text("Текущий пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("password_old"),
                    singleLine = true,
                )
            OutlinedTextField(
                next,
                { next = it },
                label = { Text("Новый пароль (10–128 символов)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("password_new"),
                singleLine = true,
            )
            OutlinedTextField(
                confirm,
                { confirm = it },
                label = { Text("Повторите новый пароль") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("password_confirm"),
                singleLine = true,
            )
            state.message?.let { Text(it) }
            Button(
                {
                    if (reset) onReset(email, code, next, confirm) else onChange(old, next, confirm)
                    old = ""
                    next = ""
                    confirm = ""
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("password_submit"),
            ) {
                Text(if (reset) "Восстановить" else "Изменить пароль")
            }
            DemoNote(
                "Почта не участвует в offline-восстановлении. Только сохранённый индивидуальный код; универсального обходного пароля нет."
            )
        }
    }
}
