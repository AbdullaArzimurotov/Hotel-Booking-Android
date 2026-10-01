package ru.arzimurotov.hotel

import android.graphics.Color
import android.os.Bundle
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

/**
 * Единственная launcher Activity. Hilt создаёт две независимые ViewModel: BrowseViewModel для
 * локального каталога и FoundationViewModel для диагностики API. Подписка
 * collectAsStateWithLifecycle связывает StateFlow с жизненным циклом Activity; HotelApp получает
 * состояния и callbacks, не создаёт репозитории внутри composable.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: FoundationViewModel by viewModels()
    private val browseViewModel: BrowseViewModel by viewModels()

    override fun attachBaseContext(newBase: android.content.Context) {
        // Русский язык используется также в системных строках календаря Material.
        val locale = java.util.Locale.forLanguageTag("ru-RU")
        java.util.Locale.setDefault(locale)
        val configuration = android.content.res.Configuration(newBase.resources.configuration)
        configuration.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            HotelTheme {
                HotelApp(
                    browseViewModel.state.collectAsStateWithLifecycle().value,
                    browseViewModel::setQuery,
                    browseViewModel::toggleFavorite,
                    browseViewModel::reload,
                    viewModel.state.collectAsStateWithLifecycle().value,
                    viewModel::refresh,
                )
            }
        }
    }
}
