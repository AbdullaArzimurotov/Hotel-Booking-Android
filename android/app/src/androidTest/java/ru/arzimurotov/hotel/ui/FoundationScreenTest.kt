package ru.arzimurotov.hotel.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.arzimurotov.hotel.domain.SystemHealth

class FoundationScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readyScreenShowsDatabaseAndAllowsRetry() {
        var retries = 0
        compose.setContent { HotelTheme {
            FoundationScreen(FoundationState.Ready(SystemHealth(true, true, "0.1.0"))) { retries++ }
        } }
        compose.onNodeWithTag("connectionStatus").assertTextEquals("Система готова")
        compose.onNodeWithText("База PostgreSQL").assertIsDisplayed()
        compose.onNodeWithTag("checkConnection").performScrollTo().performClick()
        assertEquals(1, retries)
    }

    @Test fun loadingPreventsDuplicateRequests() {
        compose.setContent { HotelTheme { FoundationScreen(FoundationState.Loading) {} } }
        compose.onNodeWithTag("checkConnection").assertIsNotEnabled()
    }

    @Test fun offlineScreenOffersRecovery() {
        compose.setContent { HotelTheme { FoundationScreen(FoundationState.Unavailable) {} } }
        compose.onNodeWithTag("connectionStatus").assertTextEquals("Нет соединения с системой")
        compose.onNodeWithTag("checkConnection").assertIsEnabled()
    }
}
