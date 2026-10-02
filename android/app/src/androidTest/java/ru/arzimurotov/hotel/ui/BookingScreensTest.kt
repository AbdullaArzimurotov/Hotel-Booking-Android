package ru.arzimurotov.hotel.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import ru.arzimurotov.hotel.domain.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.LocalActivity

/** Детерминированные UI-тесты коммерческих экранов. Реальные платежи/личный телефон не затрагиваются. */
class BookingScreensTest {
    @get:Rule val compose=createComposeRule()
    private val catalog=SeedCatalog.createCatalog()
    private val hotel=catalog.hotels.first {it.legacyId=="tashkent-5"}
    private val query=SearchQuery()
    private val offer=AvailableOffer("type","rate","STANDARD",2,22,10000,30000,"UZS",4,true,true,24)
    private val availability=Availability(hotel.id,listOf(offer),100,hotel.services)
    private val user=UserProfile("u","user@example.test","Тестовый клиент",null,"USER")
    @Test fun checkoutRequiresAccountAndHasNoCardFields() {
        compose.setContent {HotelTheme {CheckoutScreen(catalog,hotel,query,"STANDARD",TravelState(availability=availability),AuthState(),{},{},{},{})}}
        compose.onNodeWithTag("checkout_login").performScrollTo().assertExists()
        compose.onNodeWithTag("booking_submit").assertDoesNotExist()
        compose.onNodeWithText("Номер банковской карты").assertDoesNotExist()
    }
    @Test fun submitUsesServerOfferAndSelectedPaymentMethod() {
        var request:CreateBooking?=null
        compose.setContent {HotelTheme {CheckoutScreen(catalog,hotel,query,"STANDARD",TravelState(availability=availability),AuthState(user=user),{},{},{},{request=it})}}
        compose.onNodeWithTag("method_PAY_AT_HOTEL").performScrollTo().performClick()
        compose.onNodeWithTag("booking_submit").performScrollTo().performClick()
        compose.runOnIdle {assertEquals("PAY_AT_HOTEL",request!!.paymentMethod);assertEquals("rate",request!!.rateId);assertEquals(30000L,request!!.expectedTotal)}
    }
    @Test fun serverErrorAndBusyStateCannotSubmitAgain() {
        compose.setContent {HotelTheme {CheckoutScreen(catalog,hotel,query,"STANDARD",TravelState(availability=availability,busy=true,error="Цена изменилась"),AuthState(user=user),{},{},{},{})}}
        compose.onNodeWithTag("booking_submit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("booking_error").performScrollTo().assertTextContains("Цена изменилась")
    }
    @Test fun orderShowsDemoStatusAndHistoricalReceipt() {
        val b=Booking("b","HC-test","2026-12-01","2026-12-04",2,0,"CANCELLED","ONLINE_DEMO","REVERSED",30000,"UZS",null,2000,1,"USER",BookingSnapshot(hotel.name,hotel.address,user.fullName,user.email,"STANDARD",listOf("101"),10000,30000,emptyList(),"Asia/Tashkent"),receiptId="receipt",serverNow=100)
        compose.setContent {HotelTheme {OrdersScreen(AuthState(user=user),TravelState(bookings=listOf(b)),{},{},{},{},{},{},{},{})}}
        compose.onNodeWithTag("orders_history").performClick()
        compose.onNodeWithTag("receipt_b").performScrollTo().assertExists()
        compose.onNodeWithTag("pay_b").assertDoesNotExist()
        compose.onNodeWithText("Демооплата аннулирована (не банковский возврат)").assertExists()
    }
    @Test fun printWithoutActivityShowsHintInsteadOfCrashing() {
        compose.setContent {CompositionLocalProvider(LocalActivity provides null) {HotelTheme {
            OrdersScreen(AuthState(user=user),TravelState(pdf="%PDF-test".toByteArray()),{},{},{},{},{},{},{},{})
        }}}
        compose.onNodeWithTag("pdf_print").performScrollTo().performClick()
        compose.onNodeWithText("Печать недоступна. Сохраните PDF.").performScrollTo().assertExists()
    }
    @Test fun newOrderFocusRestoresActiveTabInsteadOfOldHistory() {
        val focus=mutableStateOf("previous")
        val b=Booking("b","HC-new","2026-12-01","2026-12-04",2,0,"CONFIRMED","PAY_AT_HOTEL","UNPAID",30000,"UZS",null,2000,1,null,BookingSnapshot(hotel.name,hotel.address,user.fullName,user.email,"STANDARD",listOf("101"),10000,30000,emptyList(),"Asia/Tashkent"),serverNow=100)
        compose.setContent {HotelTheme {OrdersScreen(AuthState(user=user),TravelState(bookings=listOf(b)),{},{},{},{},{},{},{},{},focus.value)}}
        compose.onNodeWithTag("orders_history").performClick()
        compose.onNodeWithTag("order_b").assertDoesNotExist()
        compose.runOnIdle {focus.value="newly-created"}
        compose.onNodeWithTag("order_b").assertExists()
        compose.onNodeWithTag("orders_active").assertIsSelected()
    }
}
