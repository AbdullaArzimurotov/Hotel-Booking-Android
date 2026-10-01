package ru.arzimurotov.hotel.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/** Readiness abstraction makes API tests independent of a live PostgreSQL process. */
fun interface DatabaseProbe {
    suspend fun isReady(): Boolean
}

/** Owns a small JDBC pool, migrations, Exposed connection, and graceful pool shutdown. */
class DatabaseService(config: ServerConfig) : DatabaseProbe, AutoCloseable {
    private val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.databaseUrl
        username = config.databaseUser
        password = config.databasePassword
        maximumPoolSize = 4
        minimumIdle = 1
        connectionTimeout = 3_000
        validationTimeout = 1_000
        poolName = "hotel-database"
    })
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

    override suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
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

    override fun close() { pool.close() }
}
