package ru.arzimurotov.hotel.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import ru.arzimurotov.hotel.BuildConfig
import ru.arzimurotov.hotel.data.RemoteHealthRepository
import ru.arzimurotov.hotel.domain.HealthRepository

/**
 * Composition root связывает контракты с API-репозиториями каталога, аккаунта и заказов.
 * В рабочем приложении нет подмены SQL локальным demo-каталогом. SingletonComponent сохраняет
 * один Ktor Client на приложение; ключи и персональные ответы не попадают в сетевые логи.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton
    fun travelRepository(r:ru.arzimurotov.hotel.data.RemoteTravelRepository):ru.arzimurotov.hotel.data.TravelRepository=r
    @Provides
    @Singleton
    fun catalogRepository(client: HttpClient, connection: ru.arzimurotov.hotel.data.ApiConnection): ru.arzimurotov.hotel.domain.CatalogRepository =
        ru.arzimurotov.hotel.data.RemoteCatalogRepository(client,connection)

    @Provides @Singleton
    fun authRepository(client: HttpClient, connection: ru.arzimurotov.hotel.data.ApiConnection, session: ru.arzimurotov.hotel.data.SessionStore): ru.arzimurotov.hotel.data.AuthRepository =
        ru.arzimurotov.hotel.data.RemoteAuthRepository(client,connection,session)

    @Provides
    @Singleton
    fun client(): HttpClient =
        HttpClient(OkHttp) {
            // Дополнительные поля ответа не ломают старую версию клиента.
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                // Ограниченные ожидания позволяют показать ошибку и кнопку повторной проверки.
                requestTimeoutMillis = 5_000
                connectTimeoutMillis = 3_000
                socketTimeoutMillis = 5_000
            }
        }

    @Provides
    @Singleton
    fun healthRepository(client: HttpClient, connection: ru.arzimurotov.hotel.data.ApiConnection): HealthRepository =
        object : HealthRepository { override suspend fun check() = RemoteHealthRepository(client,connection.baseUrl).check() }
}
