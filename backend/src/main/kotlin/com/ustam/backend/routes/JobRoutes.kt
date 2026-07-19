package com.ustam.backend.routes

import com.ustam.backend.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private fun ResultRow.toJobSummary(proposalCount: Int = 0, includeParties: Boolean = true): JobSummary = JobSummary(
    id = this[Jobs.id].value,
    title = this[Jobs.title],
    category = this[Jobs.category],
    subcategory = this[Jobs.subcategory],
    location = this[Jobs.location],
    availableDates = decodeStringList(this[Jobs.availableDates]),
    status = this[Jobs.status],
    proposalCount = proposalCount,
    provider = if (includeParties) this[Jobs.providerId]?.let { publicUserFor(it.value) } else null,
    customer = if (includeParties) publicUserFor(this[Jobs.customerId].value) else null,
)

private fun ResultRow.toProposalDto(): ProposalDto = ProposalDto(
    id = this[Proposals.id].value,
    jobId = this[Proposals.jobId].value,
    provider = publicUserFor(this[Proposals.providerId].value),
    message = this[Proposals.message],
    price = this[Proposals.price].toPlainString(),
    proposedDate = this[Proposals.proposedDate]?.toString(),
    proposedTime = this[Proposals.proposedTime]?.toString(),
    durationHours = this[Proposals.durationHours]?.toPlainString(),
    status = this[Proposals.status],
)

private fun requireRole(call: ApplicationCall, role: String): Boolean {
    val userId = call.currentUserId()
    val actualRole = transaction { Users.selectAll().where { Users.id eq userId }.single()[Users.role] }
    return actualRole == role
}

