package ru.arzimurotov.hotel.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import ru.arzimurotov.hotel.BuildConfig

/** Форма аккаунта не сохраняет пароль через rememberSaveable. Видимость доступна явно;
 * busy блокирует повторную отправку. Серверные fieldErrors показаны у нужного поля. */
@Composable fun AuthForm(state: AuthState, onLogin: (String,String)->Unit,
                        onRegister: (String,String,String,String,String)->Unit,
                        onBack: ()->Unit) {
    var registration by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("auth_screen")) {
        ScreenHeader(if(registration) "Создать аккаунт" else "Добро пожаловать", "Ваш профиль путешественника",onBack=onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(if(registration) "Сохраните контактные данные для будущих поездок." else "Войдите в ваш аккаунт «Гостиница».",style=MaterialTheme.typography.titleMedium)
            if(registration) AuthField(name,{name=it},"Имя","fullName",state.fields["fullName"],state.busy)
            AuthField(email,{email=it},"Email","email",state.fields["email"],state.busy,KeyboardType.Email)
            if(registration) AuthField(phone,{phone=it},"Телефон (необязательно)","phone",state.fields["phone"],state.busy,KeyboardType.Phone)
            OutlinedTextField(password,{password=it},Modifier.fillMaxWidth().testTag("auth_password"),enabled=!state.busy,label={Text("Пароль")},
                singleLine=true,isError=state.fields["password"]!=null,supportingText={Text(state.fields["password"] ?: "От 10 до 128 символов")},
                visualTransformation=if(visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                trailingIcon={TextButton({visible=!visible}) { Text(if(visible) "Скрыть" else "Показать") }})
            if(registration) OutlinedTextField(confirm,{confirm=it},Modifier.fillMaxWidth().testTag("auth_confirm"),enabled=!state.busy,
                label={Text("Повторите пароль")},singleLine=true,visualTransformation=PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),isError=state.fields["confirm"]!=null)
            state.message?.let { Text(it,Modifier.testTag("auth_message"),color=MaterialTheme.colorScheme.primary) }
            Button({
                if(registration) onRegister(name,email,phone,password,confirm) else onLogin(email,password)
                password=""; confirm=""
            },Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("auth_submit"),
                enabled=!state.busy && email.isNotBlank() && password.isNotEmpty() && (!registration || (name.isNotBlank() && confirm.isNotEmpty()))) {
                if(state.busy) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                else Text(if(registration) "Создать аккаунт" else "Войти")
            }
            TextButton({registration=!registration;password="";confirm=""},Modifier.fillMaxWidth().testTag("auth_toggle"),enabled=!state.busy) {
                Text(if(registration) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться")
            }
            DemoNote("Email и SMS не подтверждаются. Заказы сохраняются в SQL; оплата демонстрационная, деньги не списываются.")
        }
    }
}

@Composable private fun AuthField(value: String, changed:(String)->Unit,label:String,key:String,error:String?,busy:Boolean,keyboard:KeyboardType=KeyboardType.Text) {
    OutlinedTextField(value,changed,Modifier.fillMaxWidth().testTag("auth_$key"),enabled=!busy,singleLine=true,label={Text(label)},isError=error!=null,
        supportingText={if(error!=null) Text(error)},keyboardOptions=KeyboardOptions(keyboardType=keyboard))
}

