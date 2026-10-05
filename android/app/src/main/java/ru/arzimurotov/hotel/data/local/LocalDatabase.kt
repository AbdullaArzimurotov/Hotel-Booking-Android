package ru.arzimurotov.hotel.data.local

import androidx.room.*

/**
 * Справочные объекты сериализуются по одному, а не одним blob всей базы. Индекс kind обеспечивает
 * разделение стран/городов/гостиниц/мест; active сохраняет архивные записи. Остатки, интервалы,
 * пользователи и заказы вынесены в отдельные SQL-таблицы ниже.
 */
@Entity(tableName = "catalog_entries", indices = [Index("kind")])
data class CatalogEntry(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val payload: String,
    val active: Boolean = true,
    val version: Int = 1,
)

@Entity(tableName = "local_users", indices = [Index(value = ["email"], unique = true)])
data class LocalUser(
    @PrimaryKey val id: String,
    val email: String,
    val name: String,
    val phone: String?,
    val role: String,
    val passwordHash: String,
    val recoveryHash: String? = null,
    val sessionVersion: Int = 1,
    val active: Boolean = true,
)

@Entity(tableName = "room_types", indices = [Index(value = ["hotelId", "kind"], unique = true)])
data class LocalType(
    @PrimaryKey val id: String,
    val hotelId: String,
    val kind: String,
    val capacity: Int,
    val area: Int,
    val amenities: String,
    val active: Boolean = true,
)

