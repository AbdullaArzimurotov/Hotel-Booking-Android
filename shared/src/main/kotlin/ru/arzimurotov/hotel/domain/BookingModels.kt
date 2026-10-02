package ru.arzimurotov.hotel.domain

import kotlinx.serialization.Serializable

/** Wire-модель поиска. Даты ISO-8601, деньги в минимальных единицах; цена клиента не авторитетна. */
@Serializable data class StayRequest(val countryId: String, val cityId: String? = null,
    val checkIn: String, val checkOut: String, val adults: Int = 2, val children: Int = 0,
    val rooms: Int = 1, val stars: Set<Int> = emptySet(), val minRating: Double = 0.0,
    val maxPrice: Long? = null, val amenities: Set<String> = emptySet(), val roomKind: String? = null,
    val maxDistanceKm: Double? = null, val name: String = "", val sort: String = "RECOMMENDED")
@Serializable data class AvailableOffer(val roomTypeId: String, val rateId: String, val kind: String,
    val capacity: Int, val area: Int, val nightlyPrice: Long, val stayTotal: Long, val currency: String,
    val availableCount: Int, val allowDemo: Boolean, val allowAtHotel: Boolean, val freeCancelHours: Int)
@Serializable data class SearchHit(val hotelId: String, val offers: List<AvailableOffer>)
@Serializable data class SearchResponse(val items: List<SearchHit>, val total: Int, val offset: Int,
    val limit: Int, val checkedAt: Long)
@Serializable data class Availability(val hotelId: String, val offers: List<AvailableOffer>, val checkedAt: Long,
    val services:List<HotelService> = emptyList())
@Serializable data class TransferDetails(val airport: String, val flight: String, val pickupAt: String, val phone: String)
@Serializable data class CreateBooking(val hotelId: String, val roomTypeId: String, val rateId: String,
    val stay: StayRequest, val serviceIds: Set<String> = emptySet(), val transfer: TransferDetails? = null,
    val paymentMethod: String, val expectedTotal: Long, val idempotencyKey: String)
@Serializable data class BookedService(val name: String, val total: Long, val transfer: TransferDetails? = null)
/** Неизменяемые реквизиты сохраняются при оформлении. Изменение каталога не переписывает историю. */
@Serializable data class BookingSnapshot(val hotelName: String, val address: String, val customer: String,
    val email: String, val kind: String, val roomNumbers: List<String>, val nightlyPrice: Long,
    val roomTotal: Long, val services: List<BookedService>, val timezone: String)
@Serializable data class Booking(val id: String, val number: String, val checkIn: String, val checkOut: String,
    val adults: Int, val children: Int, val status: String, val paymentMethod: String, val paymentStatus: String,
    val total: Long, val currency: String, val expiresAt: Long?, val cancelUntil: Long,
    val createdAt: Long, val cancellationReason: String?, val snapshot: BookingSnapshot,
    val receiptId: String? = null, val transactionId: String? = null, val serverNow: Long = 0)
@Serializable data class DemoPayment(val bookingId: String, val idempotencyKey: String)
