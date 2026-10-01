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
data class LivenessResponse(
    val status: String = "ok",
    val service: String = "hotel-coursework",
    val version: String = VERSION,
)

@Serializable
data class ReadinessResponse(
    val status: String,
    val database: String,
    val service: String = "hotel-coursework",
    val version: String = VERSION,
    val stage: Int = 1,
)

/**
 * REST-модуль платформы первого этапа. /health проверяет сам процесс без SQL; /api/v1/health
 * выполняет DatabaseProbe и возвращает 200 либо 503. DatabaseProbe передаётся извне, поэтому
 * API-тесты заменяют БД тестовой реализацией. На этом этапе маршрутов регистрации, каталога,
 * бронирования и оплаты ещё нет. Ответы содержат только состояние/версию, не параметры подключения
 * и не пароли.
 */
fun Application.hotelModule(database: DatabaseProbe) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
    routing {
        get("/health") { call.respond(LivenessResponse()) }
        get("/api/v1/health") {
            val ready = database.isReady()
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                ReadinessResponse(
                    if (ready) "ok" else "degraded",
                    if (ready) "connected" else "unavailable",
                ),
            )
        }
    }
}

/** Запускает локальный REST-сервер после применения неразрушающих SQL-миграций. */
fun main() {
    val config = ServerConfig.fromEnvironment()
    val database = DatabaseService(config)
    try {
        embeddedServer(Netty, host = config.host, port = config.port) { hotelModule(database) }
            .start(wait = true)
    } finally {
        database.close()
    }
}
