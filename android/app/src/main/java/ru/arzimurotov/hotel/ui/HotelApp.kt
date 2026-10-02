package ru.arzimurotov.hotel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
 * NavigationRail; на телефоне — нижняя панель главных вкладок. Рабочий каталог, аккаунты,
 * поиск и заказы используют настоящие API; test-only callbacks позволяют изолировать UI-тесты.
 */
@Composable
fun HotelApp(
    state: BrowseState,
    onQuery: (SearchQuery) -> Unit,
    onFavorite: (String) -> Unit,
    onReload: () -> Unit,
    health: FoundationState,
    onHealthRefresh: () -> Unit,
    auth: AuthState = AuthState(),
    onLogin: (String,String)->Unit = { _,_ -> },
    onRegister: (String,String,String,String,String)->Unit = { _,_,_,_,_ -> },
    onUpdateProfile: (String,String)->Unit = { _,_ -> },
    onLogout: ()->Unit = {},
    onAuthRefresh: ()->Unit = {},
    onAdminSummary: ()->Unit = {},
    isUsb: Boolean = false,
    onConnection: (Boolean)->Unit = {},
    travel:TravelState? = null,
    onSearch:(SearchQuery,Boolean)->Unit = {_,_->},
    onAvailability:(String,SearchQuery)->Unit = {_,_->},
    onCreate:(CreateBooking)->Unit = {},
    onLoadBookings:(Boolean)->Unit = {},
    onPay:(String)->Unit = {},
    onCancel:(String)->Unit = {},
    onBookingRefresh:(String)->Unit = {},
    onReceipt:(String)->Unit = {},
    onPdfConsumed:()->Unit = {},
    onCreatedConsumed:()->Unit = {},
) {
    var connectionDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if(connectionDialog && ru.arzimurotov.hotel.BuildConfig.DEBUG) AlertDialog(
        onDismissRequest={connectionDialog=false},title={Text("Подключение к серверу")},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Запустите scripts/dev.py server. Для телефона также выполните scripts/dev.py usb.")
            OutlinedButton({onConnection(false);connectionDialog=false},Modifier.fillMaxWidth().testTag("connection_emulator")) { Text("Эмулятор · 10.0.2.2:8080") }
            OutlinedButton({onConnection(true);connectionDialog=false},Modifier.fillMaxWidth().testTag("connection_usb")) { Text("Телефон USB · 127.0.0.1:8080") }
            Text("Сейчас: "+if(isUsb) "USB" else "эмулятор")
            Text("При смене сервера аккаунт выйдет из системы. Каталог будет загружен заново.")
        }},confirmButton={TextButton({connectionDialog=false}) {Text("Закрыть")}})
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
            state.catalog == null ->
                Column(Modifier.fillMaxSize().testTag("catalog_error")) {
                    ScreenHeader("Гостиница")
                    EmptyPanel(
                        "Каталог не загрузился",
                        "Сервер недоступен. Проверьте запуск backend и выберите подключение.",
                        actionLabel = "Повторить",
                        onAction = onReload,
                    )
                    if(ru.arzimurotov.hotel.BuildConfig.DEBUG) TextButton({connectionDialog=true},Modifier.fillMaxWidth().testTag("connection_settings")) { Text("Настроить подключение") }
                }
            else -> {
                val catalog = state.catalog
                val nav = rememberNavController()
                var orderFocus by rememberSaveable { mutableStateOf<String?>(null) }
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
                LaunchedEffect(travel?.created?.id) {
                    if(travel?.created!=null) {orderFocus=travel.created.id;onCreatedConsumed();tab("bookings")}
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
                            if(state.error) Surface(color=MaterialTheme.colorScheme.secondaryContainer) {
                                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Text("Нет связи. Показаны ранее загруженные данные.",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                                    TextButton(onReload) {Text("Повторить")}
                                }
                            }
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
                                        LaunchedEffect(state.query) {if(travel!=null)onSearch(state.query,false)}
                                        ResultsScreen(
                                            state,
                                            wide,
                                            onQuery,
                                            ::back,
                                            ::hotel,
                                            onFavorite,
                                            server=travel,
                                            onMore={onSearch(state.query,true)},
                                            onRetry={onSearch(state.query,false)},
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
                                    composable("bookings") {
                                        if(travel==null) BookingsScreen {tab("search")}
                                        else OrdersScreen(auth,travel,{nav.navigate("auth")},{tab("search")},onLoadBookings,onPay,onCancel,onBookingRefresh,onReceipt,onPdfConsumed,orderFocus)
                                    }
                                    composable("profile") {
                                        AccountProfile(auth,state.favorites.size,{nav.navigate("favorites")},{nav.navigate("system")},
                                            {nav.navigate("auth")},onUpdateProfile,onLogout,onAuthRefresh,
                                            {onAdminSummary();nav.navigate("admin")},{connectionDialog=true})
                                    }
                                    composable("auth") {
                                        LaunchedEffect(auth.user?.id) { if(auth.user!=null) nav.popBackStack() }
                                        AuthForm(auth,onLogin,onRegister,::back)
                                    }
                                    composable("admin") {
                                        if(auth.user?.role=="ADMIN") AdminScreen(auth,::back,onAdminSummary)
                                        else EmptyPanel("Доступ закрыт","Войдите как администратор.",actionLabel="Назад",onAction=::back)
                                    }
                                    composable("system") {
                                        Column {
                                            ScreenHeader("Диагностика платформы", onBack = ::back)
                                            if(ru.arzimurotov.hotel.BuildConfig.DEBUG) TextButton({connectionDialog=true},Modifier.testTag("connection_settings")) {Text("Эмулятор / USB")}
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
                                        LaunchedEffect(id,state.query) {if(travel!=null && h!=null)onAvailability(id,state.query)}
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
                                                if(travel==null) h else h.copy(rooms=travel.availability?.takeIf {it.hotelId==id}?.offers.orEmpty().map {RoomOffer(RoomKind.valueOf(it.kind),it.capacity,it.area,it.nightlyPrice)}),
                                                state.query,
                                                id in state.favorites,
                                                { onFavorite(id) },
                                                ::back,
                                                { nav.navigate("gallery/$id/$it") },
                                                { nav.navigate("quote/$id/${it.name}") },
                                                { nav.navigate("place/$it") },
                                                live=travel!=null,
                                                checking=travel?.checking ?: false,
                                                availabilityError=travel?.availabilityError,
                                                onRetry={onAvailability(id,state.query)},
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
                                        if(h!=null && travel!=null) CheckoutScreen(catalog,h,state.query,e.arguments?.getString("kind").orEmpty(),travel,auth,::back,{nav.navigate("auth")},{onAvailability(h.id,state.query)},onCreate)
                                        else if (h != null && room != null)
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
