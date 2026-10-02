package ru.arzimurotov.hotel.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.arzimurotov.hotel.domain.*

/** Быстрые проверки бизнес-правил без основной SQL-базы. */
class AuthServiceTest {
    private val secret="test-only-".repeat(8)
    private class MemoryUsers : UserStore {
        val values=mutableMapOf<String,StoredUser>()
        override suspend fun byEmail(email:String)=values.values.find { it.profile.email==email }
        override suspend fun byId(id:String)=values[id]
        override suspend fun insert(user:StoredUser) { values[user.profile.id]=user }
        override suspend fun update(id:String,fullName:String,phone:String?):UserProfile {
            val old=values.getValue(id); val profile=old.profile.copy(fullName=fullName,phone=phone)
            values[id]=old.copy(profile=profile);return profile
        }
    }
    @Test fun hashUsesRandomSaltAndNeverContainsPassword() = runBlocking {
        val a=PasswordHasher.hash("Secure-test-123")
        val b=PasswordHasher.hash("Secure-test-123")
        assertNotEquals(a,b);assertTrue(a.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
        assertFalse(a.contains("Secure-test"));assertTrue(PasswordHasher.matches("Secure-test-123",a))
        assertFalse(PasswordHasher.matches("wrong",a));assertFalse(PasswordHasher.matches("any","corrupt"))
    }
    @Test fun normalizesEmailAndRegistersOnlyUser() = runBlocking {
        val store=MemoryUsers();val auth=AuthService(store,TokenService(secret))
        val u=auth.register(RegisterRequest("  Person@Example.com  ","Secure-test-123"," Иван "," +998 90 123 45 67 "))
        assertEquals("person@example.com",u.email);assertEquals("USER",u.role);assertEquals("Иван",u.fullName)
        try { auth.register(RegisterRequest(u.email,"Secure-test-123","Другой"));fail() } catch(e:ApiFailure) {assertEquals("EMAIL_EXISTS",e.code)}
    }
    @Test fun invalidInputRejected() {
        val auth=AuthService(MemoryUsers(),TokenService(secret))
        assertThrows(ApiFailure::class.java) { auth.email("not-email") }
        assertThrows(ApiFailure::class.java) { auth.profile("A",null) }
        assertThrows(ApiFailure::class.java) { auth.profile("Иван","abc") }
    }
    @Test fun loginWrongAndMissingUserHaveSameError() = runBlocking {
        val auth=AuthService(MemoryUsers(),TokenService(secret))
        auth.register(RegisterRequest("demo@example.com","Secure-test-123","Демо"))
        suspend fun error(email:String):String = try {auth.login(LoginRequest(email,"bad"));error("expected")}catch(e:ApiFailure){e.message}
        assertEquals(error("demo@example.com"),error("missing@example.com"))
    }
    @Test fun jwtValidatesExpiryIssuerAudienceAndSignature() {
        val user=UserProfile(java.util.UUID.randomUUID().toString(),"test@example.com","Тест",null,"USER")
        val tokens=TokenService(secret)
        assertEquals(user.id,tokens.subject(tokens.issue(user).token))
        assertThrows(ApiFailure::class.java) { tokens.subject(TokenService(secret,-30).issue(user).token) }
        assertThrows(ApiFailure::class.java) { tokens.subject(TokenService("other-secret-".repeat(8)).issue(user).token) }
        val foreign=JWT.create().withSubject(user.id).withIssuer("other").withAudience("other").withExpiresAt(java.time.Instant.now().plusSeconds(100)).sign(Algorithm.HMAC256(secret))
        assertThrows(ApiFailure::class.java) { tokens.subject(foreign) }
        assertThrows(ApiFailure::class.java) { tokens.subject("broken") }
    }
    @Test fun currentActivityAndRoleComeFromStore() = runBlocking {
        val store=MemoryUsers();val auth=AuthService(store,TokenService(secret))
        val u=auth.register(RegisterRequest("demo@example.com","Secure-test-123","Тест"))
        val token=auth.login(LoginRequest(u.email,"Secure-test-123")).token
        assertThrows(ApiFailure::class.java) { auth.requireAdmin(u) }
        store.values[u.id]=store.values.getValue(u.id).copy(profile=u.copy(role="ADMIN"))
        assertEquals("ADMIN",auth.authenticated("Bearer $token").role)
        store.values[u.id]=store.values.getValue(u.id).copy(active=false)
        try {auth.authenticated("Bearer $token");fail()} catch(e:ApiFailure) {assertEquals("UNAUTHORIZED",e.code)}
    }
    @Test fun limiterRejectsEleventhAttempt() {
        val limiter=AuthLimiter();repeat(10){limiter.check("local")}
        assertThrows(ApiFailure::class.java) {limiter.check("local")}
        limiter.check("other")
    }
}