fun Route.jobRoutes() {
    route("/jobs") {
        post {
            val userId = call.currentUserId()
            if (!requireRole(call, "customer")) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Bu işlem sadece müşteriler için."))
                return@post
            }
            val req = call.receive<CreateJobRequest>()

            val dates = req.availableDates.map { it.trim() }.filter { it.isNotBlank() }.toSet()
            if (dates.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("En az bir tarih seçmelisin."))
                return@post
            }
            try {
                dates.forEach { LocalDate.parse(it) }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Geçersiz tarih formatı (YYYY-MM-DD olmalı)."))
                return@post
            }
            val sortedDates = dates.sorted()

            val location = when {
                req.addressId != null -> transaction {
                    val addr = Addresses.selectAll().where { (Addresses.id eq req.addressId) and (Addresses.userId eq userId) }.firstOrNull()
                        ?: throw NoSuchElementException("Adres bulunamadı")
                    buildFullAddress(
                        addr[Addresses.district], addr[Addresses.neighborhood], addr[Addresses.street],
                        addr[Addresses.buildingNo], addr[Addresses.apartmentNo],
                    )
                }
                !req.customLocation.isNullOrBlank() -> req.customLocation
                else -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Bir adres seç ya da konum yaz."))
                    return@post
                }
            }

            val jobId = transaction {
                Jobs.insertAndGetId {
                    it[customerId] = userId
                    it[title] = req.title
                    it[description] = req.description
                    it[category] = req.category
                    it[subcategory] = req.subcategory
                    it[Jobs.location] = location
                    it[availableDates] = encodeStringList(sortedDates)
                    it[status] = JobStatus.OPEN
                    it[createdAt] = LocalDateTime.now()
                    it[updatedAt] = LocalDateTime.now()
                }.value
            }

            val created = transaction { Jobs.selectAll().where { Jobs.id eq jobId }.single().toJobSummary() }
            call.respond(HttpStatusCode.Created, created)
        }

        get("/mine") {
            val userId = call.currentUserId()
            val result = transaction {
                Jobs.selectAll()
                    .where { (Jobs.customerId eq userId) and (Jobs.status.inList(listOf(JobStatus.OPEN, JobStatus.ACTIVE))) }
                    .map { row ->
                        val count = Proposals.selectAll().where { Proposals.jobId eq row[Jobs.id] }.count().toInt()
                        row.toJobSummary(proposalCount = count)
                    }
            }
            call.respond(result)
        }

        get("/open") {
            val userId = call.currentUserId()
            if (!requireRole(call, "provider")) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Bu sayfa sadece ustalar için."))
                return@get
            }
            val rows = JobServices.getOpenJobsForProvider(userId)
            val result = transaction { rows.map { it.toJobSummary() } }
            call.respond(result)
        }

        get("/history") {
            val userId = call.currentUserId()
            val filter = call.request.queryParameters["filter"] ?: "all"

            val historyResponse = transaction {
                val all = Jobs.selectAll()
                    .where { (Jobs.customerId eq userId) and (Jobs.status.inList(listOf(JobStatus.COMPLETED, JobStatus.CANCELLED))) }
                    .toList()
                val completed = all.filter { it[Jobs.status] == JobStatus.COMPLETED }
                val cancelled = all.filter { it[Jobs.status] == JobStatus.CANCELLED }

                val filtered = when (filter) {
                    "completed" -> completed
                    "cancelled" -> cancelled
                    else -> all
                }

                val completedJobIds = completed.map { it[Jobs.id].value }
                val totalSpentValue = if (completedJobIds.isEmpty()) BigDecimal.ZERO else {
                    Proposals.selectAll()
                        .where { (Proposals.jobId.inList(completedJobIds)) and (Proposals.status eq ProposalStatus.ACCEPTED) }
                        .fold(BigDecimal.ZERO) { acc, row -> acc + row[Proposals.price] }
                }
                val ratingsGiven = if (completedJobIds.isEmpty()) emptyList() else {
                    Ratings.selectAll().where { Ratings.jobId.inList(completedJobIds) and (Ratings.customerId eq userId) }.toList()
                }
                val avg = if (ratingsGiven.isNotEmpty()) ratingsGiven.map { it[Ratings.score] }.average() else null

                JobHistoryResponse(
                    jobs = filtered.map { it.toJobSummary() },
                    filter = filter,
                    counts = mapOf("all" to all.size, "completed" to completed.size, "cancelled" to cancelled.size),
                    totalSpent = totalSpentValue.toPlainString(),
                    avgRating = avg,
                )
            }

            call.respond(historyResponse)
        }

        get("/{id}") {
            val userId = call.currentUserId()
            val jobId = call.parameters["id"]!!.toInt()

            val detail = transaction {
                val row = Jobs.selectAll().where { Jobs.id eq jobId }.singleOrNull()
                    ?: throw NoSuchElementException("İlan bulunamadı")
                val user = Users.selectAll().where { Users.id eq userId }.single()
                val isOwner = row[Jobs.customerId].value == userId
                val isProvider = user[Users.role] == "provider"

                val myProposalRow = if (isProvider) {
                    Proposals.selectAll().where { (Proposals.jobId eq jobId) and (Proposals.providerId eq userId) }.firstOrNull()
                } else null

                if (!isOwner && myProposalRow == null && isProvider && row[Jobs.status] != JobStatus.OPEN) {
                    throw ForbiddenError("Bu ilana erişimin yok.")
                }

                val availableDates = decodeStringList(row[Jobs.availableDates])
                val busyDates = if (isProvider) JobServices.getProviderBusyDates(userId, excludeJobId = jobId) else emptySet()
                val hasConflict = isProvider && myProposalRow == null && availableDates.isNotEmpty() && availableDates.all { it in busyDates }
                val freeDates = if (isProvider) availableDates.filter { it !in busyDates } else null

                val canMessage = row[Jobs.status] in setOf(JobStatus.ACTIVE, JobStatus.COMPLETED) &&
                    (isOwner || (myProposalRow != null && myProposalRow[Proposals.status] == ProposalStatus.ACCEPTED))
                val unreadCount = if (canMessage) {
                    Messages.selectAll()
                        .where { (Messages.jobId eq jobId) and (Messages.readAt.isNull()) and (Messages.senderId neq userId) }
                        .count().toInt()
                } else 0

                val hasRating = Ratings.selectAll().where { Ratings.jobId eq jobId }.count() > 0

                val proposals = if (isOwner) {
                    Proposals.selectAll().where { Proposals.jobId eq jobId }.map { it.toProposalDto() }
                } else null

                JobDetail(
                    id = row[Jobs.id].value,
                    title = row[Jobs.title],
                    description = row[Jobs.description],
                    category = row[Jobs.category],
                    subcategory = row[Jobs.subcategory],
                    location = row[Jobs.location],
                    availableDates = availableDates,
                    status = row[Jobs.status],
                    customer = publicUserFor(row[Jobs.customerId].value),
                    provider = row[Jobs.providerId]?.let { publicUserFor(it.value) },
                    isOwner = isOwner,
                    myProposal = myProposalRow?.toProposalDto(),
                    hasConflict = hasConflict,
                    availableDatesForMe = freeDates,
                    canMessage = canMessage,
                    unreadCount = unreadCount,
                    hasRating = hasRating,
                    proposals = proposals,
                )
            }
            call.respond(detail)
        }

        post("/{id}/complete") {
            val userId = call.currentUserId()
            val jobId = call.parameters["id"]!!.toInt()
            transaction {
                val row = Jobs.selectAll().where { (Jobs.id eq jobId) and (Jobs.customerId eq userId) }.singleOrNull()
                    ?: throw NoSuchElementException("İlan bulunamadı")
                if (row[Jobs.status] != JobStatus.ACTIVE) throw ForbiddenError("İş aktif değil.")
            }
            JobServices.completeJob(jobId)
            call.respond(OkResponse(message = "İş tamamlandı."))
        }

        post("/{id}/cancel") {
            val userId = call.currentUserId()
            val jobId = call.parameters["id"]!!.toInt()
            transaction {
                val row = Jobs.selectAll().where { (Jobs.id eq jobId) and (Jobs.customerId eq userId) }.singleOrNull()
                    ?: throw NoSuchElementException("İlan bulunamadı")
                if (row[Jobs.status] !in setOf(JobStatus.OPEN, JobStatus.ACTIVE)) throw ForbiddenError("Bu iş iptal edilemez.")
            }
            JobServices.cancelJob(jobId)
            call.respond(OkResponse(message = "İş iptal edildi."))
        }

        post("/{id}/rate") {
            val userId = call.currentUserId()
            val jobId = call.parameters["id"]!!.toInt()
            val req = call.receive<RateJobRequest>()
            if (req.score !in 1..5) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Puan 1 ile 5 arasında olmalı."))
                return@post
            }
            transaction {
                val row = Jobs.selectAll().where { (Jobs.id eq jobId) and (Jobs.customerId eq userId) }.singleOrNull()
                    ?: throw NoSuchElementException("İlan bulunamadı")
                if (row[Jobs.status] != JobStatus.COMPLETED) throw ForbiddenError("İş henüz tamamlanmadı.")
                if (Ratings.selectAll().where { Ratings.jobId eq jobId }.count() > 0) throw ForbiddenError("Bu iş zaten değerlendirildi.")
                val providerId = row[Jobs.providerId]!!.value
                Ratings.insertAndGetId {
                    it[Ratings.jobId] = jobId
                    it[customerId] = userId
                    it[Ratings.providerId] = providerId
                    it[score] = req.score
                    it[comment] = req.comment
                    it[createdAt] = LocalDateTime.now()
                }
            }
            call.respond(OkResponse(message = "Değerlendirme kaydedildi."))
        }

        post("/{id}/proposals") {
            val userId = call.currentUserId()
            val jobId = call.parameters["id"]!!.toInt()
            if (!requireRole(call, "provider")) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Bu işlem sadece ustalar için."))
                return@post
            }
            val req = call.receive<CreateProposalRequest>()

            val durationHours = req.durationHours?.let { BigDecimal(it) }
            if (durationHours != null && (durationHours < BigDecimal("0.5") || durationHours > BigDecimal("12"))) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Süre 0.5 ile 12 saat arasında olmalı."))
                return@post
            }

            transaction {
                val job = Jobs.selectAll().where { (Jobs.id eq jobId) and (Jobs.status eq JobStatus.OPEN) }.singleOrNull()
                    ?: throw NoSuchElementException("İlan bulunamadı veya artık açık değil")

                if (Proposals.selectAll().where { (Proposals.jobId eq jobId) and (Proposals.providerId eq userId) }.count() > 0) {
                    throw ForbiddenError("Bu işe zaten teklif verdin.")
                }

                val availableDates = decodeStringList(job[Jobs.availableDates])
                if (req.proposedDate != null) {
                    if (req.proposedDate !in availableDates) {
                        throw IllegalArgumentException("Seçilen tarih bu ilanın müsait günlerinden biri değil.")
                    }
                    if (JobServices.hasDateConflict(userId, jobId, req.proposedDate)) {
                        throw ScheduleConflictError("Seçtiğin tarihte çakışan başka bir işin var.")
                    }
                }

                Proposals.insertAndGetId {
                    it[Proposals.jobId] = jobId
                    it[providerId] = userId
                    it[message] = req.message
                    it[price] = BigDecimal(req.price)
                    it[proposedDate] = req.proposedDate?.let { d -> LocalDate.parse(d) }
                    it[proposedTime] = req.proposedTime?.let { t -> LocalTime.parse(t) }
                    it[Proposals.durationHours] = durationHours
                    it[createdAt] = LocalDateTime.now()
                }
            }
            call.respond(HttpStatusCode.Created, OkResponse(message = "Teklifin gönderildi."))
        }
    }

    route("/proposals") {
        post("/{id}/accept") {
            val userId = call.currentUserId()
            val proposalId = call.parameters["id"]!!.toInt()
            transaction {
                val proposal = Proposals.selectAll().where { Proposals.id eq proposalId }.singleOrNull()
                    ?: throw NoSuchElementException("Teklif bulunamadı")
                val job = Jobs.selectAll().where { Jobs.id eq proposal[Proposals.jobId] }.single()
                if (job[Jobs.customerId].value != userId) throw ForbiddenError("Bu teklifi kabul edemezsin.")
                if (job[Jobs.status] != JobStatus.OPEN) throw ForbiddenError("Bu ilana zaten bir teklif kabul edilmiş.")
            }
            JobServices.acceptProposal(proposalId)
            call.respond(OkResponse(message = "Teklif kabul edildi."))
        }
    }
}

class ForbiddenError(message: String) : Exception(message)
