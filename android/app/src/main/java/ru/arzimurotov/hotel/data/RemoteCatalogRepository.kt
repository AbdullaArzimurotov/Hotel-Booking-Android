package ru.arzimurotov.hotel.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.arzimurotov.hotel.domain.*

/** Только REST: ошибка не заменяется вымышленным успехом. Списки загружаются постранично;
 * лимит сервера 100 соблюдается. Снимок согласуется по ссылкам; старый RAM сохраняет ViewModel.
 * Постоянный кэш Room и серверный поиск по датам появятся на следующих этапах. */
class RemoteCatalogRepository(private val client: HttpClient, private val baseUrl: ()->String) : CatalogRepository {
    constructor(client: HttpClient, connection: ApiConnection) : this(client,{connection.baseUrl})
    private suspend inline fun <reified T> pages(base: String, path: String): List<T> {
        val result=mutableListOf<T>()
        do {
            val response=client.get(base+"api/v1/"+path) { parameter("offset",result.size); parameter("limit",100) }
            check(response.status==HttpStatusCode.OK) { "Catalog API unavailable" }
            val page=response.body<Page<T>>()
            check(page.offset==result.size && page.limit==100 && page.total in 0..100000)
            check(page.items.isNotEmpty() || result.size>=page.total) { "Invalid pagination" }
            result.addAll(page.items)
        } while(result.size<page.total)
        return result
    }
    override suspend fun load(): Catalog = coroutineScope {
        val base=baseUrl()
        val countries=async { pages<Country>(base,"locations/countries") }
        val cities=async { pages<City>(base,"locations/cities") }
        val hotels=async { pages<Hotel>(base,"hotels") }
        val places=async { pages<Place>(base,"places") }
        val data=Catalog(countries.await(),cities.await(),hotels.await(),places.await())
        check(data.countries.isNotEmpty() && data.cities.isNotEmpty())
        check(data.hotels.all { h -> h.rooms.isNotEmpty() && data.cities.any { it.id==h.cityId } })
        data
    }
}
