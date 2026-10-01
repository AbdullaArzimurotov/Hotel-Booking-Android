package ru.arzimurotov.hotel.server

/** Environment-only settings; credentials never enter source control or API responses. */
data class ServerConfig(
    val host: String,
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
) {
    override fun toString(): String =
        "ServerConfig(host=$host, port=$port, databaseUrl=$databaseUrl, databaseUser=$databaseUser, databasePassword=<redacted>)"

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ServerConfig {
            val port = environment["PORT"]?.let {
                requireNotNull(it.toIntOrNull()) { "PORT must be a valid TCP port" }
            } ?: 8080
            require(port in 1..65535) { "PORT must be a valid TCP port" }
            val password = environment["DB_PASSWORD"]
            require(!password.isNullOrBlank()) { "DB_PASSWORD is required. Use scripts/dev.py server." }
            return ServerConfig(
                host = environment["HOST"] ?: "127.0.0.1",
                port = port,
                databaseUrl = environment["DB_URL"] ?: "jdbc:postgresql://127.0.0.1:55432/hotel_coursework",
                databaseUser = environment["DB_USER"] ?: "hotel_app",
                databasePassword = password,
            )
        }
    }
}
