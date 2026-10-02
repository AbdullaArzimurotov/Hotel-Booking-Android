package ru.arzimurotov.hotel.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import ru.arzimurotov.hotel.domain.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** UI-тесты без телефона/реального пароля: формы, серверные ошибки и роли. */
class AuthScreensTest {
    @get:Rule val compose=createComposeRule()
    @Test fun loginFormSendsOnlyEnteredCredentials() {
        var submitted:Pair<String,String>?=null
        compose.setContent {HotelTheme {AuthForm(AuthState(),{e,p->submitted=e to p},{_,_,_,_,_->},{})}}
        compose.onNodeWithTag("auth_email").performTextInput("demo@example.com")
        compose.onNodeWithTag("auth_password").performTextInput("demo-password")
        compose.onNodeWithTag("auth_submit").performScrollTo().performClick()
        compose.runOnIdle {assertEquals("demo@example.com" to "demo-password",submitted)}
    }
    @Test fun registrationAndFieldErrorsAreVisible() {
        compose.setContent {HotelTheme {AuthForm(AuthState(fields=mapOf("email" to "Ошибка email")),{_,_->},{_,_,_,_,_->},{})}}
        compose.onNodeWithText("Ошибка email").assertExists()
        compose.onNodeWithTag("auth_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("auth_fullName").assertExists()
        compose.onNodeWithTag("auth_confirm").assertExists()
    }
    @Test fun profileUsesApiDataAndHidesAdminForUser() {
        compose.setContent {HotelTheme {AccountProfile(AuthState(user=UserProfile("id","real@example.com","Настоящее имя",null,"USER")),0,{},{},{},{_,_->},{},{},{},{})}}
        compose.onNodeWithTag("profile_email").assertTextEquals("real@example.com")
        compose.onNodeWithTag("admin_summary").assertDoesNotExist()
        compose.onNodeWithTag("profile_save").assertIsEnabled()
    }
    @Test fun busyFormCannotSubmitAgain() {
        compose.setContent {HotelTheme {AuthForm(AuthState(busy=true),{_,_->},{_,_,_,_,_->},{})}}
        compose.onNodeWithTag("auth_submit").performScrollTo().assertIsNotEnabled()
    }
    @Test fun returningFromLoginShowsServerProfileInFields() {
        compose.setContent {
            var auth by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(AuthState()) }
            HotelTheme {
                HotelApp(BrowseState(catalog=ru.arzimurotov.hotel.data.DemoCatalogRepository.createCatalog(),loading=false),{},{},{},FoundationState.Unavailable,{},
                    auth=auth,onLogin={_,_->auth=AuthState(user=UserProfile("id","demo@example.com","Имя сервера","+998901234567","USER"))})
            }
        }
        compose.onNodeWithTag("nav_profile").performClick()
        compose.onNodeWithTag("login_info").performClick()
        compose.onNodeWithTag("auth_email").performTextInput("demo@example.com")
        compose.onNodeWithTag("auth_password").performTextInput("demo-password")
        compose.onNodeWithTag("auth_submit").performScrollTo().performClick()
        compose.onNodeWithTag("auth_fullName").assertTextContains("Имя сервера")
        compose.onNodeWithTag("auth_phone").assertTextContains("+998901234567")
    }
}
