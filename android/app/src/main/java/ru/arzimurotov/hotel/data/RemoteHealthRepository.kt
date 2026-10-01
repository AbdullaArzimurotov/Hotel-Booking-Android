package ru.arzimurotov.hotel.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import ru.arzimurotov.hotel.domain.HealthRepository
import ru.arzimurotov.hotel.domain.SystemHealth

/** Транспортная DTO остаётся в Data: доменная модель не зависит от JSON-сериализатора. */
@Serializable
private data class HealthDto(
    val status: String,
    val service: String,
    val version: String,
    val stage: Int,
    val database: String,
)

/**
 * Выполняет GET /api/v1/health и преобразует JSON DTO в SystemHealth. Ответы 200 и 503 различают
 * готовую и недоступную базу; неожиданный ответ/чужой service считается ошибкой. Доступность
 * HTTP-сервера не означает готовность PostgreSQL. Исключение обрабатывает FoundationViewModel;
 * пароли и URL базы в модель не попадают.
 */
class RemoteHealthRepository(private val client: HttpClient, private val baseUrl: String) :
    HealthRepository {
    override suspend fun check(): SystemHealth {
        val response = client.get("${baseUrl.trimEnd('/')}/api/v1/health")
        check(
            response.status == HttpStatusCode.OK ||
                response.status == HttpStatusCode.ServiceUnavailable
        ) {
            "Unexpected readiness response"
        }
        val data = response.body<HealthDto>()
        check(data.service == "hotel-coursework") { "Unexpected service" }
        return SystemHealth(
            true,
            response.status == HttpStatusCode.OK && data.database == "connected",
            data.version,
        )
    }
}
