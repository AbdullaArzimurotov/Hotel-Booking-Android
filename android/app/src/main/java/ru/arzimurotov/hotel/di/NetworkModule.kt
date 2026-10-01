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
 * Composition root приложения: связывает доменные интерфейсы с реализациями Data. Каталог пока
 * локальный; реальный Ktor Client используется только для health API. SingletonComponent сохраняет
 * один клиент на приложение, вместо создания на каждом экране.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun catalogRepository(): ru.arzimurotov.hotel.domain.CatalogRepository =
        ru.arzimurotov.hotel.data.DemoCatalogRepository()

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
    fun healthRepository(client: HttpClient): HealthRepository =
        RemoteHealthRepository(client, BuildConfig.API_BASE_URL)
}
