package ru.arzimurotov.hotel.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface TokenStore {
    fun read(): String?

    fun save(token: String)

    fun clear()

    fun readRecovery(): String? = null

    fun saveRecovery(value: String) {}

    fun clearRecovery() {}
}

/**
 * JWT хранится AES-GCM ciphertext в noBackupFilesDir. Ключ неэкспортируемый Android Keystore.
 * Повреждённый файл/утерянный ключ означает выход, а не попытку использовать незашифрованный JWT. В
 * оперативной памяти токен нужен HTTP-клиенту; пароль нигде не сохраняется.
 */
@Singleton
class SessionStore @Inject constructor(@ApplicationContext context: Context) : TokenStore {
    private val file = java.io.File(context.noBackupFilesDir, "session.enc")
    private val alias = "hotel_session_v1"
    private val pendingFile = java.io.File(context.noBackupFilesDir, "pending_operation.enc")
    private val recoveryFile = java.io.File(context.noBackupFilesDir, "recovery_notice.enc")

    /**
     * Код, ещё не подтверждённый пользователем, шифруется Keystore и переживает process death.
     * После «Я сохранил код» удаляется; в Room остаётся только Argon2id хеш.
     */
    @Synchronized
    override fun saveRecovery(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val output = android.util.AtomicFile(recoveryFile)
        val s = output.startWrite()
        try {
            s.write(cipher.iv + cipher.doFinal(value.toByteArray()))
            output.finishWrite(s)
        } catch (e: Exception) {
            output.failWrite(s)
            throw e
        }
    }

    @Synchronized
    override fun readRecovery(): String? =
        try {
            if (!recoveryFile.exists()) null
            else {
                val bytes = recoveryFile.readBytes()
                require(bytes.size in 29..8192)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                )
                cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            clearRecovery()
            null
        }

    @Synchronized
    override fun clearRecovery() {
        recoveryFile.delete()
    }

    @Synchronized
    fun savePending(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val output = android.util.AtomicFile(pendingFile)
        val s = output.startWrite()
        try {
            s.write(cipher.iv + cipher.doFinal(value.toByteArray()))
            output.finishWrite(s)
        } catch (e: Exception) {
            output.failWrite(s)
            throw e
        }
    }

    @Synchronized
    fun readPending(): String? =
        try {
            if (!pendingFile.exists()) null
            else {
                val bytes = pendingFile.readBytes()
                require(bytes.size in 29..8192)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                )
                cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            clearPending()
            null
        }

    @Synchronized
    fun clearPending() {
        pendingFile.delete()
    }

    private val mutableExpired = MutableStateFlow(0)
    val expired = mutableExpired.asStateFlow()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                alias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    @Synchronized
    override fun read(): String? =
        try {
            if (!file.exists()) null
            else {
                val bytes = file.readBytes()
                require(bytes.size in 29..8192)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                )
                cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            clear()
            null
        }

    @Synchronized
    override fun save(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(encrypted)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    override fun clear() {
        file.delete()
        clearPending()
    }

    fun invalidate() {
        clear()
        mutableExpired.value += 1
    }
}
