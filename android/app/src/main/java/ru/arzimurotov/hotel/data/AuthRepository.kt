package ru.arzimurotov.hotel.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.arzimurotov.hotel.domain.*

class AuthProblem(val error: ApiError) : Exception(error.message)
interface AuthRepository {
    val expirations: kotlinx.coroutines.flow.Flow<Int> get() = kotlinx.coroutines.flow.emptyFlow()
    suspend fun restore(): UserProfile?
    suspend fun register(request: RegisterRequest): UserProfile
    suspend fun login(request: LoginRequest): UserProfile
    suspend fun update(request: ProfilePatch): UserProfile
    suspend fun summary(): AdminSummary
    suspend fun logout()
}

/** Token сохраняется только после успешной проверки AuthResponse. 401 защищённого запроса
 * очищает сеанс и уведомляет AuthViewModel. Сетевой сбой не удаляет действующий token. */
class RemoteAuthRepository(private val client: HttpClient, private val connection: ApiConnection, private val session: SessionStore) : AuthRepository {
    override val expirations = session.expired
    private fun url(path: String)=connection.baseUrl+"api/v1/"+path
    private suspend fun token()=withContext(Dispatchers.IO) { session.read() } ?: throw AuthProblem(ApiError("UNAUTHORIZED","Войдите в аккаунт."))
    private suspend fun checked(response: HttpResponse, protected: Boolean = false): HttpResponse {
        if(response.status.value !in 200..299) {
            if(protected && response.status==HttpStatusCode.Unauthorized) withContext(Dispatchers.IO) { session.invalidate() }
            val error=runCatching { response.body<ApiError>() }.getOrElse { ApiError("HTTP_ERROR","Сервис недоступен. Повторите позже.") }
            throw AuthProblem(error)
        }
        return response
    }
    override suspend fun restore(): UserProfile? {
        val token=withContext(Dispatchers.IO) { session.read() } ?: return null
        return checked(client.get(url("profile")) { bearerAuth(token) },true).body()
    }
    override suspend fun register(request: RegisterRequest): UserProfile =
        checked(client.post(url("auth/register")) { contentType(ContentType.Application.Json); setBody(request) }).body()
    override suspend fun login(request: LoginRequest): UserProfile {
        val result=checked(client.post(url("auth/login")) { contentType(ContentType.Application.Json); setBody(request) }).body<AuthResponse>()
        check(result.expiresAt>java.time.Instant.now().epochSecond && result.token.length in 20..4096)
        withContext(Dispatchers.IO) { session.save(result.token) }
        return result.user
    }
    override suspend fun update(request: ProfilePatch): UserProfile {
        val token=token()
        return checked(client.patch(url("profile")) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(request) },true).body()
    }
    override suspend fun summary(): AdminSummary {
        val token=token()
        return checked(client.get(url("admin/summary")) { bearerAuth(token) },true).body()
    }
    override suspend fun logout() { withContext(Dispatchers.IO) { session.clear() } }
}
