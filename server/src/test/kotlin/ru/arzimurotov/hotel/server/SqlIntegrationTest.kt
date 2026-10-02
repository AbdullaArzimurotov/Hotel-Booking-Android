package ru.arzimurotov.hotel.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.arzimurotov.hotel.domain.*

/** Запускается только scripts/dev.py test-sql. Имя одноразовой базы защищает от очистки основной.
 * Тест вообще не вызывает DROP/TRUNCATE/clean: проверяет Flyway fresh+upgrade/restart и seed. */
class SqlIntegrationTest {
    @Test fun migrationSeedPersistenceAndHttpSecurity() {
        val url=System.getenv("HOTEL_TEST_DB_URL")
        assumeTrue("Separate SQL test database not configured",url!=null)
        require(Regex("^jdbc:postgresql://127\\.0\\.0\\.1:55432/hotel_coursework_test_[0-9a-f]{12}$").matches(url!!))
        val config=ServerConfig.fromEnvironment(mapOf("DB_URL" to url,"DB_USER" to "hotel_app","DB_PASSWORD" to System.getenv("HOTEL_TEST_DB_PASSWORD")))
        val secret="integration-only-".repeat(5)
        DatabaseService(config).use { db ->
            val repo=CatalogRepository(db);val auth=AuthService(UserRepository(db),TokenService(secret))
            runBlocking {
                repo.seed(); repo.seed()
                val summary=repo.summary()
                assertEquals(listOf(3,6,36,432,30),listOf(summary.countries,summary.cities,summary.hotels,summary.rooms,summary.places))
                val hotel=repo.load().hotels.first()
                assertEquals(12,repo.rooms(hotel.id).size)
                db.query { c -> c.execute("UPDATE hotels SET name='Изменено администратором' WHERE id=?",uid(hotel.id)) }
                repo.seed()
                assertEquals("Изменено администратором",repo.load().hotel(hotel.id)!!.name)
                assertEquals(5,repo.hotelPlaces(hotel.id).size)
            }
            testApplication {
                application { hotelModule(db,repo,auth) }
                val api=createClient {install(ContentNegotiation){json()}}
                suspend fun post(path:String,value:Any)=api.post("/api/v1/"+path){contentType(ContentType.Application.Json);setBody(value)}
                val email="sql-test@example.com"
                // String body исключает динамическую сериализацию Any в тестовом клиенте.
                val request="""{"email":"  SQL-TEST@example.com ","password":"Sql-safe-test-123","fullName":"SQL Тест"}"""
                assertEquals(HttpStatusCode.Created,post("auth/register",request).status)
                assertEquals(HttpStatusCode.Conflict,post("auth/register",request).status)
                assertEquals(HttpStatusCode.BadRequest,post("auth/register","""{"email":"evil@example.com","password":"Sql-safe-test-123","fullName":"Тест","role":"ADMIN"}""").status)
                assertEquals(HttpStatusCode.Unauthorized,post("auth/login","""{"email":"sql-test@example.com","password":"wrong"}""").status)
                val login=post("auth/login","""{"email":"sql-test@example.com","password":"Sql-safe-test-123"}""")
                assertEquals(HttpStatusCode.OK,login.status)
                val session=login.body<AuthResponse>()
                assertEquals(email,session.user.email)
                assertEquals(HttpStatusCode.Forbidden,api.get("/api/v1/admin/summary"){bearerAuth(session.token)}.status)
                assertEquals(HttpStatusCode.Unauthorized,api.get("/api/v1/profile"){bearerAuth("broken")}.status)
                assertEquals(HttpStatusCode.BadRequest,api.patch("/api/v1/profile"){bearerAuth(session.token);contentType(ContentType.Application.Json);setBody("""{"fullName":"Тест","role":"ADMIN"}""")}.status)
                val edited=api.patch("/api/v1/profile"){bearerAuth(session.token);contentType(ContentType.Application.Json);setBody(ProfilePatch("Новое имя","+998901234567"))}
                assertEquals("Новое имя",edited.body<UserProfile>().fullName)
                val page=api.get("/api/v1/hotels?limit=20").body<Page<Hotel>>()
                assertEquals(36,page.total);assertEquals(20,page.items.size)
                assertEquals(HttpStatusCode.BadRequest,api.get("/api/v1/hotels?limit=101").status)
                assertEquals(HttpStatusCode.BadRequest,api.get("/api/v1/hotels/not-a-uuid").status)
                assertEquals(HttpStatusCode.NotFound,api.get("/api/v1/hotels/"+java.util.UUID.randomUUID()).status)
                val admin=auth.register(RegisterRequest("admin-test@example.com","Sql-safe-test-123","Админ"),"ADMIN")
                val adminToken=auth.login(LoginRequest(admin.email,"Sql-safe-test-123")).token
                assertEquals(HttpStatusCode.OK,api.get("/api/v1/admin/summary"){bearerAuth(adminToken)}.status)
                db.query { c -> c.execute("UPDATE users SET active=false WHERE id=?",uid(session.user.id)) }
                assertEquals(HttpStatusCode.Unauthorized,api.get("/api/v1/profile"){bearerAuth(session.token)}.status)
                assertEquals(HttpStatusCode.Unauthorized,api.get("/api/v1/profile"){bearerAuth(TokenService(secret,-10).issue(admin).token)}.status)
            }
        }
        DatabaseService(config).use { restarted ->
            runBlocking {
                val repo=CatalogRepository(restarted);repo.seed()
                assertEquals(432,repo.summary().rooms)
                assertEquals("Новое имя",UserRepository(restarted).byEmail("sql-test@example.com")!!.profile.fullName)
                assertEquals(5,restarted.query { c -> c.select("SELECT count(*) FROM flyway_schema_history WHERE success") { it.getInt(1) }.single() })
            }
        }
    }
}
