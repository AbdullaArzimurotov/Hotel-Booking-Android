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
import kotlinx.serialization.json.Json
import ru.arzimurotov.hotel.BuildConfig
import ru.arzimurotov.hotel.data.RemoteHealthRepository
import ru.arzimurotov.hotel.domain.HealthRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun client(): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 5_000; connectTimeoutMillis = 3_000; socketTimeoutMillis = 5_000 }
    }

    @Provides
    @Singleton
    fun healthRepository(client: HttpClient): HealthRepository = RemoteHealthRepository(client, BuildConfig.API_BASE_URL)
}
