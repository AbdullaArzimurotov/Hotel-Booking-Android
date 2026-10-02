package ru.arzimurotov.hotel.domain

import kotlinx.serialization.Serializable

/** Общий wire-контракт Android/Ktor. Денежные Long передаются в минимальных единицах валюты. */
@Serializable data class Page<T>(val items: List<T>, val total: Int, val offset: Int, val limit: Int)
@Serializable data class UserProfile(val id: String, val email: String, val fullName: String, val phone: String?, val role: String)
@Serializable data class RegisterRequest(val email: String, val password: String, val fullName: String, val phone: String? = null)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class ProfilePatch(val fullName: String, val phone: String? = null)
@Serializable data class AuthResponse(val token: String, val expiresAt: Long, val user: UserProfile)
@Serializable data class ApiError(val code: String, val message: String, val fieldErrors: Map<String, String> = emptyMap(), val requestId: String = "")
@Serializable data class AdminSummary(val countries: Int, val cities: Int, val hotels: Int, val rooms: Int, val places: Int, val users: Int)
@Serializable data class PhysicalRoom(val id: String, val roomTypeId: String, val number: String, val offer: RoomOffer, val currency: String, val availabilityGuaranteed: Boolean = false)
