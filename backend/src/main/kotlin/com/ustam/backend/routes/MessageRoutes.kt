package com.ustam.backend.routes

import com.ustam.backend.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

private fun ResultRow.toMessageDto(currentUserId: Int): MessageDto = MessageDto(
    id = this[Messages.id].value,
    senderId = this[Messages.senderId].value,
    body = this[Messages.body],
    createdAt = this[Messages.createdAt].format(isoFormatter),
    isMine = this[Messages.senderId].value == currentUserId,
)

fun Route.messageRoutes() {
    get("/conversations") {
        val userId = call.currentUserId()
        val conversations = transaction {
            val role = Users.selectAll().where { Users.id eq userId }.single()[Users.role]
            val jobs = if (role == "customer") {
                Jobs.selectAll().where {
                    (Jobs.customerId eq userId) and (Jobs.status.inList(listOf(JobStatus.ACTIVE, JobStatus.COMPLETED)))
                }.toList()
            } else {
                Jobs.selectAll().where {
                    (Jobs.providerId eq userId) and (Jobs.status.inList(listOf(JobStatus.ACTIVE, JobStatus.COMPLETED)))
                }.toList()
            }

            jobs.map { job ->
                val jobId = job[Jobs.id].value
                val lastMessageRow = Messages.selectAll().where { Messages.jobId eq jobId }
                    .orderBy(Messages.createdAt to SortOrder.DESC).firstOrNull()
                val unread = Messages.selectAll().where {
                    (Messages.jobId eq jobId) and (Messages.readAt.isNull()) and (Messages.senderId neq userId)
                }.count().toInt()
                val counterpartId = if (role == "customer") job[Jobs.providerId]?.value else job[Jobs.customerId].value

                ConversationDto(
                    job = job.let {
                        JobSummary(
                            id = it[Jobs.id].value, title = it[Jobs.title], category = it[Jobs.category],
                            subcategory = it[Jobs.subcategory], location = it[Jobs.location],
                            availableDates = decodeStringList(it[Jobs.availableDates]), status = it[Jobs.status],
                        )
                    },
                    counterpart = counterpartId?.let { publicUserFor(it) } ?: PublicUser(0, "?", false),
                    lastMessage = lastMessageRow?.toMessageDto(userId),
                    unreadCount = unread,
                )
            }.sortedByDescending { it.lastMessage?.createdAt ?: "" }
        }
        call.respond(conversations)
    }

    route("/jobs/{id}/messages") {
        get {
            val userId = call.currentUserId()
            val jobId = call.parameters["id"]!!.toInt()

            val messages = transaction {
                val job = Jobs.selectAll().where { Jobs.id eq jobId }.singleOrNull()
                    ?: throw NoSuchElementException("İlan bulunamadı")
                val isOwner = job[Jobs.customerId].value == userId
                val isProvider = job[Jobs.providerId]?.value == userId
                if (!isOwner && !isProvider) throw ForbiddenError("Bu sohbete erişimin yok.")
                if (job[Jobs.status] !in setOf(JobStatus.ACTIVE, JobStatus.COMPLETED)) {
                    throw ForbiddenError("Bu iş için mesajlaşma henüz açık değil.")
                }

                Messages.update({ (Messages.jobId eq jobId) and (Messages.readAt.isNull()) and (Messages.senderId neq userId) }) {
                    it[readAt] = LocalDateTime.now()
                }

                Messages.selectAll().where { Messages.jobId eq jobId }
                    .orderBy(Messages.createdAt to SortOrder.ASC)
                    .map { it.toMessageDto(userId) }
            }
            call.respond(messages)
        }

        post {
            val userId = call.currentUserId()
            val jobId = call.parameters["id"]!!.toInt()
            val req = call.receive<SendMessageRequest>()

            transaction {
                val job = Jobs.selectAll().where { Jobs.id eq jobId }.singleOrNull()
                    ?: throw NoSuchElementException("İlan bulunamadı")
                val isOwner = job[Jobs.customerId].value == userId
                val isProvider = job[Jobs.providerId]?.value == userId
                if (!isOwner && !isProvider) throw ForbiddenError("Bu sohbete erişimin yok.")
                if (job[Jobs.status] !in setOf(JobStatus.ACTIVE, JobStatus.COMPLETED)) {
                    throw ForbiddenError("Bu iş için mesajlaşma henüz açık değil.")
                }
                Messages.insertAndGetId {
                    it[Messages.jobId] = jobId
                    it[senderId] = userId
                    it[body] = req.body
                    it[createdAt] = LocalDateTime.now()
                }
            }
            call.respond(HttpStatusCode.Created, OkResponse(message = "Mesaj gönderildi."))
        }
    }
}
