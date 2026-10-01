package ru.arzimurotov.hotel.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.arzimurotov.hotel.data.DemoCatalogRepository
import ru.arzimurotov.hotel.domain.*

/** Real Compose navigation, no network dependency or fabricated bookings. */
class HotelAppTest {
    @get:Rule val compose = createComposeRule()
    private var latest =
        BrowseState(catalog = DemoCatalogRepository.createCatalog(), loading = false)

    private fun app(initial: BrowseState = latest) {
        compose.setContent {
            var state by remember { mutableStateOf(initial) }
            latest = state
            HotelTheme {
                HotelApp(
                    state,
                    { state = state.copy(query = it) },
                    { id ->
                        state =
                            state.copy(
                                favorites =
                                    if (id in state.favorites) state.favorites - id
                                    else state.favorites + id
                            )
                    },
                    {
                        state =
                            state.copy(
                                loading = false,
                                error = false,
                                catalog = DemoCatalogRepository.createCatalog(),
                            )
                    },
                    FoundationState.Unavailable,
                    {},
                )
            }
        }
    }

    private fun results() {
        compose.onNodeWithTag("search_submit").performScrollTo().performClick()
        compose.onNodeWithTag("results_screen").assertExists()
    }

    @Test
    fun fourTabsNavigateWithoutCreatingOrders() {
        app()
        compose.onNodeWithTag("nav_places").performClick()
        compose.onNodeWithTag("places_screen").assertExists()
        compose.onNodeWithTag("nav_bookings").performClick()
        compose.onNodeWithTag("bookings_screen").assertExists()
        compose.onNodeWithText("Ваши поездки будут здесь").assertIsDisplayed()
        compose.onNodeWithTag("nav_profile").performClick()
        compose.onNodeWithTag("profile_screen").assertExists()
        compose.onNodeWithTag("open_diagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("connectionStatus").assertTextEquals("Нет соединения с системой")
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("nav_search").performClick()
        compose.onNodeWithTag("search_screen").assertExists()
    }

    @Test
    fun destinationGuestsAndDatesAreEditable() {
        app()
        compose.onNodeWithTag("destination").performScrollTo().performClick()
        compose.onNodeWithTag("country_tr").performClick()
        compose.onNodeWithTag("city_antalya").performClick()
        compose.onNodeWithTag("dialog_confirm").performClick()
        compose.onNodeWithTag("guests").performScrollTo().performClick()
        compose.onNodeWithTag("adults_plus").performClick()
        compose.onNodeWithTag("dialog_confirm").performClick()
        compose.runOnIdle {
            assertEquals("antalya", latest.query.cityId)
            assertEquals(3, latest.query.adults)
        }
        compose.onNodeWithTag("dates").performScrollTo().performClick()
        compose.onNodeWithTag("dialog_confirm").assertIsEnabled().performClick()
        results()
        compose.onNodeWithTag("results_grid").performScrollToNode(hasTestTag("hotel_antalya-6"))
        compose.onNodeWithTag("hotel_antalya-6").assertExists()
    }

    @Test
    fun filtersSortAndEmptyResultRecoveryWork() {
        app()
        results()
        compose.onNodeWithTag("filters_open").performScrollTo().performClick()
        compose.onNodeWithTag("stars_5").performClick()
        compose.onNodeWithTag("dialog_confirm").performClick()
        compose.runOnIdle { assertEquals(setOf(5), latest.query.filters.stars) }
        compose.onNodeWithTag("sort_open").performClick()
        compose.onNodeWithTag("sort_PRICE").performClick()
        compose.runOnIdle { assertEquals(SortOrder.PRICE, latest.query.sort) }
        compose.onNodeWithTag("hotel_name_search").performTextInput("Нет такой гостиницы")
        compose.onNodeWithTag("results_grid").performScrollToNode(hasText("Ничего не найдено"))
        compose.onNodeWithText("Ничего не найдено").assertIsDisplayed()
        compose.onNodeWithText("Сбросить фильтры").performClick()
        compose.runOnIdle {
            assertEquals("", latest.query.name)
            assertEquals(0, latest.query.filters.activeCount)
        }
    }

    @Test
    fun hotelGalleryFavoritesAndServiceEstimateWork() {
        app()
        results()
        compose.onNodeWithTag("results_grid").performScrollToNode(hasTestTag("hotel_tashkent-6"))
        compose.onNodeWithTag("favorite_tashkent-6").performClick()
        compose.onNodeWithTag("hotel_tashkent-6").performClick()
        compose.onNodeWithTag("hotel_detail").assertExists()
        compose.onNodeWithTag("hotel_gallery").performTouchInput { click(center) }
        compose.onNodeWithTag("gallery_screen").assertExists()
        compose.onNodeWithTag("full_gallery").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("hotel_detail_list").performScrollToNode(hasTestTag("room_STANDARD"))
        compose.onNodeWithTag("room_STANDARD").performClick()
        compose.onNodeWithTag("quote_screen").assertExists()
        compose.onNodeWithTag("service_transfer").performScrollTo().performClick()
        compose.onNodeWithTag("quote_list").performScrollToNode(hasTestTag("quote_total"))
        compose.onNodeWithTag("quote_total").assertTextEquals("5 175 000 сум")
        compose.onNodeWithTag("booking_info").performScrollTo().performClick()
        compose.onNodeWithText("Бронирование — следующий этап").assertIsDisplayed()
        compose.runOnIdle { assertTrue("tashkent-6" in latest.favorites) }
    }

    @Test
    fun placesHaveCategoriesAndDetailsWithoutTicketSales() {
        app()
        compose.onNodeWithTag("nav_places").performClick()
        compose.onNodeWithTag("place_category_MUSEUM").performClick()
        compose.onNodeWithTag("place_tashkent-place-3").performScrollTo().performClick()
        compose.onNodeWithTag("place_detail").assertExists()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("places_screen").assertExists()
    }

    @Test
    fun catalogueFailureOffersRetry() {
        app(BrowseState(loading = false, error = true))
        compose.onNodeWithTag("catalog_error").assertExists()
        compose.onNodeWithText("Повторить").performClick()
        compose.onNodeWithTag("search_screen").assertExists()
    }

    @Test
    fun favoritesCanBeOpenedAndRemovedFromProfile() {
        app()
        results()
        compose.onNodeWithTag("results_grid").performScrollToNode(hasTestTag("favorite_tashkent-6"))
        compose.onNodeWithTag("favorite_tashkent-6").performClick()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("nav_profile").performClick()
        compose.onNodeWithTag("open_favorites").performScrollTo().performClick()
        compose.onNodeWithTag("results_grid").performScrollToNode(hasTestTag("favorite_tashkent-6"))
        compose.onNodeWithTag("favorite_tashkent-6").performClick()
        compose.onNodeWithText("Сохраните понравившиеся места").assertExists()
        compose.runOnIdle { assertTrue(latest.favorites.isEmpty()) }
    }

    @Test
    fun invalidGuestConfigurationCannotBeApplied() {
        app()
        compose.onNodeWithTag("guests").performScrollTo().performClick()
        compose.onNodeWithTag("rooms_plus").performClick()
        compose.onNodeWithTag("rooms_plus").performClick()
        compose.onNodeWithTag("dialog_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("adults_plus").performClick()
        compose.onNodeWithTag("dialog_confirm").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(3, latest.query.rooms)
            assertEquals(3, latest.query.adults)
        }
    }

    @Test
    fun unfinishedFilterSelectionSurvivesSavedStateRestoration() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        var applied: SearchQuery? = null
        restoration.setContent {
            HotelTheme { FiltersDialog(SearchQuery(), "UZS", {}, { applied = it }) }
        }
        compose.onNodeWithTag("stars_5").performClick()
        compose.onNodeWithTag("rating_9.0").performClick()
        compose.onNodeWithTag("price_filter").performTextInput("2000000")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("stars_5").assertIsSelected()
        compose.onNodeWithTag("rating_9.0").assertIsSelected()
        compose.onNodeWithTag("dialog_confirm").performClick()
        compose.runOnIdle {
            assertEquals(setOf(5), applied!!.filters.stars)
            assertEquals(9.0, applied!!.filters.minRating, 0.001)
            assertEquals(2_000_000L, applied!!.filters.maxPrice)
        }
    }
}
