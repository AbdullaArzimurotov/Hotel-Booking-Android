package ru.arzimurotov.hotel.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import ru.arzimurotov.hotel.BuildConfig
import androidx.core.content.edit

/** Только два debug-адреса, произвольный URL/HTTP в release не разрешён.
 * Смена адреса — явное действие пользователя, а не скрытая подмена источника. */
@Singleton class ApiConnection @Inject constructor(@ApplicationContext context: Context) {
    private val preferences=context.getSharedPreferences("connection",Context.MODE_PRIVATE)
    val baseUrl: String get() = if(BuildConfig.DEBUG && preferences.getBoolean("usb",false)) "http://127.0.0.1:8080/" else BuildConfig.API_BASE_URL
    val isUsb: Boolean get() = baseUrl.contains("127.0.0.1")
    fun selectUsb(usb: Boolean) { if(BuildConfig.DEBUG) preferences.edit {putBoolean("usb",usb)} }
}
