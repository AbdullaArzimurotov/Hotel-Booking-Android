package ru.arzimurotov.hotel.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import ru.arzimurotov.hotel.MainActivity

/** Настоящие формы и SQL, отдельный случайный USER. Начальные demo-пароли не изменяются. */
class OfflineAccountFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun wait(tag: String) =
        ui.waitUntil(20000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun click(tag: String) {
        val n = ui.onNodeWithTag(tag)
        runCatching { n.performScrollTo() }
        n.performClick()
    }

    private fun text(tag: String, value: String) {
        ui.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    }

    private fun recovery(): String {
        wait("recovery_code")
        val code =
            ui.onNodeWithTag("recovery_code")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .first()
                .text
        click("recovery_saved")
        return code
    }

    @Test
    fun registerChangeRecoverAndLoginWithoutNetwork() {
        wait("search_screen")
        click("nav_profile")
        wait("profile_screen")
        if (ui.onAllNodesWithTag("profile_logout").fetchSemanticsNodes().isNotEmpty())
            click("profile_logout")
        wait("login_info")
        click("login_info")
        wait("auth_screen")
        click("auth_toggle")
        val email = "ui-${java.util.UUID.randomUUID()}@example.com"
        text("auth_fullName", "Учебный пользователь")
        text("auth_email", email)
        text("auth_password", "InitialPass!2026")
        text("auth_confirm", "InitialPass!2026")
        click("auth_submit")
        wait("html_confirmation")
        click("html_close")
        click("auth_toggle")
        text("auth_password", "InitialPass!2026")
        click("auth_submit")
        val original = recovery()
        wait("profile_screen")
        click("change_password")
        wait("password_screen")
        text("password_old", "InitialPass!2026")
        text("password_new", "ChangedPass!2026")
        text("password_confirm", "ChangedPass!2026")
        click("password_submit")
        val changed = recovery()
        org.junit.Assert.assertNotEquals(original, changed)
        click("back")
        click("profile_logout")
        wait("login_info")
        click("login_info")
        click("forgot_password")
        wait("password_screen")
        text("reset_email", email)
        text("reset_code", original)
        text("password_new", "RecoveredPass!2026")
        text("password_confirm", "RecoveredPass!2026")
        click("password_submit")
        ui.waitUntil(15000) {
            ui.onAllNodesWithText("Email или код восстановления неверен.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        text("reset_code", changed)
        text("password_new", "RecoveredPass!2026")
        text("password_confirm", "RecoveredPass!2026")
        click("password_submit")
        recovery()
        click("back")
        text("auth_email", email)
        text("auth_password", "RecoveredPass!2026")
        click("auth_submit")
        wait("profile_screen")
        ui.onNodeWithTag("profile_email").assertTextEquals(email)
        click("profile_logout")
    }
}
