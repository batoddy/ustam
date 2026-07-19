package com.ustam.backend

import kotlinx.serialization.Serializable

// ── Auth ──────────────────────────────────────────────
@Serializable
data class SignUpRequest(
    val username: String,
    val email: String,
    val password: String,
    val firstName: String,
    val lastName: String,
    val role: String, // "customer" | "provider"
    val phone: String = "",
)

@Serializable
data class LoginRequest(val usernameOrEmail: String, val password: String)

@Serializable
data class TokenPair(val accessToken: String, val refreshToken: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

// ── Users ─────────────────────────────────────────────
@Serializable
data class MeResponse(
    val id: Int,
    val username: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val role: String,
    val phone: String,
    val isVerifiedUsta: Boolean,
    val isAvailable: Boolean,
    val serviceCategories: List<String>,
    val serviceAreas: Map<String, List<String>>,
)

@Serializable
data class PublicUser(
    val id: Int,
    val displayName: String,
    val isVerifiedUsta: Boolean,
    val avgRating: Double? = null,
    val completedJobs: Int? = null,
)

@Serializable
data class EditProfileRequest(
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val phone: String? = null,
)

@Serializable
data class CategoriesRequest(val serviceCategories: List<String>)

@Serializable
data class AreasRequest(val serviceAreas: Map<String, List<String>>)

// ── Addresses ─────────────────────────────────────────
@Serializable
data class AddressDto(
    val id: Int = 0,
    val label: String = "ev",
    val district: String,
    val neighborhood: String = "",
    val street: String = "",
    val buildingNo: String = "",
    val apartmentNo: String = "",
    val isDefault: Boolean = false,
    val fullAddress: String = "",
)

// ── Jobs ──────────────────────────────────────────────
@Serializable
data class CreateJobRequest(
    val category: String,
    val subcategory: String = "",
    val title: String,
    val description: String,
    val availableDates: List<String>,
    val addressId: Int? = null,
    val customLocation: String? = null,
)

@Serializable
data class JobSummary(
    val id: Int,
    val title: String,
    val category: String,
    val subcategory: String,
    val location: String,
    val availableDates: List<String>,
    val status: String,
    val proposalCount: Int = 0,
    val provider: PublicUser? = null,
    val customer: PublicUser? = null,
)

@Serializable
data class ProposalDto(
    val id: Int,
    val jobId: Int,
    val provider: PublicUser,
    val message: String,
    val price: String,
    val proposedDate: String? = null,
    val proposedTime: String? = null,
    val durationHours: String? = null,
    val status: String,
)

@Serializable
data class JobDetail(
    val id: Int,
    val title: String,
    val description: String,
    val category: String,
    val subcategory: String,
    val location: String,
    val availableDates: List<String>,
    val status: String,
    val customer: PublicUser,
    val provider: PublicUser? = null,
    val isOwner: Boolean,
    val myProposal: ProposalDto? = null,
    val hasConflict: Boolean = false,
    val availableDatesForMe: List<String>? = null,
    val canMessage: Boolean = false,
    val unreadCount: Int = 0,
    val hasRating: Boolean = false,
    val proposals: List<ProposalDto>? = null,
)

@Serializable
data class CreateProposalRequest(
    val price: String,
    val proposedDate: String? = null,
    val proposedTime: String? = null,
    val durationHours: String? = null,
    val message: String = "",
)

@Serializable
data class RateJobRequest(val score: Int, val comment: String = "")

@Serializable
data class JobHistoryResponse(
    val jobs: List<JobSummary>,
    val filter: String,
    val counts: Map<String, Int>,
    val totalSpent: String,
    val avgRating: Double?,
)

// ── Messages ──────────────────────────────────────────
@Serializable
data class ConversationDto(
    val job: JobSummary,
    val counterpart: PublicUser,
    val lastMessage: MessageDto? = null,
    val unreadCount: Int,
)

@Serializable
data class MessageDto(
    val id: Int,
    val senderId: Int,
    val body: String,
    val createdAt: String,
    val isMine: Boolean,
)

@Serializable
data class SendMessageRequest(val body: String)

// ── Generic ───────────────────────────────────────────
@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class OkResponse(val ok: Boolean = true, val message: String = "")
