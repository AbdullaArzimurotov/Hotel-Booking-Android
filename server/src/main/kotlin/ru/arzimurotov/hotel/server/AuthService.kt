package ru.arzimurotov.hotel.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import ru.arzimurotov.hotel.domain.*

/** Безопасная ошибка бизнес-правила: в HTTP никогда не попадают SQL/stack trace/секреты. */
class ApiFailure(val status: HttpStatusCode, val code: String, override val message: String, val fields: Map<String,String> = emptyMap()) : RuntimeException(message)
fun invalid(fields: Map<String,String>): Nothing = throw ApiFailure(HttpStatusCode.BadRequest,"VALIDATION","Проверьте заполнение полей.",fields)
data class StoredUser(val profile: UserProfile, val hash: String, val active: Boolean)
interface UserStore {
    suspend fun byEmail(email: String): StoredUser?
    suspend fun byId(id: String): StoredUser?
    suspend fun insert(user: StoredUser)
    suspend fun update(id: String, fullName: String, phone: String?): UserProfile
}

/** Репозиторий аккаунтов. Уникальность email гарантирует PostgreSQL, включая гонку регистрации. */
class UserRepository(private val db: DatabaseService) : UserStore {
    private fun java.sql.ResultSet.user() = StoredUser(UserProfile(getString("id"),getString("email"),getString("full_name"),getString("phone"),getString("role")),getString("password_hash"),getBoolean("active"))
    override suspend fun byEmail(email: String) = db.query { c -> c.select("SELECT * FROM users WHERE email=?",email) { it.user() }.singleOrNull() }
    override suspend fun byId(id: String) = db.query { c -> c.select("SELECT * FROM users WHERE id=?",uid(id)) { it.user() }.singleOrNull() }
    override suspend fun insert(user: StoredUser) {
        try { db.query { c -> c.execute("INSERT INTO users(id,email,password_hash,full_name,phone,role,active) VALUES(?,?,?,?,?,?,?)",uid(user.profile.id),user.profile.email,user.hash,user.profile.fullName,user.profile.phone,user.profile.role,user.active) } }
        catch(e: Exception) {
            if(generateSequence<Throwable>(e) { it.cause }.filterIsInstance<java.sql.SQLException>().any { it.sqlState=="23505" })
                throw ApiFailure(HttpStatusCode.Conflict,"EMAIL_EXISTS","Этот email уже зарегистрирован.")
            throw e
        }
    }
    override suspend fun update(id: String, fullName: String, phone: String?): UserProfile = db.query { c ->
        c.select("UPDATE users SET full_name=?,phone=? WHERE id=? AND active RETURNING *",fullName,phone,uid(id)) { it.user().profile }.singleOrNull()
            ?: throw unauthorized()
    }
}
fun unauthorized() = ApiFailure(HttpStatusCode.Unauthorized,"UNAUTHORIZED","Сеанс завершён. Войдите снова.")

/** Argon2id pure JVM: 19 MiB, t=2, p=1, случайный 16-байтный salt, 32-байтный hash.
 * PHC-строка содержит параметры, а не пароль. Сравнение постоянно по времени.
 * Хеширование выполняется на IO и дополнительно ограничивается семафором. */
object PasswordHasher {
    private val permits = kotlinx.coroutines.sync.Semaphore(2)
    private val encoder = Base64.getEncoder().withoutPadding()
    suspend fun hash(password: String): String = calculate {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        "\$argon2id\$v=19\$m=19456,t=2,p=1\$"+encoder.encodeToString(salt)+"\$"+encoder.encodeToString(derive(password,salt))
    }
    suspend fun matches(password: String, phc: String): Boolean = calculate {
        try {
            val parts=phc.split('$')
            if(parts.size!=6 || parts[1]!="argon2id" || parts[2]!="v=19" || parts[3]!="m=19456,t=2,p=1") false
            else {
                val salt=Base64.getDecoder().decode(parts[4])
                val expected=Base64.getDecoder().decode(parts[5])
                salt.size==16 && expected.size==32 && MessageDigest.isEqual(expected,derive(password,salt))
            }
        } catch(_: IllegalArgumentException) { false }
    }
    private suspend fun <T> calculate(block: () -> T): T {
        permits.acquire()
        try { return withContext(Dispatchers.IO) { block() } } finally { permits.release() }
    }
    private fun derive(password: String, salt: ByteArray): ByteArray {
        val input=password.toByteArray(StandardCharsets.UTF_8)
        return try {
            val parameters=Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(19456).withIterations(2).withParallelism(1).withSalt(salt).build()
            val generator=Argon2BytesGenerator().also { it.init(parameters) }
            ByteArray(32).also { generator.generateBytes(input,it) }
        } finally { input.fill(0) }
    }
}

/** JWT не является источником текущих прав: роль и active каждый раз читаются из SQL.
 * Секрет не имеет дефолтного значения и не выводится в toString. Refresh отсутствует. */
