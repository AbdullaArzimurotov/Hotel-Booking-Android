package ru.arzimurotov.hotel.domain

import kotlin.math.*

/**
 * Центры шести небольших включённых карт. Объекты каталога вымышлены, координаты демонстрационные.
 */
object Geo {
    val centers =
        mapOf(
            "tashkent" to (41.3111 to 69.2797),
            "samarkand" to (39.6547 to 66.9750),
            "moscow" to (55.7512 to 37.6184),
            "petersburg" to (59.9398 to 30.3146),
            "istanbul" to (41.0082 to 28.9784),
            "antalya" to (36.8841 to 30.7056),
        )

    fun timezone(country: String) =
        when (country) {
            "uz" -> "Asia/Tashkent"
            "ru" -> "Europe/Moscow"
            "tr" -> "Europe/Istanbul"
            else -> "UTC"
        }

    /** Границы совпадают с manifest.json встроенных OSM-фрагментов. */
    fun hasMapCoverage(city: String?, lat: Double?, lon: Double?): Boolean {
        val p = centers[city] ?: return false
        if (lat == null || lon == null) return false
        val small = city in setOf("moscow", "petersburg", "istanbul")
        return abs(lat - p.first) <= (if (small) .0045 else .012) &&
            abs(lon - p.second) <= (if (small) .006 else .016)
    }

    fun distanceKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h =
            sin(dLat / 2).pow(2) +
                cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
        return 6371.0088 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
