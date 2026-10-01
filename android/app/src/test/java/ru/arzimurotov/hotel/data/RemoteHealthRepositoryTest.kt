package ru.arzimurotov.hotel.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RemoteHealthRepositoryTest {
    @Test fun `successful readiness is parsed with forward compatible extra fields`() = runTest {
        val client = HttpClient(MockEngine {
            respond("""{"status":"ok","service":"hotel-coursework","version":"0.1.0","stage":1,"database":"connected","extra":true}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true }) } }
        try {
            val result = RemoteHealthRepository(client, "http://example.test").check()
            assertTrue(result.serverConnected)
            assertTrue(result.databaseConnected)
        } finally { client.close() }
    }

    @Test fun `a different service is not accepted as our backend`() = runTest {
        val client = HttpClient(MockEngine {
            respond("""{"status":"ok","service":"other-service","version":"1","stage":1,"database":"connected"}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            try {
                RemoteHealthRepository(client, "http://example.test").check()
                fail("A different service must be rejected")
            } catch (_: IllegalStateException) { /* expected */ }
        } finally { client.close() }
    }

    @Test fun `503 maps to database down instead of a false positive`() = runTest {
        val client = HttpClient(MockEngine {
            assertEquals("/api/v1/health", it.url.encodedPath)
            respond("""{"status":"degraded","service":"hotel-coursework","version":"0.1.0","stage":1,"database":"unavailable"}""",
                HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            val result = RemoteHealthRepository(client, "http://example.test/").check()
            assertTrue(result.serverConnected)
            assertFalse(result.databaseConnected)
        } finally { client.close() }
    }
}