class TokenService(secret: String, private val lifetimeSeconds: Long = 1800) {
    init { require(secret.length >= 48) { "JWT_SECRET must have at least 48 characters" } }
    private val algorithm=Algorithm.HMAC256(secret)
    private val verifier=JWT.require(algorithm).withIssuer("hotel-coursework").withAudience("hotel-android").build()
    fun issue(user: UserProfile): AuthResponse {
        val now=Instant.now(); val expires=now.plusSeconds(lifetimeSeconds)
        val token=JWT.create().withIssuer("hotel-coursework").withAudience("hotel-android").withSubject(user.id)
            .withIssuedAt(now).withExpiresAt(expires).withJWTId(UUID.randomUUID().toString()).sign(algorithm)
        return AuthResponse(token,expires.epochSecond,user)
    }
    fun subject(token: String): String = try {
        val value=verifier.verify(token)
        require(value.expiresAtAsInstant != null && value.issuedAtAsInstant != null && value.issuedAtAsInstant <= Instant.now().plusSeconds(5))
        uid(value.subject).toString()
    } catch(_: Exception) { throw unauthorized() }
}

/** Сервис регистрации/профиля: валидация, нормализация email, только USER из публичного API. */
class AuthService(private val users: UserStore, private val tokens: TokenService) {
    private var dummy: String? = null
    private val dummyMutex = kotlinx.coroutines.sync.Mutex()
    fun email(raw: String): String {
        val email=raw.trim().lowercase(java.util.Locale.ROOT)
        if(email.length > 254 || !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email)) invalid(mapOf("email" to "Введите корректный email."))
        return email
    }
    fun profile(fullName: String, phone: String?): ProfilePatch {
        val name=fullName.trim(); val number=phone?.trim()?.takeIf { it.isNotEmpty() }
        val fields=buildMap {
            if(name.length !in 2..120 || name.any { it.isISOControl() }) put("fullName","Имя должно содержать 2–120 символов.")
            if(number!=null && (number.length !in 5..30 || !Regex("^[+0-9 ()-]+$").matches(number))) put("phone","Проверьте номер телефона.")
        }
        if(fields.isNotEmpty()) invalid(fields)
        return ProfilePatch(name,number)
    }
    suspend fun register(request: RegisterRequest, role: String = "USER"): UserProfile {
        val address=email(request.email); val patch=profile(request.fullName,request.phone)
        if(request.password.length !in 10..128) invalid(mapOf("password" to "Пароль: от 10 до 128 символов."))
        if(users.byEmail(address)!=null) throw ApiFailure(HttpStatusCode.Conflict,"EMAIL_EXISTS","Этот email уже зарегистрирован.")
        val user=StoredUser(UserProfile(UUID.randomUUID().toString(),address,patch.fullName,patch.phone,role),PasswordHasher.hash(request.password),true)
        users.insert(user); return user.profile
    }
    suspend fun login(request: LoginRequest): AuthResponse {
        if(request.email.length>254 || request.password.length>128) throw badLogin()
        val user=users.byEmail(request.email.trim().lowercase(java.util.Locale.ROOT))
        dummyMutex.lock()
        try { if(dummy==null) dummy=PasswordHasher.hash(UUID.randomUUID().toString()) } finally { dummyMutex.unlock() }
        val matches=PasswordHasher.matches(request.password,user?.hash ?: dummy!!)
        if(!matches || user?.active!=true) throw badLogin()
        return tokens.issue(user.profile)
    }
    private fun badLogin() = ApiFailure(HttpStatusCode.Unauthorized,"INVALID_CREDENTIALS","Неверный email или пароль.")
    suspend fun authenticated(header: String?): UserProfile {
        if(header==null || !header.startsWith("Bearer ") || header.length>4096) throw unauthorized()
        val user=users.byId(tokens.subject(header.removePrefix("Bearer ")))
        if(user?.active!=true) throw unauthorized()
        return user.profile
    }
    suspend fun update(user: UserProfile, request: ProfilePatch): UserProfile {
        val patch=profile(request.fullName,request.phone); return users.update(user.id,patch.fullName,patch.phone)
    }
    fun requireAdmin(user: UserProfile) {
        if(user.role!="ADMIN") throw ApiFailure(HttpStatusCode.Forbidden,"FORBIDDEN","Недостаточно прав.")
    }
}

/** 10 попыток за минуту с IP (без доверия X-Forwarded-For). Ограниченная память, безопасно
 * для локального сервера; для нескольких инстансов потребуется общий Redis limiter. */
class AuthLimiter {
    private val attempts=linkedMapOf<String,ArrayDeque<Long>>()
    @Synchronized fun check(key: String) {
        val now=System.currentTimeMillis()
        attempts.entries.removeIf { it.value.isEmpty() || it.value.last()<now-60000 }
        val queue=attempts.getOrPut(key) {
            if(attempts.size>=10000) throw ApiFailure(HttpStatusCode.TooManyRequests,"RATE_LIMIT","Повторите попытку позже.")
            ArrayDeque()
        }
        while(queue.isNotEmpty() && queue.first()<now-60000) queue.removeFirst()
        if(queue.size>=10) throw ApiFailure(HttpStatusCode.TooManyRequests,"RATE_LIMIT","Слишком много попыток. Подождите минуту.")
        queue.addLast(now)
    }
}
