package ru.arzimurotov.hotel.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.arzimurotov.hotel.data.*
import ru.arzimurotov.hotel.domain.*

data class TravelState(
    val search: SearchResponse? = null,
    val searching: Boolean = false,
    val searchError: String? = null,
    val availability: Availability? = null,
    val checking: Boolean = false,
    val availabilityError: String? = null,
    val bookings: List<Booking> = emptyList(),
    val bookingTotal: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val created: Booking? = null,
    val pdf: ByteArray? = null,
)

/**
 * Независимые jobs поиска и карточки не блокируют личный кабинет. Отмена coroutine не превращается
 * в сетевую ошибку; повтор кнопки оформления блокируется, идемпотентность обеспечивает сервер.
 */
@HiltViewModel
class TravelViewModel @Inject constructor(private val repository: TravelRepository) : ViewModel() {
    private val mutable = MutableStateFlow(TravelState())
    val state = mutable.asStateFlow()
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var action: Job? = null
    private var actionGeneration = 0L

    private fun message(e: Exception) =
        (e as? AuthProblem)?.error?.let {
            it.message +
                if (it.fieldErrors.isEmpty()) ""
                else "\n" + it.fieldErrors.values.joinToString("\n")
        } ?: "Не удалось выполнить операцию. Повторите попытку."

    fun search(q: SearchQuery, more: Boolean = false) {
        if (more && state.value.searching) return
        searchJob?.cancel()
        mutable.update {
            it.copy(searching = true, searchError = null, search = if (more) it.search else null)
        }
        searchJob = viewModelScope.launch {
            try {
                if (!more) delay(300)
                val old = state.value.search
                val page = repository.search(q.wire(), if (more) old?.items?.size ?: 0 else 0)
                ensureActive()
                mutable.update {
                    it.copy(
                        searching = false,
                        search =
                            if (more && old != null)
                                page.copy(
                                    items = (old.items + page.items).distinctBy { v -> v.hotelId }
                                )
                            else page,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(searching = false, searchError = message(e)) }
            }
        }
    }

    fun availability(id: String, q: SearchQuery) {
        detailJob?.cancel()
        mutable.update { it.copy(availability = null, checking = true, availabilityError = null) }
        detailJob = viewModelScope.launch {
            try {
                val a = repository.availability(id, q.wire())
                ensureActive()
                mutable.update { it.copy(availability = a, checking = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(checking = false, availabilityError = message(e)) }
            }
        }
    }

    private fun operation(block: suspend () -> Unit) {
        if (action?.isActive == true) return
        val generation = ++actionGeneration
        mutable.update { it.copy(busy = true, error = null) }
        action = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                if (generation == actionGeneration) mutable.update { it.copy(error = message(e)) }
            } finally {
                if (generation == actionGeneration) mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun loadBookings(more: Boolean = false) = operation {
        val page = repository.bookings(if (more) state.value.bookings.size else 0)
        currentCoroutineContext().ensureActive()
        mutable.update {
            it.copy(
                bookings =
                    if (more) (it.bookings + page.items).distinctBy { b -> b.id } else page.items,
                bookingTotal = page.total,
            )
        }
    }

    fun create(userId: String, r: CreateBooking) = operation {
        val b = repository.create(userId, r)
        currentCoroutineContext().ensureActive()
        mutable.update {
            it.copy(
                created = b,
                bookings = listOf(b) + it.bookings.filter { old -> old.id != b.id },
            )
        }
    }

    private fun replace(b: Booking) {
        mutable.update {
            it.copy(bookings = it.bookings.map { old -> if (old.id == b.id) b else old })
        }
    }

    fun pay(userId: String, id: String) = operation {
        val b = repository.pay(userId, id)
        currentCoroutineContext().ensureActive()
        replace(b)
    }

    fun cancel(id: String) = operation {
        val b = repository.cancel(id)
        currentCoroutineContext().ensureActive()
        replace(b)
    }

    fun refresh(id: String) = operation {
        val b = repository.booking(id)
        currentCoroutineContext().ensureActive()
        replace(b)
    }

    fun receipt(id: String) = operation {
        val bytes = repository.receipt(id)
        currentCoroutineContext().ensureActive()
        mutable.update { it.copy(pdf = bytes) }
    }

    fun consumePdf() {
        mutable.update { it.copy(pdf = null) }
    }

    fun consumeCreated() {
        mutable.update { it.copy(created = null) }
    }

    // Отменённый finally не должен сбрасывать busy новой операции другого аккаунта.
    fun clearAccount() {
        actionGeneration++
        action?.cancel()
        action = null
        mutable.update {
            it.copy(
                bookings = emptyList(),
                bookingTotal = 0,
                busy = false,
                error = null,
                created = null,
                pdf = null,
            )
        }
    }

    fun reset() {
        actionGeneration++
        searchJob?.cancel()
        detailJob?.cancel()
        action?.cancel()
        action = null
        mutable.value = TravelState()
    }
}
