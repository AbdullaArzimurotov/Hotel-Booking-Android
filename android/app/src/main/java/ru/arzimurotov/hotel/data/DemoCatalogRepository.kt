package ru.arzimurotov.hotel.data

import ru.arzimurotov.hotel.domain.CatalogRepository
import ru.arzimurotov.hotel.domain.SeedCatalog

/** Детерминированный источник только для тестов; production DI использует REST. */
class DemoCatalogRepository : CatalogRepository {
    override suspend fun load() = createCatalog()
    companion object { fun createCatalog() = SeedCatalog.createCatalog() }
}
