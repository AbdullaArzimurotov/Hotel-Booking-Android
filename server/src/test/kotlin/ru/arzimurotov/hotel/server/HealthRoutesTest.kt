package ru.arzimurotov.hotel.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class HealthRoutesTest {
    @Test fun `readiness confirms a real successful database probe`() = testApplication {
        var checks = 0
        application { hotelModule(DatabaseProbe { checks++; true }) }
        val response = client.get("/api/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val dto = Json.decodeFromString<ReadinessResponse>(response.bodyAsText())
        assertEquals("connected", dto.database)
        assertEquals("hotel-coursework", dto.service)
        assertEquals(1, checks)
    }

    @Test fun `unavailable database returns 503 without credentials`() = testApplication {
        application { hotelModule(DatabaseProbe { false }) }
        val response = client.get("/api/v1/health")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertEquals("unavailable", Json.decodeFromString<ReadinessResponse>(body).database)
        assertFalse(body.contains("password"))
        assertFalse(body.contains("jdbc:"))
    }

    @Test fun `liveness does not require a database query`() = testApplication {
        application { hotelModule(DatabaseProbe { error("Must not access database") }) }
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/bookings").status)
    }

    @Test fun `config requires secret outside source code`() {
        assertThrows(IllegalArgumentException::class.java) { ServerConfig.fromEnvironment(emptyMap()) }
        val config = ServerConfig.fromEnvironment(mapOf("DB_PASSWORD" to "test-only"))
        assertEquals(8080, config.port)
        assertEquals("127.0.0.1", config.host)
        assertFalse(config.toString().contains("test-only"))
        assertThrows(IllegalArgumentException::class.java) {
            ServerConfig.fromEnvironment(mapOf("DB_PASSWORD" to "test-only", "PORT" to "invalid"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerConfig.fromEnvironment(mapOf("DB_PASSWORD" to "test-only", "PORT" to "70000"))
        }
    }
}
