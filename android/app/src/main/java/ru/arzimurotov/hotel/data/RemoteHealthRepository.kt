package ru.arzimurotov.hotel.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import ru.arzimurotov.hotel.domain.HealthRepository
import ru.arzimurotov.hotel.domain.SystemHealth

@Serializable
private data class HealthDto(val status: String, val service: String, val version: String, val stage: Int, val database: String)

/** Adapts the readiness REST response to a framework-free domain model. */
class RemoteHealthRepository(private val client: HttpClient, private val baseUrl: String) : HealthRepository {
    override suspend fun check(): SystemHealth {
        val response = client.get("${baseUrl.trimEnd('/')}/api/v1/health")
        check(response.status == HttpStatusCode.OK || response.status == HttpStatusCode.ServiceUnavailable) {
            "Unexpected readiness response"
        }
        val data = response.body<HealthDto>()
        check(data.service == "hotel-coursework") { "Unexpected service" }
        return SystemHealth(true, response.status == HttpStatusCode.OK && data.database == "connected", data.version)
    }
}
