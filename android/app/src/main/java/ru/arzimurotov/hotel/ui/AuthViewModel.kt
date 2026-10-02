package ru.arzimurotov.hotel.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.arzimurotov.hotel.data.*
import ru.arzimurotov.hotel.domain.*

/** user=null означает Guest. Загруженный профиль не выдумывается и не подменяется offline-копией. */
data class AuthState(val user: UserProfile? = null, val busy: Boolean = false, val message: String? = null,
                     val fields: Map<String,String> = emptyMap(), val summary: AdminSummary? = null)

/** Единственная операция записи одновременно; пароль живёт только в аргументе запроса.
 * ViewModel переживает пересоздание Activity, но пароль не записывается в SavedState/Bundle. */
@HiltViewModel class AuthViewModel @Inject constructor(private val repository: AuthRepository) : ViewModel() {
    private val mutable=MutableStateFlow(AuthState())
    val state=mutable.asStateFlow()
    private var operation: Job? = null
    init {
        refresh()
        viewModelScope.launch {
            repository.expirations.collect { if(it>0) mutable.value=AuthState(message="Сеанс завершён. Войдите снова.") }
        }
    }
    private fun action(block: suspend () -> Unit) {
        if(operation?.isActive==true) return
        operation=viewModelScope.launch {
            mutable.update { it.copy(busy=true,message=null,fields=emptyMap()) }
            try { block() }
            catch(e: CancellationException) { throw e }
            catch(e: AuthProblem) { mutable.update { it.copy(message=e.error.message,fields=e.error.fieldErrors) } }
            catch(_: Exception) { mutable.update { it.copy(message="Нет связи с сервером. Проверьте подключение и повторите.") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun refresh() = action { mutable.update { it.copy(user=repository.restore(),summary=null) } }
    fun login(email: String,password: String) = action {
        val user=repository.login(LoginRequest(email,password))
        mutable.value=AuthState(user=user,busy=true,message="Вы вошли в аккаунт.")
    }
    fun register(name: String,email: String,phone: String,password: String,confirm: String) = action {
        if(password!=confirm) throw AuthProblem(ApiError("VALIDATION","Пароли не совпадают.",mapOf("confirm" to "Повторите тот же пароль.")))
        repository.register(RegisterRequest(email,password,name,phone.takeIf { it.isNotBlank() }))
        mutable.update { it.copy(message="Аккаунт создан. Войдите с вашим email и паролем.") }
    }
    fun update(name: String,phone: String) = action {
        val user=repository.update(ProfilePatch(name,phone.takeIf { it.isNotBlank() }))
        mutable.update { it.copy(user=user,message="Профиль сохранён.") }
    }
    fun summary() = action { val data=repository.summary(); mutable.update { it.copy(summary=data) } }
    fun logout() = action { repository.logout(); mutable.value=AuthState(busy=true,message="Вы вышли из аккаунта.") }
    /** При смене источника отменяем прежний запрос до удаления token и профиля. */
    fun reset() {
        operation?.cancel()
        operation=viewModelScope.launch { repository.logout(); mutable.value=AuthState() }
    }
}
