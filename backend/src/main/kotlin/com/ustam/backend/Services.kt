package com.ustam.backend

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun encodeStringList(list: List<String>): String = json.encodeToString(ListSerializer(String.serializer()), list)
fun decodeStringList(raw: String): List<String> =
    if (raw.isBlank()) emptyList() else json.decodeFromString(ListSerializer(String.serializer()), raw)

fun encodeAreas(map: Map<String, List<String>>): String =
    json.encodeToString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), map)
fun decodeAreas(raw: String): Map<String, List<String>> =
    if (raw.isBlank()) emptyMap() else json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), raw)

/** Never expose a raw username to a counterparty — matches the masking rule proven on the Django site. */
fun displayName(firstName: String, lastName: String, username: String): String {
    if (firstName.isNotBlank() && lastName.isNotBlank()) {
        return "$firstName ${lastName.first()}."
    }
    if (firstName.isNotBlank()) return firstName
    return username
}

fun buildFullAddress(district: String, neighborhood: String, street: String, buildingNo: String, apartmentNo: String): String {
    val parts = listOfNotNull(
        district.takeIf { it.isNotBlank() },
        neighborhood.takeIf { it.isNotBlank() },
        street.takeIf { it.isNotBlank() },
    )
    var full = parts.joinToString(", ")
    if (buildingNo.isNotBlank()) full += " No:$buildingNo"
    if (apartmentNo.isNotBlank()) full += "/D:$apartmentNo"
    return full
}

fun publicUserFor(userId: Int): PublicUser = transaction {
    val row = Users.selectAll().where { Users.id eq userId }.single()
    val ratingsForUser = Ratings.selectAll().where { Ratings.providerId eq userId }.toList()
    val avgRating = if (ratingsForUser.isNotEmpty()) ratingsForUser.map { it[Ratings.score] }.average() else null
    val completedCount = Jobs.selectAll().where { (Jobs.providerId eq userId) and (Jobs.status eq JobStatus.COMPLETED) }.count()
    PublicUser(
        id = row[Users.id].value,
        displayName = displayName(row[Users.firstName], row[Users.lastName], row[Users.username]),
        isVerifiedUsta = row[Users.isVerifiedUsta],
        avgRating = avgRating,
        completedJobs = completedCount.toInt(),
    )
}

object JobStatus {
    const val OPEN = "open"
    const val ACTIVE = "active"
    const val COMPLETED = "completed"
    const val CANCELLED = "cancelled"
}

object ProposalStatus {
    const val PENDING = "pending"
    const val ACCEPTED = "accepted"
    const val REJECTED = "rejected"
}

class ScheduleConflictError(message: String) : Exception(message)

object JobServices {

    /** ISO date strings this provider is already booked on, based on an ACCEPTED proposal
     * whose job is currently ACTIVE. Fetches broadly then filters in Kotlin to sidestep
     * Exposed's EntityID-column query-composition quirks. */
    fun getProviderBusyDates(providerId: Int, excludeJobId: Int? = null): Set<String> = transaction {
        (Proposals innerJoin Jobs)
            .selectAll()
            .where {
                (Proposals.providerId eq providerId) and
                    (Proposals.status eq ProposalStatus.ACCEPTED) and
                    (Jobs.status eq JobStatus.ACTIVE)
            }
            .toList()
            .filter { excludeJobId == null || it[Jobs.id].value != excludeJobId }
            .mapNotNull { it[Proposals.proposedDate]?.toString() }
            .toSet()
    }

    /** True if the provider is booked on every date this job's customer offered —
     * i.e. there is no free date left for them to propose at all. */
    fun hasScheduleConflict(providerId: Int, jobId: Int, jobAvailableDates: List<String>): Boolean {
        if (jobAvailableDates.isEmpty()) return false
        val busy = getProviderBusyDates(providerId, excludeJobId = jobId)
        return jobAvailableDates.toSet().all { it in busy }
    }

    /** True if this one specific date is already booked for the provider. */
    fun hasDateConflict(providerId: Int, jobId: Int, date: String?): Boolean {
        if (date.isNullOrBlank()) return false
        return date in getProviderBusyDates(providerId, excludeJobId = jobId)
    }

    /** Accept a proposal: job goes active, sibling proposals rejected — all in one transaction
     * (the Django version had this same operation NOT wrapped atomically; fixed here from the start). */
    fun acceptProposal(proposalId: Int) {
        transaction {
            val proposalRow = Proposals.selectAll().where { Proposals.id eq proposalId }.singleOrNull()
                ?: throw NoSuchElementException("Proposal not found")
            val jobId = proposalRow[Proposals.jobId].value
            val providerId = proposalRow[Proposals.providerId].value
            val proposedDate = proposalRow[Proposals.proposedDate]?.toString()

            if (hasDateConflict(providerId, jobId, proposedDate)) {
                throw ScheduleConflictError("Bu ustanın seçilen tarihte çakışan başka bir işi var.")
            }

            Jobs.update({ Jobs.id eq jobId }) {
                it[Jobs.providerId] = providerId
                it[Jobs.status] = JobStatus.ACTIVE
                it[Jobs.updatedAt] = LocalDateTime.now()
            }
            Proposals.update({ Proposals.id eq proposalId }) {
                it[Proposals.status] = ProposalStatus.ACCEPTED
            }
            Proposals.update({ (Proposals.jobId eq jobId) and (Proposals.id neq proposalId) }) {
                it[Proposals.status] = ProposalStatus.REJECTED
            }
        }
    }

    fun completeJob(jobId: Int) = transaction {
        Jobs.update({ Jobs.id eq jobId }) {
            it[status] = JobStatus.COMPLETED
            it[updatedAt] = LocalDateTime.now()
        }
    }

    fun cancelJob(jobId: Int) = transaction {
        Jobs.update({ Jobs.id eq jobId }) {
            it[status] = JobStatus.CANCELLED
            it[updatedAt] = LocalDateTime.now()
        }
    }

    /** Mirrors Django's get_open_jobs_for_provider: unavailable providers see nothing;
     * providers with no category/area preference set fall back to seeing everything open. */
    fun getOpenJobsForProvider(providerId: Int): List<ResultRow> = transaction {
        val provider = Users.selectAll().where { Users.id eq providerId }.single()
        if (!provider[Users.isAvailable]) return@transaction emptyList()

        val alreadyProposed = Proposals
            .selectAll().where { Proposals.providerId eq providerId }
            .map { it[Proposals.jobId].value }
            .toSet()

        val categories = decodeStringList(provider[Users.serviceCategories])
        val areas = decodeAreas(provider[Users.serviceAreas])

        Jobs.selectAll().where { Jobs.status eq JobStatus.OPEN }
            .toList()
            .filter { row -> row[Jobs.id].value !in alreadyProposed }
            .filter { row ->
                val categoryOk = categories.isEmpty() || row[Jobs.category] in categories
                val areaOk = areas.isEmpty() || areas.keys.any { district -> row[Jobs.location].contains(district, ignoreCase = true) }
                categoryOk && areaOk
            }
    }
}
