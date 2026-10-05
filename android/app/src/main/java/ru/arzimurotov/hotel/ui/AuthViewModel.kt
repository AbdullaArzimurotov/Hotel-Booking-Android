package ru.arzimurotov.hotel.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.arzimurotov.hotel.data.*
import ru.arzimurotov.hotel.domain.*

/**
 * user=null означает Guest. Загруженный профиль не выдумывается и не подменяется offline-копией.
 */
data class AuthState(
    val user: UserProfile? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val fields: Map<String, String> = emptyMap(),
    val summary: AdminSummary? = null,
    val recoveryCode: String? = null,
    val welcomeHtml: String? = null,
)

/**
 * Единственная операция записи одновременно; пароль живёт только в аргументе запроса. ViewModel
 * переживает пересоздание Activity, но пароль не записывается в SavedState/Bundle.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(private val repository: AuthRepository) : ViewModel() {
    private val mutable = MutableStateFlow(AuthState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            repository.expirations.collect {
                if (it > 0) mutable.value = AuthState(message = "Сеанс завершён. Войдите снова.")
            }
        }
    }

    private fun action(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null, fields = emptyMap()) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthProblem) {
                mutable.update { it.copy(message = e.error.message, fields = e.error.fieldErrors) }
            } catch (_: Exception) {
                mutable.update {
                    it.copy(message = "Не удалось выполнить операцию. Повторите попытку.")
                }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun refresh() = action {
        val user = repository.restore()
        mutable.update {
            it.copy(
                user = user,
                summary = null,
                recoveryCode = user?.let { repository.takeRecovery(it.id) },
            )
        }
    }

    fun login(email: String, password: String) = action {
        val user = repository.login(LoginRequest(email, password))
        mutable.value =
            AuthState(
                user = user,
                busy = true,
                message = "Вы вошли в аккаунт.",
                recoveryCode = repository.takeRecovery(user.id),
            )
    }

    fun register(name: String, email: String, phone: String, password: String, confirm: String) =
        action {
            if (password != confirm)
                throw AuthProblem(
                    ApiError(
                        "VALIDATION",
                        "Пароли не совпадают.",
                        mapOf("confirm" to "Повторите тот же пароль."),
                    )
                )
            repository.register(
                RegisterRequest(email, password, name, phone.takeIf { it.isNotBlank() })
            )
            mutable.update {
                it.copy(
                    message = "Аккаунт создан. Войдите с вашим email и паролем.",
                    welcomeHtml =
                        ru.arzimurotov.hotel.data.local.ConfirmationHtml.welcome(name, email),
                )
            }
        }

    fun update(name: String, phone: String) = action {
        val user = repository.update(ProfilePatch(name, phone.takeIf { it.isNotBlank() }))
        mutable.update { it.copy(user = user, message = "Профиль сохранён.") }
    }

    fun summary() = action {
        val data = repository.summary()
        mutable.update { it.copy(summary = data) }
    }

    fun changePassword(old: String, new: String, confirm: String) = action {
        if (new != confirm) throw AuthProblem(ApiError("VALIDATION", "Пароли не совпадают."))
        val code = repository.changePassword(old, new)
        mutable.update {
            it.copy(
                recoveryCode = code,
                message = "Пароль изменён. Сохраните новый код восстановления.",
            )
        }
    }

    fun resetPassword(email: String, code: String, new: String, confirm: String) = action {
        if (new != confirm) throw AuthProblem(ApiError("VALIDATION", "Пароли не совпадают."))
        val replacement = repository.resetPassword(email, code, new)
        mutable.value =
            AuthState(
                busy = true,
                recoveryCode = replacement,
                message = "Пароль восстановлен. Войдите с новым паролем.",
            )
    }

    fun consumeRecovery() {
        repository.acknowledgeRecovery()
        mutable.update { it.copy(recoveryCode = null) }
    }

    fun logout() = action {
        repository.logout()
        mutable.value = AuthState(busy = true, message = "Вы вышли из аккаунта.")
    }

    /** При смене источника отменяем прежний запрос до удаления token и профиля. */
    fun reset() {
        operation?.cancel()
        operation = viewModelScope.launch {
            repository.logout()
            mutable.value = AuthState()
        }
    }
}