@Entity(
    tableName = "rooms",
    indices = [Index(value = ["typeId", "number"], unique = true)],
    foreignKeys =
        [
            ForeignKey(
                entity = LocalType::class,
                parentColumns = ["id"],
                childColumns = ["typeId"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
)
data class LocalRoom(
    @PrimaryKey val id: String,
    val typeId: String,
    val number: String,
    val active: Boolean = true,
)

@Entity(
    tableName = "rates",
    indices = [Index("typeId")],
    foreignKeys =
        [
            ForeignKey(
                entity = LocalType::class,
                parentColumns = ["id"],
                childColumns = ["typeId"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
)
data class LocalRate(
    @PrimaryKey val id: String,
    val typeId: String,
    val amount: Long,
    val currency: String,
    val start: String,
    val end: String,
    val demo: Boolean = true,
    val atHotel: Boolean = true,
    val freeCancelHours: Int = 24,
    val active: Boolean = true,
)

@Entity(tableName = "hotel_services", indices = [Index("hotelId")])
data class LocalService(
    @PrimaryKey val id: String,
    val hotelId: String,
    val code: String,
    val name: String,
    val description: String,
    val amount: Long,
    val perNight: Boolean,
    val active: Boolean = true,
)

@Entity(
    tableName = "room_blocks",
    indices = [Index("roomId")],
    foreignKeys =
        [
            ForeignKey(
                entity = LocalRoom::class,
                parentColumns = ["id"],
                childColumns = ["roomId"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
)
data class LocalBlock(
    @PrimaryKey val id: String,
    val roomId: String,
    val start: String,
    val end: String,
    val reason: String,
    val active: Boolean = true,
)

@Entity(
    tableName = "bookings",
    indices = [Index(value = ["ownerId", "key"], unique = true), Index("hotelId")],
)
data class LocalOrder(
    @PrimaryKey val id: String,
    val ownerId: String,
    val hotelId: String,
    val typeId: String,
    val start: String,
    val end: String,
    val key: String,
    val fingerprint: String,
    val payload: String,
)

@Entity(
    tableName = "booking_rooms",
    primaryKeys = ["bookingId", "roomId"],
    indices = [Index("roomId")],
    foreignKeys =
        [
            ForeignKey(
                entity = LocalOrder::class,
                parentColumns = ["id"],
                childColumns = ["bookingId"],
                onDelete = ForeignKey.RESTRICT,
            ),
            ForeignKey(
                entity = LocalRoom::class,
                parentColumns = ["id"],
                childColumns = ["roomId"],
                onDelete = ForeignKey.RESTRICT,
            ),
        ],
)
data class LocalAllocation(val bookingId: String, val roomId: String)

@Entity(
    tableName = "payments",
    indices =
        [
            Index(value = ["bookingId"], unique = true),
            Index(value = ["ownerId", "key"], unique = true),
        ],
)
data class LocalPayment(
    @PrimaryKey val id: String,
    val bookingId: String,
    val ownerId: String,
    val key: String,
    val transactionId: String,
    val amount: Long,
    val currency: String,
    val paidAt: Long,
    val status: String = "PAID",
)

@Entity(tableName = "receipts", indices = [Index(value = ["bookingId"], unique = true)])
data class LocalReceipt(
    @PrimaryKey val id: String,
    val bookingId: String,
    val ownerId: String,
    val payload: String,
)

@Entity(
    tableName = "hotel_places",
    primaryKeys = ["hotelId", "placeId"],
    indices = [Index("placeId")],
)
data class LocalHotelPlace(val hotelId: String, val placeId: String)

@Entity(tableName = "audit")
data class LocalAudit(
    @PrimaryKey val id: String,
    val actor: String,
    val action: String,
    val target: String,
    val createdAt: Long,
)

/** DAO вызывается на IO либо внутри withTransaction. Денежные суммы INTEGER/Long: без REAL. */
@Dao
interface LocalDao {
    @Query("SELECT * FROM catalog_entries ORDER BY title,id")
    suspend fun entries(): List<CatalogEntry>

    @Query("SELECT * FROM catalog_entries WHERE id=:id")
    suspend fun entry(id: String): CatalogEntry?

    @Upsert suspend fun put(v: CatalogEntry)

    @Query("SELECT * FROM local_users WHERE email=:email")
    suspend fun userEmail(email: String): LocalUser?

    @Query("SELECT * FROM local_users WHERE id=:id") suspend fun user(id: String): LocalUser?

    @Query("SELECT count(*) FROM local_users") suspend fun userCount(): Int

    @Upsert suspend fun put(v: LocalUser)

    @Query("SELECT * FROM room_types ORDER BY id") suspend fun types(): List<LocalType>

    @Upsert suspend fun put(v: LocalType)

    @Query("SELECT * FROM rooms ORDER BY id") suspend fun rooms(): List<LocalRoom>

    @Upsert suspend fun put(v: LocalRoom)

    @Query("SELECT * FROM rates ORDER BY start,id") suspend fun rates(): List<LocalRate>

    @Upsert suspend fun put(v: LocalRate)

    @Query("SELECT * FROM hotel_services ORDER BY id") suspend fun services(): List<LocalService>

    @Upsert suspend fun put(v: LocalService)

    @Query("SELECT * FROM room_blocks ORDER BY start,id") suspend fun blocks(): List<LocalBlock>

    @Upsert suspend fun put(v: LocalBlock)

    @Query("SELECT * FROM bookings ORDER BY start,id") suspend fun orders(): List<LocalOrder>

    @Query("SELECT * FROM bookings WHERE id=:id") suspend fun order(id: String): LocalOrder?

    @Upsert suspend fun put(v: LocalOrder)

    @Query("SELECT * FROM booking_rooms") suspend fun allocations(): List<LocalAllocation>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun put(v: LocalAllocation)

    @Query("SELECT * FROM payments WHERE bookingId=:id")
    suspend fun payment(id: String): LocalPayment?

    @Upsert suspend fun put(v: LocalPayment)

    @Query("SELECT * FROM receipts WHERE id=:id") suspend fun receipt(id: String): LocalReceipt?

    @Upsert suspend fun put(v: LocalReceipt)

    @Query("SELECT * FROM hotel_places") suspend fun links(): List<LocalHotelPlace>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun put(v: LocalHotelPlace)

    @Query("DELETE FROM hotel_places WHERE hotelId=:id") suspend fun clearLinks(id: String)

    @Insert suspend fun put(v: LocalAudit)

    @Query("SELECT * FROM audit ORDER BY createdAt DESC LIMIT 100")
    suspend fun audit(): List<LocalAudit>
}

/**
 * Новая независимая БД: старые файлы сеанса 0.6.0 и сервер PostgreSQL не удаляются. Разрушительные
 * миграции запрещены; схемы Room экспортируются в Git для последующих версий.
 */
@Database(
    entities =
        [
            CatalogEntry::class,
            LocalUser::class,
            LocalType::class,
            LocalRoom::class,
            LocalRate::class,
            LocalService::class,
            LocalBlock::class,
            LocalOrder::class,
            LocalAllocation::class,
            LocalPayment::class,
            LocalReceipt::class,
            LocalHotelPlace::class,
            LocalAudit::class,
        ],
    version = 1,
    exportSchema = true,
)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun dao(): LocalDao
}
