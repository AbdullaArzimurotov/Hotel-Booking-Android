package ru.arzimurotov.hotel.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import ru.arzimurotov.hotel.MainActivity
import ru.arzimurotov.hotel.domain.SeedCatalog

/** Интеграция Hilt ViewModel, русских ресурсов и восстановления навигации Activity. */
class MainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityRecreationPreservesResultsAndFavorite() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("search_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("destination").performScrollTo().performClick()
        compose.onNodeWithTag("country_${SeedCatalog.uuid("uz")}").performClick()
        compose.onNodeWithTag("city_${SeedCatalog.uuid("tashkent")}").performClick()
        compose.onNodeWithTag("dialog_confirm").performClick()
        compose.onNodeWithTag("search_submit").performScrollTo().performClick()
        val favorite="favorite_${SeedCatalog.uuid("tashkent-6")}"
        compose.waitUntil(20000) {compose.onAllNodesWithTag("server_search_success").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("results_grid").performScrollToNode(hasTestTag(favorite))
        compose.onNodeWithTag(favorite).performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20000) {compose.onAllNodesWithTag("server_search_success").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("results_screen").assertExists()
        compose.onNodeWithTag("results_grid").performScrollToNode(hasTestTag(favorite))
        compose.onNodeWithContentDescription("Убрать из избранного").assertExists()
        compose.onNodeWithTag(favorite).performClick()
    }
}
