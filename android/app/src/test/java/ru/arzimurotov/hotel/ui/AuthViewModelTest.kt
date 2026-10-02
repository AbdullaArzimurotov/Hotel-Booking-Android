package ru.arzimurotov.hotel.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import ru.arzimurotov.hotel.data.*
import ru.arzimurotov.hotel.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){Dispatchers.resetMain()}
    private class Fake : AuthRepository {
        var user:UserProfile?=null
        var logins=0;var registrations=0;var fail=false
        val expired=MutableStateFlow(0)
        override val expirations=expired
        override suspend fun restore():UserProfile? {if(fail) throw java.io.IOException();return user}
        override suspend fun register(request:RegisterRequest):UserProfile {registrations++;return UserProfile("id",request.email,request.fullName,request.phone,"USER")}
        override suspend fun login(request:LoginRequest):UserProfile {logins++;return UserProfile("id",request.email,"Тест",null,"USER").also{user=it}}
        override suspend fun update(request:ProfilePatch)=user!!.copy(fullName=request.fullName,phone=request.phone).also{user=it}
        override suspend fun summary()=AdminSummary(3,6,36,432,30,1)
        override suspend fun logout(){user=null}
    }
    @Test fun guestLoginEditLogoutAndDuplicateTap()=runTest(dispatcher) {
        val repo=Fake();val vm=AuthViewModel(repo);advanceUntilIdle();assertNull(vm.state.value.user)
        vm.login("test@example.com","test-password");vm.login("test@example.com","test-password");advanceUntilIdle()
        assertEquals(1,repo.logins);assertEquals("test@example.com",vm.state.value.user!!.email)
        vm.update("Новое имя","+998901234567");advanceUntilIdle();assertEquals("Новое имя",vm.state.value.user!!.fullName)
        vm.logout();advanceUntilIdle();assertNull(vm.state.value.user)
    }
    @Test fun mismatchedPasswordDoesNotSendRequest()=runTest(dispatcher) {
        val repo=Fake();val vm=AuthViewModel(repo);advanceUntilIdle()
        vm.register("Тест","test@example.com","","one-password","other-password");advanceUntilIdle()
        assertEquals(0,repo.registrations);assertTrue(vm.state.value.fields.containsKey("confirm"))
    }
    @Test fun networkFailureDoesNotInventAccountAndRetryWorks()=runTest(dispatcher) {
        val repo=Fake().apply{fail=true};val vm=AuthViewModel(repo);advanceUntilIdle()
        assertNull(vm.state.value.user);assertNotNull(vm.state.value.message)
        repo.fail=false;vm.refresh();advanceUntilIdle();assertNull(vm.state.value.message)
    }
    @Test fun expiredSessionReturnsGuest()=runTest(dispatcher) {
        val repo=Fake();val vm=AuthViewModel(repo);advanceUntilIdle()
        vm.login("test@example.com","test-password");advanceUntilIdle()
        repo.expired.value=1;advanceUntilIdle();assertNull(vm.state.value.user);assertNotNull(vm.state.value.message)
    }
}
