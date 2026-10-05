package ru.arzimurotov.hotel.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import ru.arzimurotov.hotel.MainActivity
import ru.arzimurotov.hotel.domain.SeedCatalog

/**
 * E2E через настоящую Activity, Hilt, SQLite и Keystore. До запуска runner на тестовом AVD
 * отключены Wi-Fi/mobile data: ни каталог, ни пароль, ни бронь не используют backend.
 */
class OfflineFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun wait(tag: String) =
        ui.waitUntil(20000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun click(tag: String) {
        val node = ui.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun prefix(value: String) =
        SemanticsMatcher("tag starts $value") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(value) == true
        }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        // Dialog/WebView используют отдельные Android frames, не только Compose test clock.
        android.os.SystemClock.sleep(400)
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val file =
            java.io.File(
                instrumentation.targetContext.getExternalFilesDir(null),
                "screenshots/$name.png",
            )
        file.parentFile!!.mkdirs()
        val image = instrumentation.uiAutomation.takeScreenshot()
        try {
            file.outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            image.recycle()
        }
    }

    private fun cancelActive() {
        repeat(20) {
            if (ui.onAllNodes(prefix("cancel_")).fetchSemanticsNodes().isEmpty()) return
            ui.onAllNodes(prefix("cancel_")).onFirst().performScrollTo().performClick()
            ui.onNodeWithText("Да, отменить").performClick()
            ui.waitForIdle()
        }
    }

    private fun login(email: String, password: String) {
        ui.onNodeWithTag("nav_profile").performClick()
        wait("profile_screen")
        if (ui.onAllNodesWithTag("profile_logout").fetchSemanticsNodes().isNotEmpty())
            click("profile_logout")
        ui.waitUntil(10000) {
            ui.onAllNodesWithTag("login_info").fetchSemanticsNodes().isNotEmpty()
        }
        click("login_info")
        wait("auth_screen")
        ui.onNodeWithTag("auth_email").performTextReplacement(email)
        ui.onNodeWithTag("auth_password").performTextReplacement(password)
        click("auth_submit")
        ui.waitUntil(15000) {
            ui.onAllNodesWithTag("profile_email").fetchSemanticsNodes().isNotEmpty()
        }
        if (ui.onAllNodesWithTag("recovery_saved").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithTag("recovery_saved").performClick()
        ui.onNodeWithTag("profile_email").assertTextEquals(email)
        ui.onNodeWithTag("recovery_saved").assertDoesNotExist()
    }

    private fun checkout(atHotel: Boolean = false) {
        ui.onNodeWithTag("nav_search").performClick()
        if (ui.onAllNodesWithTag("results_screen").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithTag("back").performClick()
        wait("search_screen")
        click("destination")
        click("country_${SeedCatalog.uuid("uz")}")
        click("city_${SeedCatalog.uuid("tashkent")}")
        click("dialog_confirm")
        click("search_submit")
        wait("server_search_success")
        val id = SeedCatalog.uuid("tashkent-1")
        ui.onNodeWithTag("results_grid").performScrollToNode(hasTestTag("hotel_$id"))
        ui.onNodeWithTag("hotel_$id").performClick()
        wait("hotel_detail")
        ui.onNodeWithTag("hotel_detail_list").performScrollToNode(hasTestTag("room_STANDARD"))
        ui.onNodeWithTag("room_STANDARD").performClick()
        wait("checkout_screen")
        ui.waitUntil(15000) {
            ui.onAllNodesWithTag("booking_submit").fetchSemanticsNodes().isNotEmpty()
        }
        if (atHotel) click("method_PAY_AT_HOTEL")
        click("booking_submit")
        wait("orders_screen")
        ui.waitUntil(15000) { ui.onAllNodes(prefix("order_")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun offlineClientAdminMapAndBothPaymentMethods() {
        wait("search_screen")
        screenshot("search")
        login("client@hotel.demo", "HotelClient!2026")
        screenshot("profile")
        ui.onNodeWithTag("nav_bookings").performClick()
        wait("orders_screen")
        ui.waitForIdle()
        cancelActive()
        checkout()
        ui.onAllNodes(prefix("pay_")).onFirst().performScrollTo().performClick()
        ui.waitUntil(15000) {
            ui.onAllNodesWithText("Демооплата выполнена").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("paid_order")
        ui.onAllNodes(prefix("receipt_")).onFirst().performScrollTo().performClick()
        wait("html_confirmation")
        ui.onNodeWithTag("html_confirmation").performScrollTo()
        wait("html_rendered")
        screenshot("confirmation")
        val context =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        if (
            context.packageManager
                .queryIntentActivities(
                    android.content.Intent(
                        android.content.Intent.ACTION_SENDTO,
                        android.net.Uri.parse("mailto:"),
                    ),
                    0,
                )
                .isEmpty()
        ) {
            click("html_mail")
            ui.waitUntil(10000) {
                ui.onAllNodesWithText(
                        "Почтового приложения нет. Документ сохранён в кабинете; установите Gmail/Mail.ru или сохраните HTML."
                    )
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
        click("html_close")
        ui.onAllNodes(prefix("cancel_")).onFirst().performScrollTo().performClick()
        ui.onNodeWithText("Да, отменить").performClick()
        checkout(true)
        ui.onNodeWithText("Оплата в гостинице - ещё не оплачено").assertExists()
        ui.onAllNodes(prefix("confirmation_")).onFirst().performScrollTo().performClick()
        wait("html_confirmation")
        click("html_close")
        ui.onNodeWithTag("nav_places").performClick()
        ui.onNodeWithTag("open_map").performClick()
        wait("offline_map")
        wait("map_view")
        wait("map_rendered")
        ui.waitForIdle()
        // Mapsforge рисует вне Compose. Ждём не только cache observer, но и цветные улицы.
        ui.waitUntil(20000) {
            val bmp =
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .takeScreenshot()
            try {
                var count = 0
                for (y in bmp.height / 3 until bmp.height * 3 / 4 step 8) for (x in
                    bmp.width / 6 until bmp.width * 5 / 6 step 8) {
                    val p = bmp.getPixel(x, y)
                    if (
                        android.graphics.Color.red(p) != android.graphics.Color.blue(p) &&
                            android.graphics.Color.green(p) > 100
                    )
                        count++
                }
                count > 200
            } finally {
                bmp.recycle()
            }
        }
        screenshot("map")
        ui.activityRule.scenario.recreate()
        wait("offline_map")
        wait("map_view")
        ui.onNodeWithTag("back").performClick()
        login("admin@hotel.demo", "HotelAdmin!2026")
        click("admin_summary")
        wait("local_admin")
        ui.waitUntil(15000) {
            runCatching { ui.onNodeWithTag("admin_add").assertIsEnabled() }.isSuccess
        }
        screenshot("admin")
        ui.onNodeWithTag("admin_add").performClick()
        wait("admin_field_name")
        screenshot("admin_form")
        ui.onNodeWithTag("back").performClick()
        click("admin_kind")
        ui.onNodeWithText("Страны").performClick()
        ui.waitUntil(15000) {
            runCatching { ui.onNodeWithTag("admin_add").assertIsEnabled() }.isSuccess
        }
        click("admin_add")
        val countryName = "UI-страна " + java.util.UUID.randomUUID().toString().take(8)
        ui.onNodeWithTag("admin_field_name").performTextReplacement(countryName)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.onNodeWithTag("admin_save")
            .performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        ui.waitUntil(15000) {
            ui.onAllNodesWithTag("admin_field_name").fetchSemanticsNodes().isEmpty() &&
                ui.onAllNodesWithText(countryName).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("local_admin").assertExists()
        ui.onNodeWithTag("back").performClick()
        wait("profile_screen")
        click("profile_logout")
    }
}
