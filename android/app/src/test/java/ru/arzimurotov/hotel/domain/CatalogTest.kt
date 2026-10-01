package ru.arzimurotov.hotel.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import ru.arzimurotov.hotel.data.DemoCatalogRepository

class CatalogTest {
    private val catalog = DemoCatalogRepository.createCatalog()
    private val today = LocalDate.of(2026, 10, 1)
    private val query = SearchQuery(checkIn = today.plusDays(7), checkOut = today.plusDays(10))

    @Test
    fun `catalog contains 36 unique hotels in six real cities`() {
        assertEquals(3, catalog.countries.size)
        assertEquals(6, catalog.cities.size)
        assertEquals(36, catalog.hotels.size)
        assertEquals(36, catalog.hotels.map { it.id }.toSet().size)
        catalog.cities.forEach { city ->
            (3..5).forEach { stars ->
                assertEquals(2, catalog.hotels.count { it.cityId == city.id && it.stars == stars })
            }
        }
        assertEquals(30, catalog.places.size)
        catalog.hotels.forEach {
            assertEquals(3, it.photos.size)
            assertEquals(3, it.rooms.size)
            assertEquals(3, it.services.size)
        }
    }

    @Test
    fun `country search never mixes currencies`() {
        catalog.countries.forEach { country ->
            val results = catalog.search(query.copy(countryId = country.id, cityId = null))
            assertEquals(12, results.size)
            assertTrue(results.all { catalog.city(it.cityId).countryId == country.id })
        }
    }

    @Test
    fun `star amenity rating and room filters are combined`() {
        val results =
            catalog.search(
                query.copy(
                    filters =
                        SearchFilters(
                            stars = setOf(5),
                            minRating = 9.0,
                            amenities = setOf(Amenity.SPA),
                            roomKind = RoomKind.SUITE,
                        )
                )
            )
        assertEquals(2, results.size)
        assertTrue(results.all { it.stars == 5 && it.rating >= 9 && Amenity.SPA in it.amenities })
    }

    @Test
    fun `price filter and sorting use suitable room not minimum advertised room`() {
        val q = query.copy(adults = 3, sort = SortOrder.PRICE)
        val results = catalog.search(q)
        assertEquals(
            results.map { it.matchingPrice(q) }.sorted(),
            results.map { it.matchingPrice(q) },
        )
        val h = results.first()
        assertTrue(h.matchingPrice(q) > h.startingPrice)
        assertEquals(
            0,
            catalog.search(q.copy(filters = SearchFilters(maxPrice = h.startingPrice))).size,
        )
    }

    @Test
    fun `name lookup is trimmed case insensitive and allows empty results`() {
        assertEquals(1, catalog.search(query.copy(name = "  гРАНд  ")).size)
        assertTrue(catalog.search(query.copy(name = "несуществующий вариант")).isEmpty())
    }

    @Test
    fun `date range accepts checkout exclusive and rejects invalid dates`() {
        assertNull(query.validationError(catalog, today))
        assertNotNull(query.copy(checkIn = today.minusDays(1)).validationError(catalog, today))
        assertNotNull(query.copy(checkOut = query.checkIn).validationError(catalog, today))
        assertNotNull(
            query.copy(checkOut = query.checkIn.plusDays(91)).validationError(catalog, today)
        )
        assertNull(
            query.copy(checkOut = query.checkIn.plusDays(90)).validationError(catalog, today)
        )
    }

    @Test
    fun `guest counts and country city integrity are validated`() {
        assertNotNull(
            query.copy(countryId = "ru", cityId = "tashkent").validationError(catalog, today)
        )
        assertNotNull(query.copy(adults = 0).validationError(catalog, today))
        assertNotNull(query.copy(rooms = 3).validationError(catalog, today))
        assertNotNull(
            query.copy(adults = 4, children = 2, rooms = 1).validationError(catalog, today)
        )
        assertNull(query.copy(adults = 4, children = 2, rooms = 2).validationError(catalog, today))
        assertEquals(3, query.copy(adults = 4, children = 2, rooms = 2).guestsPerRoom)
    }

    @Test
    fun `nightly services multiply by rooms and nights single transfer does not`() {
        val h = catalog.hotels.first()
        val room = h.rooms.first()
        val q = query.copy(rooms = 2)
        val services = h.services.filter { it.id in setOf("breakfast", "transfer") }
        val expected =
            room.pricePerNight * 3 * 2 +
                h.services.first { it.id == "breakfast" }.price * 3 * 2 +
                h.services.first { it.id == "transfer" }.price
        assertEquals(expected, previewTotal(room, q, services))
        assertEquals(room.pricePerNight * 3 * 2, previewTotal(room, q, emptyList()))
    }

    @Test
    fun `deterministic distance and rating order`() {
        val rating = catalog.search(query.copy(sort = SortOrder.RATING)).map { it.rating }
        val distance = catalog.search(query.copy(sort = SortOrder.DISTANCE)).map { it.distanceKm }
        assertEquals(rating.sortedDescending(), rating)
        assertEquals(distance.sorted(), distance)
    }
}
