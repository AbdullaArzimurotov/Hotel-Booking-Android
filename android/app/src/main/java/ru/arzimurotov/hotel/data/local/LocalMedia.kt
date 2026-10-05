package ru.arzimurotov.hotel.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Photo Picker выдаёт URI только выбранного фото. Декодирование ограничено по размеру, JPEG без
 * EXIF хранится во внутренней папке; исходный URI после импорта не нужен.
 */
object LocalMedia {
    fun file(context: Context, name: String) = File(context.filesDir, "photos/$name")

    suspend fun import(context: Context, uri: Uri): String =
        withContext(Dispatchers.IO) {
            val data =
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 5 * 1024 * 1024) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("Фото недоступно")
            require(data.size <= 5 * 1024 * 1024) { "Фото должно быть не больше 5 МиБ" }
            val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, info)
            require(
                info.outWidth > 0 &&
                    info.outHeight > 0 &&
                    info.outWidth.toLong() * info.outHeight <= 40_000_000
            ) {
                "Формат или размер фото не поддерживается"
            }
            var sample = 1
            while (info.outWidth / sample > 2048 || info.outHeight / sample > 2048) sample *= 2
            val bitmap =
                BitmapFactory.decodeByteArray(
                    data,
                    0,
                    data.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: error("Не удалось прочитать фото")
            val name = UUID.randomUUID().toString() + ".jpg"
            val destination = file(context, name)
            destination.parentFile!!.mkdirs()
            try {
                destination.outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it))
                }
                name
            } finally {
                bitmap.recycle()
            }
        }
}
