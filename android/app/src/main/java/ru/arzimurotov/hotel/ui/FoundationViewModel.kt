package ru.arzimurotov.hotel.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.arzimurotov.hotel.domain.HealthRepository
import ru.arzimurotov.hotel.domain.SystemHealth
import javax.inject.Inject

sealed interface FoundationState {
    data object Loading : FoundationState
    data class Ready(val health: SystemHealth) : FoundationState
    data object Unavailable : FoundationState
}

/** Owns connection state across recomposition and configuration changes. */
@HiltViewModel
class FoundationViewModel @Inject constructor(private val repository: HealthRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<FoundationState>(FoundationState.Loading)
    val state = mutableState.asStateFlow()
    private var checking: Job? = null

    init { refresh() }

    fun refresh() {
        if (checking?.isActive == true) return
        checking = viewModelScope.launch {
            mutableState.value = FoundationState.Loading
            try {
                mutableState.value = FoundationState.Ready(repository.check())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = FoundationState.Unavailable
            }
        }
    }
}
