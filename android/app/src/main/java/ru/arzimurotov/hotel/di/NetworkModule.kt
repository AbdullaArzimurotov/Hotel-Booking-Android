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
import ru.arzimurotov.hotel.domain.HealthRepository

/**
 * Composition root версии 0.9.0 связывает контракты с самостоятельной Room/SQLite системой. Ktor
 * сохранён для старой серверной реализации, но production repositories его не вызывают. Никакого
 * скрытого fallback или автоматической синхронизации с backend нет.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun database(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context
    ): ru.arzimurotov.hotel.data.local.LocalDatabase =
        androidx.room.Room.databaseBuilder(
                context,
                ru.arzimurotov.hotel.data.local.LocalDatabase::class.java,
                "hotel_offline_09.db",
            )
            .build()

    @Provides
    @Singleton
    fun engine(
        db: ru.arzimurotov.hotel.data.local.LocalDatabase,
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ): ru.arzimurotov.hotel.data.local.LocalEngine {
        // Отдельное пространство Keystore-файлов: старый сеанс backend 0.6.0 сохраняется.
        val localContext =
            object : android.content.ContextWrapper(context) {
                override fun getNoBackupFilesDir() =
                    java.io.File(context.noBackupFilesDir, "offline09").apply { mkdirs() }
            }
        return ru.arzimurotov.hotel.data.local.LocalEngine(
            db,
            ru.arzimurotov.hotel.data.SessionStore(localContext),
        )
    }

    @Provides
    @Singleton
    fun travelRepository(
        engine: ru.arzimurotov.hotel.data.local.LocalEngine
    ): ru.arzimurotov.hotel.data.TravelRepository =
        ru.arzimurotov.hotel.data.local.LocalTravelRepository(engine)

    @Provides
    @Singleton
    fun catalogRepository(
        engine: ru.arzimurotov.hotel.data.local.LocalEngine
    ): ru.arzimurotov.hotel.domain.CatalogRepository =
        ru.arzimurotov.hotel.data.local.LocalCatalogRepository(engine)

    @Provides
    @Singleton
    fun authRepository(
        engine: ru.arzimurotov.hotel.data.local.LocalEngine
    ): ru.arzimurotov.hotel.data.AuthRepository =
        ru.arzimurotov.hotel.data.local.LocalAuthRepository(engine)

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
    fun healthRepository(engine: ru.arzimurotov.hotel.data.local.LocalEngine): HealthRepository =
        object : HealthRepository {
            override suspend fun check(): ru.arzimurotov.hotel.domain.SystemHealth {
                engine.ensure()
                return ru.arzimurotov.hotel.domain.SystemHealth(false, true, "0.9.0")
            }
        }
}
