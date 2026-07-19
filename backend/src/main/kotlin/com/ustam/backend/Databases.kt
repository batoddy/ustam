package com.ustam.backend

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.javatime.time
import org.jetbrains.exposed.sql.transactions.transaction

object Users : IntIdTable("users") {
    val username = varchar("username", 60).uniqueIndex()
    val email = varchar("email", 190)
    val passwordHash = varchar("password_hash", 100)
    val firstName = varchar("first_name", 80)
    val lastName = varchar("last_name", 80)
    val role = varchar("role", 20) // "customer" | "provider"
    val phone = varchar("phone", 30).default("")
    val isVerifiedUsta = bool("is_verified_usta").default(false)
    val isAvailable = bool("is_available").default(true)
    val serviceCategories = text("service_categories").default("[]") // JSON list
    val serviceAreas = text("service_areas").default("{}") // JSON dict
    val createdAt = datetime("created_at")
}

object Addresses : IntIdTable("addresses") {
    val userId = reference("user_id", Users)
    val label = varchar("label", 10).default("ev") // ev | is | diger
    val district = varchar("district", 60)
    val neighborhood = varchar("neighborhood", 80).default("")
    val street = varchar("street", 140).default("")
    val buildingNo = varchar("building_no", 20).default("")
    val apartmentNo = varchar("apartment_no", 20).default("")
    val isDefault = bool("is_default").default(false)
    val createdAt = datetime("created_at")
}

object Jobs : IntIdTable("jobs") {
    val customerId = reference("customer_id", Users)
    val providerId = reference("provider_id", Users).nullable()
    val title = varchar("title", 140)
    val description = text("description")
    val category = varchar("category", 20)
    val subcategory = varchar("subcategory", 80).default("")
    val location = varchar("location", 140).default("")
    val availableDates = text("available_dates") // JSON list of ISO date strings
    val status = varchar("status", 20).default("open") // open|active|completed|cancelled
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}

object Proposals : IntIdTable("proposals") {
    val jobId = reference("job_id", Jobs)
    val providerId = reference("provider_id", Users)
    val message = text("message").default("")
    val price = decimal("price", 10, 2)
    val proposedDate = date("proposed_date").nullable()
    val proposedTime = time("proposed_time").nullable()
    val durationHours = decimal("duration_hours", 4, 1).nullable()
    val status = varchar("status", 20).default("pending") // pending|accepted|rejected
    val createdAt = datetime("created_at")

    init {
        uniqueIndex(jobId, providerId)
    }
}

object Messages : IntIdTable("messages") {
    val jobId = reference("job_id", Jobs)
    val senderId = reference("sender_id", Users)
    val body = text("body")
    val createdAt = datetime("created_at")
    val readAt = datetime("read_at").nullable()
}

object Ratings : IntIdTable("ratings") {
    val jobId = reference("job_id", Jobs).uniqueIndex()
    val customerId = reference("customer_id", Users)
    val providerId = reference("provider_id", Users)
    val score = integer("score")
    val comment = text("comment").default("")
    val createdAt = datetime("created_at")
}

fun configureDatabase() {
    val dbPath = System.getenv("DATABASE_PATH") ?: "ustam.db"
    Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
    transaction {
        SchemaUtils.createMissingTablesAndColumns(Users, Addresses, Jobs, Proposals, Messages, Ratings)
    }
}
