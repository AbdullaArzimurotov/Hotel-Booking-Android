package ru.arzimurotov.hotel.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import ru.arzimurotov.hotel.MainActivity

/** Интеграция Hilt ViewModel, русских ресурсов и восстановления навигации Activity. */
class MainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityRecreationPreservesResultsAndFavorite() {
        compose.onNodeWithTag("destination").performScrollTo().performClick()
        compose.onNodeWithTag("country_uz").performClick()
        compose.onNodeWithTag("city_tashkent").performClick()
        compose.onNodeWithTag("dialog_confirm").performClick()
        compose.onNodeWithTag("search_submit").performScrollTo().performClick()
        compose.onNodeWithTag("results_grid").performScrollToNode(hasTestTag("favorite_tashkent-6"))
        compose.onNodeWithTag("favorite_tashkent-6").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("results_screen").assertExists()
        compose.onNodeWithTag("results_grid").performScrollToNode(hasTestTag("favorite_tashkent-6"))
        compose.onNodeWithContentDescription("Убрать из избранного").assertExists()
        compose.onNodeWithTag("favorite_tashkent-6").performClick()
    }
}
