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
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.BadRequestException
import ru.arzimurotov.hotel.domain.ApiError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch

private const val VERSION = "0.6.0"

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
    val stage: Int = 6,
)

/**
 * Composition root REST API 0.6.0. /health проверяет сам процесс без SQL; /api/v1/health
 * выполняет DatabaseProbe и возвращает 200 либо 503. DatabaseProbe передаётся извне, поэтому
 * Изолированные health-тесты заменяют БД тестовой реализацией; при передаче репозиториев
 * устанавливаются каталог, аккаунты, поиск, заказы, демооплата и PDF. Health не раскрывает
 * параметры подключения и пароли. Конфликт бизнес-правил получает безопасный ApiError.
 */
fun Application.hotelModule(database: DatabaseProbe, catalog: CatalogRepository? = null, auth: AuthService? = null,
    search:SearchService? = (database as? DatabaseService)?.let { SearchService(SearchRepository(it)) },
    bookings:BookingService? = (database as? DatabaseService)?.let { BookingService(BookingRepository(it,SearchRepository(it))) }) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
    install(StatusPages) {
        exception<Throwable> { call, error ->
            if(error is kotlinx.coroutines.CancellationException) throw error
            val requestId=java.util.UUID.randomUUID().toString()
            val failure = when(error) {
                is ApiFailure -> error
                is BadRequestException, is kotlinx.serialization.SerializationException -> ApiFailure(HttpStatusCode.BadRequest,"INVALID_BODY","Некорректный запрос или недопустимые поля.")
                else -> ApiFailure(HttpStatusCode.InternalServerError,"INTERNAL_ERROR","Не удалось выполнить запрос. Повторите позже.")
            }
            if(failure.status==HttpStatusCode.InternalServerError) call.application.environment.log.error("Request {} failed: {}",requestId,error.javaClass.simpleName)
            call.response.headers.append("X-Request-Id",requestId)
            call.respond(failure.status,ApiError(failure.code,failure.message,failure.fields,requestId))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status,ApiError("NOT_FOUND","Ресурс не найден.",requestId=java.util.UUID.randomUUID().toString()))
        }
    }
    routing {
        if(catalog!=null && auth!=null) apiRoutes(catalog,auth,search,bookings)
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
fun main(args: Array<String>) {
    val config = ServerConfig.fromEnvironment()
    val database = DatabaseService(config)
    val jobs=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)
    try {
        val tokens=TokenService(requireNotNull(System.getenv("JWT_SECRET")) { "JWT_SECRET is required" })
        val catalog=CatalogRepository(database)
        val auth=AuthService(UserRepository(database),tokens)
        val search=SearchRepository(database)
        val bookingRepo=BookingRepository(database,search)
        runBlocking { catalog.seed();search.seedBlocks();bookingRepo.maintenance() }
        if(args.firstOrNull()=="create-admin") {
            runBlocking {
                auth.register(ru.arzimurotov.hotel.domain.RegisterRequest(
                    requireNotNull(System.getenv("ADMIN_EMAIL")),requireNotNull(System.getenv("ADMIN_PASSWORD")),
                    requireNotNull(System.getenv("ADMIN_NAME"))),"ADMIN")
            }
            println("Administrator created. Existing users were not modified.")
            return
        }
        jobs.launchMaintenance(bookingRepo)
        embeddedServer(Netty, host = config.host, port = config.port) { hotelModule(database,catalog,auth,SearchService(search),BookingService(bookingRepo)) }
            .start(wait = true)
    } finally {
        jobs.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        database.close()
    }
}

/** Остановка сервера отменяет обслуживающую coroutine; сбой одной итерации не убивает сервер. */
private fun kotlinx.coroutines.CoroutineScope.launchMaintenance(repo:BookingRepository) =
    launch { while(true) { kotlinx.coroutines.delay(60_000);try { repo.maintenance() } catch(e:kotlinx.coroutines.CancellationException) { throw e } catch(_:Exception) { org.slf4j.LoggerFactory.getLogger("maintenance").warn("Maintenance failed; will retry") } } }
