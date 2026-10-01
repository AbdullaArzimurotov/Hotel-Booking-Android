package ru.arzimurotov.hotel.ui

import androidx.lifecycle.SavedStateHandle
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.arzimurotov.hotel.data.DemoCatalogRepository
import ru.arzimurotov.hotel.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun after() {
        Dispatchers.resetMain()
    }

    @Test
    fun `demo catalogue loads without backend`() =
        runTest(dispatcher) {
            val vm = BrowseViewModel(DemoCatalogRepository(), SavedStateHandle())
            assertTrue(vm.state.value.loading)
            advanceUntilIdle()
            assertEquals(36, vm.state.value.catalog!!.hotels.size)
            assertFalse(vm.state.value.error)
        }

    @Test
    fun `form filters and favorites survive state restoration`() =
        runTest(dispatcher) {
            val saved = SavedStateHandle()
            val vm = BrowseViewModel(DemoCatalogRepository(), saved)
            advanceUntilIdle()
            val q =
                vm.state.value.query.copy(
                    countryId = "tr",
                    cityId = "antalya",
                    adults = 3,
                    rooms = 2,
                    filters =
                        SearchFilters(setOf(5), 9.0, 20_000, setOf(Amenity.POOL), RoomKind.SUITE),
                    sort = SortOrder.PRICE,
                    name = "Оазис",
                )
            vm.setQuery(q)
            vm.toggleFavorite("antalya-6")
            val restored = BrowseViewModel(DemoCatalogRepository(), saved)
            advanceUntilIdle()
            assertEquals(q, restored.state.value.query)
            assertEquals(setOf("antalya-6"), restored.state.value.favorites)
            restored.toggleFavorite("antalya-6")
            assertTrue(restored.state.value.favorites.isEmpty())
            restored.toggleFavorite("not-a-hotel")
            assertTrue(restored.state.value.favorites.isEmpty())
        }

    @Test
    fun `all cities selection and stale dates restore correctly`() =
        runTest(dispatcher) {
            val saved =
                SavedStateHandle(
                    mapOf(
                        "country" to "ru",
                        "city" to null,
                        "in" to LocalDate.now().minusDays(3).toEpochDay(),
                        "out" to LocalDate.now().minusDays(1).toEpochDay(),
                    )
                )
            val vm = BrowseViewModel(DemoCatalogRepository(), saved)
            advanceUntilIdle()
            assertNull(vm.state.value.query.cityId)
            assertEquals("ru", vm.state.value.query.countryId)
            assertTrue(vm.state.value.query.checkIn >= LocalDate.now())
            assertTrue(vm.state.value.query.checkOut > vm.state.value.query.checkIn)
        }

    @Test
    fun `loading failure is recoverable and duplicate requests are prevented`() =
        runTest(dispatcher) {
            var fail = true
            var calls = 0
            val repository =
                object : CatalogRepository {
                    override suspend fun load(): Catalog {
                        calls++
                        if (fail) throw IOException()
                        return DemoCatalogRepository.createCatalog()
                    }
                }
            val vm = BrowseViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            assertTrue(vm.state.value.error)
            fail = false
            vm.reload()
            vm.reload()
            advanceUntilIdle()
            assertEquals(2, calls)
            assertFalse(vm.state.value.error)
            assertEquals(36, vm.state.value.catalog!!.hotels.size)
        }

    @Test
    fun `cancelled load is not presented as catalogue failure`() =
        runTest(dispatcher) {
            val vm =
                BrowseViewModel(
                    object : CatalogRepository {
                        override suspend fun load(): Catalog {
                            throw kotlinx.coroutines.CancellationException()
                        }
                    },
                    SavedStateHandle(),
                )
            advanceUntilIdle()
            assertFalse(vm.state.value.error)
        }
}
