package ru.arzimurotov.hotel.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val VERSION = "0.1.0"

@Serializable
data class LivenessResponse(val status: String = "ok", val service: String = "hotel-coursework", val version: String = VERSION)

@Serializable
data class ReadinessResponse(
    val status: String,
    val database: String,
    val service: String = "hotel-coursework",
    val version: String = VERSION,
    val stage: Int = 1,
)

/** Stage-one REST module: liveness is separate from actual database readiness. */
fun Application.hotelModule(database: DatabaseProbe) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
    routing {
        get("/health") { call.respond(LivenessResponse()) }
        get("/api/v1/health") {
            val ready = database.isReady()
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                ReadinessResponse(if (ready) "ok" else "degraded", if (ready) "connected" else "unavailable"),
            )
        }
    }
}

/** Starts the local REST server after applying non-destructive SQL migrations. */
fun main() {
    val config = ServerConfig.fromEnvironment()
    val database = DatabaseService(config)
    try {
        embeddedServer(Netty, host = config.host, port = config.port) { hotelModule(database) }.start(wait = true)
    } finally {
        database.close()
    }
}
