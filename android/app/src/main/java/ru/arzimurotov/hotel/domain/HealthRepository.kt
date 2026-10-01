package ru.arzimurotov.hotel.domain

/** Общедоступные сведения о готовности сервера без конфиденциальных данных. */
data class SystemHealth(
    val serverConnected: Boolean,
    val databaseConnected: Boolean,
    val version: String,
)

/** Проверяет реальный сервер, а не сохранённый каталог. */
interface HealthRepository {
    suspend fun check(): SystemHealth
}
