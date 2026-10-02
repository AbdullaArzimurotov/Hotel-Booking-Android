package ru.arzimurotov.hotel.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/** Абстракция готовности позволяет тестировать API без запущенной PostgreSQL. */
fun interface DatabaseProbe {
    suspend fun isReady(): Boolean
}

/**
 * Реальная PostgreSQL инфраструктура первого этапа: небольшой пул HikariCP, Flyway и Exposed.
 * Миграции применяются до запуска HTTP-сервера; ошибка закрывает уже созданный пул. В готовности
 * выполняется SELECT 1 в транзакции с ограниченным временем ожидания. Блокирующий JDBC переносится
 * на Dispatchers.IO, отмена coroutine не скрывается как сбой БД. Это пока не репозиторий
 * гостиниц/заказов: бизнес-таблицы появятся на следующем этапе.
 */
class DatabaseService(config: ServerConfig) : DatabaseProbe, AutoCloseable {
    private val pool =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.databaseUrl
                username = config.databaseUser
                password = config.databasePassword
                maximumPoolSize = 4
                minimumIdle = 1
                connectionTimeout = 3_000
                validationTimeout = 1_000
                poolName = "hotel-database"
            }
        )
    private val database: Database

    init {
        try {
            Flyway.configure().dataSource(pool).locations("classpath:db/migration").load().migrate()
            database = Database.connect(pool)
        } catch (exception: Exception) {
            pool.close()
            throw exception
        }
    }

    override suspend fun isReady(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                transaction(database) {
                    queryTimeout = 2
                    maxAttempts = 1
                    exec("SELECT 1") { result -> result.next() && result.getInt(1) == 1 } == true
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                false
            }
        }

    override fun close() {
        pool.close()
    }

    /** JDBC внутри транзакции Exposed. Пул и блокирующий SQL не занимают Netty event loop.
     * Автоматический повтор отключён: запись нельзя незаметно выполнить повторно. */
    suspend fun <T> query(block: (java.sql.Connection) -> T): T = withContext(Dispatchers.IO) {
        transaction(database) {
            maxAttempts = 1
            block(connection.connection as java.sql.Connection)
        }
    }
}
