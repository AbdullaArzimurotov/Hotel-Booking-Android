package ru.arzimurotov.hotel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import ru.arzimurotov.hotel.domain.*

private data class AppTab(val route: String, val title: String, val icon: Glyph)

private val tabs =
    listOf(
        AppTab("search", "Поиск", Glyph.SEARCH),
        AppTab("places", "Места", Glyph.LOCATION),
        AppTab("bookings", "Брони", Glyph.BOOKING),
        AppTab("profile", "Профиль", Glyph.PROFILE),
    )

/**
 * Корневой composable и граф Navigation Compose для четырёх вкладок и вложенных экранов. Состояние
 * приходит из Activity, события уходят callbacks в ViewModel (однонаправленный поток).
 * Загрузка/ошибка обрабатываются до создания графа. Параметры маршрутов — стабильные id, а не
 * сериализованные Hotel: объект повторно находится в актуальном Catalog. От 720 dp используется
 * NavigationRail; на телефоне — нижняя панель главных вкладок. Диагностика — единственный экран,
 * связанный с настоящим API на текущем этапе.
 */
@Composable
fun HotelApp(
    state: BrowseState,
    onQuery: (SearchQuery) -> Unit,
    onFavorite: (String) -> Unit,
    onReload: () -> Unit,
    health: FoundationState,
    onHealthRefresh: () -> Unit,
) {
    Surface(
        Modifier.fillMaxSize().background(TravelNavy).statusBarsPadding(),
        color = TravelBackground,
    ) {
        when {
            state.loading ->
                Box(
                    Modifier.fillMaxSize().testTag("catalog_loading"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Готовим направления…")
                    }
                }
            state.error || state.catalog == null ->
                Column(Modifier.fillMaxSize().testTag("catalog_error")) {
                    ScreenHeader("Гостиница")
                    EmptyPanel(
                        "Каталог не загрузился",
                        "Попробуйте открыть учебные данные ещё раз.",
                        actionLabel = "Повторить",
                        onAction = onReload,
                    )
                }
            else -> {
                val catalog = state.catalog
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route ?: "search"
                val mainRoute =
                    when {
                        route == "places" || route.startsWith("place/") -> "places"
                        route == "bookings" -> "bookings"
                        route == "profile" || route == "favorites" || route == "system" -> "profile"
                        else -> "search"
                    }
                val showBottom = tabs.any { it.route == route }
                fun tab(routeName: String) {
                    // Не накапливаем одинаковые вкладки в стеке; возвращаем их сохранённое
                    // состояние.
                    nav.navigate(routeName) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                fun hotel(id: String) {
                    val h = catalog.hotel(id) ?: return
                    val city = catalog.city(h.cityId)
                    // Карточка из избранного/рекомендаций может быть из другой страны:
                    // сбрасываем несовместимые фильтры, прежде чем считать цену в её валюте.
                    if (city.id != state.query.cityId) {
                        onQuery(
                            state.query.copy(
                                countryId = city.countryId,
                                cityId = city.id,
                                filters = SearchFilters(),
                                name = "",
                            )
                        )
                    }
                    nav.navigate("hotel/$id") { launchSingleTop = true }
                }
                fun back() {
                    if (!nav.popBackStack()) tab("search")
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 720.dp
                    Row(Modifier.fillMaxSize()) {
                        if (wide)
                            NavigationRail(
                                Modifier.fillMaxHeight().navigationBarsPadding(),
                                containerColor = Color.White,
                                header = {
                                    Text(
                                        "Г",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = TravelNavy,
                                        modifier = Modifier.padding(vertical = 20.dp),
                                    )
                                },
                                windowInsets = WindowInsets(0, 0, 0, 0),
                            ) {
                                tabs.forEach { item ->
                                    NavigationRailItem(
                                        mainRoute == item.route,
                                        { tab(item.route) },
                                        icon = { TravelIcon(item.icon) },
                                        label = { Text(item.title) },
                                        modifier = Modifier.testTag("nav_${item.route}"),
                                    )
                                }
                            }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Box(
                                Modifier.weight(1f)
                                    .fillMaxWidth()
                                    .then(
                                        if (!showBottom || wide) Modifier.navigationBarsPadding()
                                        else Modifier
                                    ),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                NavHost(
                                    nav,
                                    startDestination = "search",
                                    modifier = Modifier.widthIn(max = 1200.dp).fillMaxSize(),
                                ) {
                                    composable("search") {
                                        SearchScreen(
                                            state,
                                            wide,
                                            onQuery,
                                            { nav.navigate("results") { launchSingleTop = true } },
                                            ::hotel,
                                            onFavorite,
                                        )
                                    }
                                    composable("results") {
                                        ResultsScreen(
                                            state,
                                            wide,
                                            onQuery,
                                            ::back,
                                            ::hotel,
                                            onFavorite,
                                        )
                                    }
                                    composable("favorites") {
                                        ResultsScreen(
                                            state,
                                            wide,
                                            onQuery,
                                            ::back,
                                            ::hotel,
                                            onFavorite,
                                            favoritesOnly = true,
                                        )
                                    }
                                    composable("places") {
                                        PlacesScreen(catalog, state.query, wide, onQuery) {
                                            nav.navigate("place/$it")
                                        }
                                    }
                                    composable("bookings") { BookingsScreen { tab("search") } }
                                    composable("profile") {
                                        ProfileScreen(
                                            state.favorites.size,
                                            { nav.navigate("favorites") },
                                            { nav.navigate("system") },
                                        )
                                    }
                                    composable("system") {
                                        Column {
                                            ScreenHeader("Диагностика платформы", onBack = ::back)
                                            FoundationScreen(health, onHealthRefresh)
                                        }
                                    }
                                    composable(
                                        "hotel/{id}",
                                        arguments =
                                            listOf(navArgument("id") { type = NavType.StringType }),
                                    ) { e ->
                                        val id = e.arguments?.getString("id").orEmpty()
                                        val h = catalog.hotel(id)
                                        if (h == null)
                                            EmptyPanel(
                                                "Гостиница не найдена",
                                                "Вернитесь в каталог.",
                                                actionLabel = "Назад",
                                                onAction = ::back,
                                            )
                                        else
                                            HotelDetailScreen(
                                                catalog,
                                                h,
                                                state.query,
                                                id in state.favorites,
                                                { onFavorite(id) },
                                                ::back,
                                                { nav.navigate("gallery/$id/$it") },
                                                { nav.navigate("quote/$id/${it.name}") },
                                                { nav.navigate("place/$it") },
                                            )
                                    }
                                    composable(
                                        "gallery/{id}/{page}",
                                        arguments =
                                            listOf(
                                                navArgument("id") { type = NavType.StringType },
                                                navArgument("page") { type = NavType.IntType },
                                            ),
                                    ) { e ->
                                        catalog.hotel(e.arguments?.getString("id").orEmpty())?.let {
                                            GalleryScreen(
                                                it,
                                                e.arguments?.getInt("page") ?: 0,
                                                ::back,
                                            )
                                        }
                                    }
                                    composable(
                                        "quote/{id}/{kind}",
                                        arguments =
                                            listOf(
                                                navArgument("id") { type = NavType.StringType },
                                                navArgument("kind") { type = NavType.StringType },
                                            ),
                                    ) { e ->
                                        val h =
                                            catalog.hotel(e.arguments?.getString("id").orEmpty())
                                        val room =
                                            h?.rooms?.find {
                                                it.kind.name == e.arguments?.getString("kind")
                                            }
                                        if (h != null && room != null)
                                            QuoteScreen(catalog, h, room, state.query, ::back)
                                    }
                                    composable(
                                        "place/{id}",
                                        arguments =
                                            listOf(navArgument("id") { type = NavType.StringType }),
                                    ) { e ->
                                        catalog.places
                                            .find { it.id == e.arguments?.getString("id") }
                                            ?.let { PlaceDetailScreen(it, ::back) }
                                    }
                                }
                            }
                            if (!wide && showBottom)
                                NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                                    tabs.forEach { item ->
                                        NavigationBarItem(
                                            mainRoute == item.route,
                                            { tab(item.route) },
                                            icon = { TravelIcon(item.icon) },
                                            label = { Text(item.title) },
                                            modifier = Modifier.testTag("nav_${item.route}"),
                                        )
                                    }
                                }
                        }
                    }
                }
            }
        }
    }
}
