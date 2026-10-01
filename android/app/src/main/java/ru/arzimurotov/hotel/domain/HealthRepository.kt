package ru.arzimurotov.hotel.domain

/** Public, non-sensitive readiness information returned by our server. */
data class SystemHealth(val serverConnected: Boolean, val databaseConnected: Boolean, val version: String)

/** Checks the real server, not cached catalogue data. */
interface HealthRepository {
    suspend fun check(): SystemHealth
}
