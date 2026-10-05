package ru.arzimurotov.hotel.data.local

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id: 19 MiB, 2 прохода, p=1; отдельная случайная salt. Вызывается только на IO.
 * Восстановительный код тоже хешируется. Не является авторизацией общего облачного сервера.
 */
object LocalSecurity {
    private val random = SecureRandom()

    fun hash(value: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return Base64.getEncoder().encodeToString(salt) +
            ":" +
            Base64.getEncoder().encodeToString(derive(value, salt))
    }

    fun matches(value: String, stored: String): Boolean = runCatching {
        val parts = stored.split(':')
        val salt = Base64.getDecoder().decode(parts[0])
        val expected = Base64.getDecoder().decode(parts[1])
        salt.size == 16 &&
            expected.size == 32 &&
            MessageDigest.isEqual(expected, derive(value, salt))
    }
        .getOrDefault(false)

    private fun derive(value: String, salt: ByteArray): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withMemoryAsKB(19456)
                .withIterations(2)
                .withParallelism(1)
                .build()
        )
        return ByteArray(32).also { generator.generateBytes(value.toByteArray(Charsets.UTF_8), it) }
    }

    fun recoveryCode() =
        ByteArray(16)
            .also(random::nextBytes)
            .joinToString("") { "%02X".format(it) }
            .chunked(4)
            .joinToString("-")
}
