package ru.arzimurotov.hotel.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import ru.arzimurotov.hotel.domain.*

/**
 * Только локальный MapFile, без download layer. Native View внутри Compose освобождает renderer
 * threads и tile cache при выходе. Маркеры и список получают одни ID каталога.
 */
@Composable
fun OfflineMapScreen(
    catalog: Catalog,
    query: SearchQuery,
    onBack: () -> Unit,
    onPlace: (String) -> Unit,
    onHotel: (String) -> Unit,
) {
    val cities = catalog.cities.filter { it.countryId == query.countryId }
    var cityId by
        remember(query.cityId, query.countryId) {
            mutableStateOf(query.cityId ?: cities.firstOrNull()?.id)
        }
    var category by remember { mutableStateOf<PlaceCategory?>(null) }
    var selected by remember(cityId) { mutableStateOf<Pair<String, Boolean>?>(null) }
    var open by remember { mutableStateOf(false) }
    val city = catalog.cities.find { it.id == cityId }
    val context = LocalContext.current
    var rendered by remember(cityId, category) { mutableStateOf(false) }
    var path by remember(cityId) { mutableStateOf<File?>(null) }
    var error by remember(cityId) { mutableStateOf<String?>(null) }
    LaunchedEffect(cityId) {
        if (city != null)
            try {
                path =
                    withContext(Dispatchers.IO) {
                        val key = city.legacyId
                        val file = File(context.filesDir, "maps/$key.map")
                        file.parentFile!!.mkdirs()
                        if (!file.exists())
                            context.assets.open("maps/$key.map").use { input ->
                                file.outputStream().use { input.copyTo(it) }
                            }
                        file
                    }
            } catch (_: Exception) {
                error =
                    "Для этого города нет встроенной карты. Список и координаты доступны без неё."
            }
    }
    Column(Modifier.fillMaxSize().testTag("offline_map")) {
        ScreenHeader("Карта и места", "Offline · небольшой центральный район", onBack)
        Row(Modifier.padding(8.dp)) {
            Box {
                OutlinedButton({ open = true }) { Text(city?.name ?: "Выберите город") }
                DropdownMenu(open, { open = false }) {
                    cities.forEach { c ->
                        DropdownMenuItem(
                            { Text(c.name) },
                            {
                                cityId = c.id
                                open = false
                            },
                        )
                    }
                }
            }
            TextButton({
                category =
                    if (category == null) PlaceCategory.RESTAURANT
                    else PlaceCategory.entries.getOrNull(category!!.ordinal + 1)
            }) {
                Text(category?.title ?: "Все места")
            }
        }
        val places =
            catalog.places.filter {
                it.cityId == cityId && (category == null || it.category == category)
            }
        val hotels = catalog.hotels.filter { it.cityId == cityId }
        if (path != null)
            key(path, category, catalog) {
                AndroidView(
                    factory = { ctx ->
                        AndroidGraphicFactory.createInstance(ctx.applicationContext)
                        MapView(ctx).apply {
                            setBuiltInZoomControls(true)
                            setZoomLevelMin(12)
                            setZoomLevelMax(19)
                            setZoomLevel(15)
                            setCenter(LatLong(city?.latitude ?: 0.0, city?.longitude ?: 0.0))
                            val map = MapFile(requireNotNull(path))
                            val cache =
                                AndroidUtil.createTileCache(
                                    ctx,
                                    "tiles-${city?.legacyId}",
                                    model.displayModel.tileSize,
                                    1f,
                                    model.frameBufferModel.overdrawFactor,
                                )
                            cache.addObserver(
                                object : org.mapsforge.map.model.common.Observer {
                                    override fun onChange() {
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            rendered = true
                                        }
                                    }
                                }
                            )
                            val layer =
                                AndroidUtil.createTileRendererLayer(
                                    cache,
                                    model.mapViewPosition,
                                    map,
                                    MapsforgeThemes.DEFAULT,
                                )
                            layerManager.layers.add(layer)
                            fun marker(
                                id: String,
                                title: String,
                                lat: Double?,
                                lon: Double?,
                                hotel: Boolean,
                            ) {
                                if (lat == null || lon == null) return
                                val point = LatLong(lat, lon)
                                if (!map.boundingBox().contains(point)) return
                                val icon =
                                    android.graphics.Bitmap.createBitmap(
                                        42,
                                        42,
                                        android.graphics.Bitmap.Config.ARGB_8888,
                                    )
                                val canvas = android.graphics.Canvas(icon)
                                val paint =
                                    android.graphics
                                        .Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                                        .apply {
                                            color =
                                                if (hotel) android.graphics.Color.rgb(19, 47, 76)
                                                else android.graphics.Color.rgb(221, 167, 99)
                                        }
                                canvas.drawCircle(21f, 21f, 17f, paint)
                                paint.color = android.graphics.Color.WHITE
                                paint.textSize = 22f
                                paint.textAlign = android.graphics.Paint.Align.CENTER
                                canvas.drawText(if (hotel) "Г" else "М", 21f, 29f, paint)
                                val bitmap =
                                    AndroidGraphicFactory.convertToBitmap(
                                        android.graphics.drawable.BitmapDrawable(
                                            ctx.resources,
                                            icon,
                                        )
                                    )
                                layerManager.layers.add(
                                    object : Marker(point, bitmap, 0, 0) {
                                        override fun onTap(
                                            tapLatLong: LatLong?,
                                            layerXY: org.mapsforge.core.model.Point?,
                                            tapXY: org.mapsforge.core.model.Point?,
                                        ): Boolean {
                                            if (
                                                layerXY != null &&
                                                    tapXY != null &&
                                                    kotlin.math.abs(layerXY.x - tapXY.x) < 24 &&
                                                    kotlin.math.abs(layerXY.y - tapXY.y) < 24
                                            ) {
                                                selected = id to hotel
                                                return true
                                            }
                                            return false
                                        }
                                    }
                                )
                            }
                            hotels.forEach {
                                marker(it.id, it.name, it.latitude, it.longitude, true)
                            }
                            places.forEach {
                                marker(it.id, it.name, it.latitude, it.longitude, false)
                            }
                        }
                    },
                    onRelease = { it.destroyAll() },
                    modifier =
                        Modifier.fillMaxWidth().weight(1f).clipToBounds().testTag("map_view"),
                )
            }
        else
            Box(Modifier.weight(1f).padding(24.dp)) {
                if (error != null) Text(error!!) else CircularProgressIndicator()
            }
        selected?.let { (id, isHotel) ->
            val h = hotels.find { it.id == id }
            val p = places.find { it.id == id }
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Row(Modifier.padding(12.dp)) {
                    Text(h?.name ?: p?.name.orEmpty(), Modifier.weight(1f))
                    TextButton({ if (isHotel) onHotel(id) else onPlace(id) }) { Text("Открыть") }
                }
            }
        }
        Text(
            "© OpenStreetMap contributors · ODbL. Гостиницы и координаты объектов демонстрационные.",
            Modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        if (rendered)
            Text(
                "Карта загружена из APK",
                Modifier.testTag("map_rendered"),
                style = MaterialTheme.typography.bodySmall,
            )
        if (places.isNotEmpty())
            TextButton({
                val p = places.first()
                onPlace(p.id)
            }) {
                Text("Первое место: " + places.first().name)
            }
    }
}
