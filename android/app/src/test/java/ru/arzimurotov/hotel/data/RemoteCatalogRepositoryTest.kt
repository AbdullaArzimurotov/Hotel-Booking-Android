package ru.arzimurotov.hotel.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*
import ru.arzimurotov.hotel.domain.*

class RemoteCatalogRepositoryTest {
    @Test fun loadsOnlyApiAndPreservesDecimalAmounts()=runTest {
        val data=SeedCatalog.sqlCatalog()
        val engine=MockEngine { request ->
            val body=when(request.url.encodedPath.substringAfter("/api/v1/")) {
                "locations/countries" -> Json.encodeToString(Page(data.countries,3,0,100))
                "locations/cities" -> Json.encodeToString(Page(data.cities,6,0,100))
                "hotels" -> Json.encodeToString(Page(data.hotels,36,0,100))
                else -> Json.encodeToString(Page(data.places,30,0,100))
            }
            respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        }
        HttpClient(engine){install(ContentNegotiation){json()}}.use { client ->
            assertEquals(data,RemoteCatalogRepository(client){"http://test/"}.load())
        }
    }
    @Test fun unavailableServerNeverFallsBackToDemo()=runTest {
        HttpClient(MockEngine {respond("{}",HttpStatusCode.ServiceUnavailable,headersOf(HttpHeaders.ContentType,"application/json"))}) {
            install(ContentNegotiation){json()}
        }.use { client ->
            try {RemoteCatalogRepository(client){"http://test/"}.load();fail("Expected API failure")}
            catch(_: IllegalStateException) { }
        }
    }
}