/** Реальный профиль, полученный GET /profile; роль — только для чтения. */
@Composable fun AccountProfile(state: AuthState,favorites:Int,onFavorites:()->Unit,onDiagnostics:()->Unit,onLogin:()->Unit,
                               onUpdate:(String,String)->Unit,onLogout:()->Unit,onRefresh:()->Unit,onAdmin:()->Unit,onConnection:()->Unit) {
    val user=state.user
    var name by rememberSaveable(user?.id,user?.fullName) { mutableStateOf(user?.fullName.orEmpty()) }
    var phone by rememberSaveable(user?.id,user?.phone) { mutableStateOf(user?.phone.orEmpty()) }
    var draftKey by rememberSaveable { mutableStateOf("") }
    // Navigation может восстановить черновик Guest после входа. Сравниваем с версией
    // серверного профиля; обычная ротация не стирает ещё не сохранённый ввод.
    LaunchedEffect(user?.id,user?.fullName,user?.phone) {
        val key=listOf(user?.id,user?.fullName,user?.phone).joinToString("|")
        if(draftKey!=key) { name=user?.fullName.orEmpty();phone=user?.phone.orEmpty();draftKey=key }
    }
    Column(Modifier.fillMaxSize().testTag("profile_screen")) {
        ScreenHeader("Профиль","Путешествуйте в своём ритме")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text(user?.fullName ?: "Вы смотрите как гость",style=MaterialTheme.typography.headlineSmall)
                    if(user==null) {
                        Text("Каталог открыт без регистрации. Войдите, чтобы управлять своим профилем.")
                        Button(onLogin,Modifier.fillMaxWidth().testTag("login_info"),enabled=!state.busy) { Text("Войти или зарегистрироваться") }
                    } else {
                        Text(user.email,Modifier.testTag("profile_email"),color=TravelMuted)
                        Text(if(user.role=="ADMIN") "Администратор" else "Пользователь",color=TravelMuted)
                        AuthField(name,{name=it},"Имя","fullName",state.fields["fullName"],state.busy)
                        AuthField(phone,{phone=it},"Телефон","phone",state.fields["phone"],state.busy,KeyboardType.Phone)
                        Button({onUpdate(name,phone)},Modifier.fillMaxWidth().testTag("profile_save"),enabled=!state.busy && name.isNotBlank()) { Text("Сохранить профиль") }
                        OutlinedButton(onLogout,Modifier.fillMaxWidth().testTag("profile_logout"),enabled=!state.busy) { Text("Выйти из аккаунта") }
                        if(user.role=="ADMIN") Button(onAdmin,Modifier.fillMaxWidth().testTag("admin_summary"),enabled=!state.busy) { Text("Сводка администратора") }
                    }
                    if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.message?.let { Text(it,Modifier.testTag("profile_message"),color=MaterialTheme.colorScheme.primary) }
                    TextButton(onRefresh,enabled=!state.busy) { Text("Обновить состояние аккаунта") }
                }
            }
            OutlinedButton(onFavorites,Modifier.fillMaxWidth().testTag("open_favorites")) { Text("Избранное · $favorites") }
            OutlinedButton(onDiagnostics,Modifier.fillMaxWidth().testTag("open_diagnostics")) { Text("Диагностика системы") }
            if(BuildConfig.DEBUG) OutlinedButton(onConnection,Modifier.fillMaxWidth().testTag("connection_settings")) { Text("Подключение: эмулятор / USB") }
            Text("Версия ${BuildConfig.VERSION_NAME} · SQL-каталог и аккаунты",style=MaterialTheme.typography.labelLarge)
            DemoNote("36 вымышленных гостиниц, 6 реальных городов. Поиск, брони и демооплата работают через сервер. Избранное хранится на устройстве; полный кабинет администратора — этап 8.")
        }
    }
}

@Composable fun AdminScreen(state:AuthState,onBack:()->Unit,onRetry:()->Unit) {
    Column(Modifier.fillMaxSize().testTag("admin_screen")) {
        ScreenHeader("Сводка администратора","Только чтение · права проверяет сервер",onBack=onBack)
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            state.summary?.let { s ->
                listOf("Страны" to s.countries,"Города" to s.cities,"Гостиницы" to s.hotels,"Физические номера" to s.rooms,"Места" to s.places,"Аккаунты" to s.users).forEach {
                    Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween) { Text(it.first);Text(it.second.toString()) } }
                }
            }
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it) }
            Button(onRetry,enabled=!state.busy) { Text("Обновить") }
            DemoNote("Редактирование каталога будет реализовано на этапе 8.")
        }
    }
}
