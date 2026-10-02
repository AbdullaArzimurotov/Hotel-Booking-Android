package ru.arzimurotov.hotel

import android.graphics.Color
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import ru.arzimurotov.hotel.ui.BrowseViewModel
import ru.arzimurotov.hotel.ui.FoundationViewModel
import ru.arzimurotov.hotel.ui.HotelApp
import ru.arzimurotov.hotel.ui.HotelTheme
import ru.arzimurotov.hotel.ui.AuthViewModel
import ru.arzimurotov.hotel.data.ApiConnection
import javax.inject.Inject

/**
 * Единственная launcher Activity. Hilt создаёт четыре ViewModel: SQL-каталог, аккаунт,
 * серверный поиск/заказы и независимая диагностика API. Подписка
 * collectAsStateWithLifecycle связывает StateFlow с жизненным циклом Activity; HotelApp получает
 * состояния и callbacks, не создаёт репозитории внутри composable.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: FoundationViewModel by viewModels()
    private val browseViewModel: BrowseViewModel by viewModels()
    private val authViewModel: AuthViewModel by viewModels()
    private val travelViewModel: ru.arzimurotov.hotel.ui.TravelViewModel by viewModels()
    @Inject lateinit var connection: ApiConnection

    override fun attachBaseContext(newBase: android.content.Context) {
        // Русский язык используется также в системных строках календаря Material.
        val locale = java.util.Locale.forLanguageTag("ru-RU")
        java.util.Locale.setDefault(locale)
        // Сохраняем исходный Activity baseContext. Подмена его createConfigurationContext
        // теряет Activity-привязку системного PrintManager (ошибка печати на Xiaomi).
        // Локаль задаём штатным override ресурсов до первого обращения к resources.
        super.attachBaseContext(newBase)
        val configuration = android.content.res.Configuration()
        configuration.setLocale(locale)
        applyOverrideConfiguration(configuration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            val account=authViewModel.state.collectAsStateWithLifecycle().value
            androidx.compose.runtime.LaunchedEffect(account.user?.id) {travelViewModel.clearAccount()}
            var isUsb by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(connection.isUsb) }
            HotelTheme {
                HotelApp(
                    browseViewModel.state.collectAsStateWithLifecycle().value,
                    browseViewModel::setQuery,
                    browseViewModel::toggleFavorite,
                    browseViewModel::reload,
                    viewModel.state.collectAsStateWithLifecycle().value,
                    viewModel::refresh,
                    authViewModel.state.collectAsStateWithLifecycle().value,
                    authViewModel::login,authViewModel::register,authViewModel::update,
                    authViewModel::logout,authViewModel::refresh,authViewModel::summary,
                    isUsb,
                    { usb ->
                        connection.selectUsb(usb)
                        isUsb=connection.isUsb
                        authViewModel.reset()
                        travelViewModel.reset()
                        browseViewModel.changeSource()
                        viewModel.refresh()
                    },
                    travel=travelViewModel.state.collectAsStateWithLifecycle().value,
                    onSearch=travelViewModel::search,onAvailability=travelViewModel::availability,
                    onCreate={r->account.user?.let {travelViewModel.create(it.id,r)}},
                    onLoadBookings=travelViewModel::loadBookings,
                    onPay={id->account.user?.let {travelViewModel.pay(it.id,id)}},
                    onCancel=travelViewModel::cancel,onBookingRefresh=travelViewModel::refresh,
                    onReceipt=travelViewModel::receipt,onPdfConsumed=travelViewModel::consumePdf,
                    onCreatedConsumed=travelViewModel::consumeCreated,
                )
            }
        }
    }
}
