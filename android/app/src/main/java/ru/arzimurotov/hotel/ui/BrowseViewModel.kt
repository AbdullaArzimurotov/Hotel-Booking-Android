package ru.arzimurotov.hotel.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.arzimurotov.hotel.domain.*

/** Состояние каталога для Compose: данные, загрузка/ошибка, форма и id избранных гостиниц. */
data class BrowseState(
    val catalog: Catalog? = null,
    val loading: Boolean = true,
    val error: Boolean = false,
    val query: SearchQuery = SearchQuery(),
    val favorites: Set<String> = emptySet(),
)

/**
 * ViewModel каталога в MVVM: загружает CatalogRepository и обрабатывает действия UI.
 * MutableStateFlow закрыт внутри класса; экран получает только поток для чтения. SavedStateHandle
 * сохраняет параметры формы и избранное для восстановления Activity. Это не долговременная база
 * аккаунта: очистка данных приложения сбросит состояние. Поиск/расчёт остаются доменными функциями,
 * а не HTTP/SQL-логикой экранов.
 */
@HiltViewModel
class BrowseViewModel
@Inject
constructor(private val repository: CatalogRepository, private val saved: SavedStateHandle) :
    ViewModel() {
    private val mutableState =
        MutableStateFlow(
            BrowseState(
                query = restoreQuery(),
                favorites = saved.get<ArrayList<String>>("favorites")?.toSet().orEmpty(),
            )
        )
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        reload()
    }

    /** Повторная загрузка после ошибки; параллельные одинаковые запросы не запускаются. */
    fun reload() {
        if (loadJob?.isActive == true) return
        loadJob =
            viewModelScope.launch {
                mutableState.update { it.copy(loading = true, error = false) }
                try {
                    val catalog = repository.load()
                    val current=state.value.query
                    val country=catalog.countries.find { it.id==current.countryId || it.legacyId==current.countryId } ?: catalog.countries.first()
                    val city=current.cityId?.let { key -> catalog.cities.find { (it.id==key || it.legacyId==key) && it.countryId==country.id } ?: catalog.cities.firstOrNull { it.countryId==country.id } }
                    val favorites=state.value.favorites.mapNotNull { key -> catalog.hotels.find { it.id==key || it.legacyId==key }?.id }.toSet()
                    setQuery(current.copy(countryId=country.id,cityId=city?.id))
                    saved["favorites"]=ArrayList(favorites)
                    mutableState.update {
                        it.copy(
                            catalog = catalog,
                            loading = false,
                            favorites = favorites,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    // Отмена жизненного цикла не является ошибкой каталога.
                    throw cancelled
                } catch (error: Exception) {
                    // В debug журнале только тип ошибки: URL, SQL и персональные данные не выводятся.
                    if(ru.arzimurotov.hotel.BuildConfig.DEBUG) android.util.Log.w("HotelCatalog",error.javaClass.simpleName)
                    mutableState.update { it.copy(loading = false, error = true) }
                }
            }
    }

    /** Новый backend не должен получать данные/ответы предыдущего источника. */
    fun changeSource() {
        loadJob?.cancel()
        loadJob=null
        mutableState.update { it.copy(catalog=null,loading=true,error=false) }
        reload()
    }

    /**
     * Обновляет форму и сохраняет только совместимые с Bundle значения: примитивы и списки.
     * LocalDate записывается числом epochDay, enum — именем; объекты UI в state не хранятся.
     */
    fun setQuery(query: SearchQuery) {
        mutableState.update { it.copy(query = query) }
        saved["country"] = query.countryId
        saved["city"] = query.cityId
        saved["in"] = query.checkIn.toEpochDay()
        saved["out"] = query.checkOut.toEpochDay()
        saved["adults"] = query.adults
        saved["children"] = query.children
        saved["rooms"] = query.rooms
        saved["stars"] = ArrayList(query.filters.stars)
        saved["rating"] = query.filters.minRating
        saved["price"] = query.filters.maxPrice
        saved["moneyVersion"] = 1
        saved["amenities"] = ArrayList(query.filters.amenities.map { it.name })
        saved["roomKind"] = query.filters.roomKind?.name
        saved["distance"] = query.filters.maxDistanceKm
        saved["sort"] = query.sort.name
        saved["name"] = query.name
    }

    /** Переключает только существующий объект: неизвестный id не попадёт в избранное. */
    fun toggleFavorite(id: String) {
        if (state.value.catalog?.hotel(id) == null) return
        mutableState.update {
            it.copy(favorites = if (id in it.favorites) it.favorites - id else it.favorites + id)
        }
        saved["favorites"] = ArrayList(state.value.favorites)
    }

    /** Старые даты сбрасываются на будущие; неизвестные enum игнорируются безопасно. */
    private fun restoreQuery(): SearchQuery {
        val defaults = SearchQuery()
        val start = saved.get<Long>("in")?.let(LocalDate::ofEpochDay) ?: defaults.checkIn
        val end = saved.get<Long>("out")?.let(LocalDate::ofEpochDay) ?: defaults.checkOut
        val datesFresh = start >= LocalDate.now() && end > start
        return defaults.copy(
            countryId = saved["country"] ?: defaults.countryId,
            cityId = if (saved.contains("city")) saved["city"] else defaults.cityId,
            checkIn = if (datesFresh) start else defaults.checkIn,
            checkOut = if (datesFresh) end else defaults.checkOut,
            adults = saved["adults"] ?: 2,
            children = saved["children"] ?: 0,
            rooms = saved["rooms"] ?: 1,
            filters =
                SearchFilters(
                    saved.get<ArrayList<Int>>("stars")?.toSet().orEmpty(),
                    saved["rating"] ?: 0.0,
                    saved.get<Long>("price")?.let { value -> if(saved.get<Int>("moneyVersion")==1) value else runCatching { Math.multiplyExact(value,100L) }.getOrNull() },
                    saved
                        .get<ArrayList<String>>("amenities")
                        ?.mapNotNull { name -> Amenity.entries.find { it.name == name } }
                        ?.toSet()
                        .orEmpty(),
                    RoomKind.entries.find { it.name == saved.get<String>("roomKind") },
                    saved["distance"],
                ),
            sort =
                SortOrder.entries.find { it.name == saved.get<String>("sort") }
                    ?: SortOrder.RECOMMENDED,
            name = saved["name"] ?: "",
        )
    }
}
