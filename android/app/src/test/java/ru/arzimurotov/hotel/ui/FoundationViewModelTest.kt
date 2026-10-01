package ru.arzimurotov.hotel.ui

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.arzimurotov.hotel.domain.HealthRepository
import ru.arzimurotov.hotel.domain.SystemHealth

@OptIn(ExperimentalCoroutinesApi::class)
class FoundationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun `successful probe becomes ready`() = runTest(dispatcher) {
        val model = FoundationViewModel(object : HealthRepository {
            override suspend fun check() = SystemHealth(true, true, "0.1.0")
        })
        assertEquals(FoundationState.Loading, model.state.value)
        advanceUntilIdle()
        val ready = model.state.value as FoundationState.Ready
        assertTrue(ready.health.databaseConnected)
    }

    @Test fun `network failure is recoverable by retry`() = runTest(dispatcher) {
        var fail = true
        var requests = 0
        val model = FoundationViewModel(object : HealthRepository {
            override suspend fun check(): SystemHealth {
                requests++
                if (fail) throw IOException("offline")
                return SystemHealth(true, true, "0.1.0")
            }
        })
        advanceUntilIdle()
        assertEquals(FoundationState.Unavailable, model.state.value)
        fail = false
        model.refresh()
        model.refresh()
        advanceUntilIdle()
        assertEquals(2, requests)
        assertTrue(model.state.value is FoundationState.Ready)
    }

    @Test fun `database unavailable is distinct from network failure`() = runTest(dispatcher) {
        val model = FoundationViewModel(object : HealthRepository {
            override suspend fun check() = SystemHealth(true, false, "0.1.0")
        })
        advanceUntilIdle()
        assertFalse((model.state.value as FoundationState.Ready).health.databaseConnected)
    }
}
