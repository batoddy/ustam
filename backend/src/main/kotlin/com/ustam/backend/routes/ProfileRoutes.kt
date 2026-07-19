package com.ustam.backend.routes

import com.ustam.backend.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

private fun ApplicationCall.userId() = currentUserId()

fun meResponseFor(userId: Int): MeResponse = transaction {
    val row = Users.selectAll().where { Users.id eq userId }.single()
    MeResponse(
        id = row[Users.id].value,
        username = row[Users.username],
        email = row[Users.email],
        firstName = row[Users.firstName],
        lastName = row[Users.lastName],
        role = row[Users.role],
        phone = row[Users.phone],
        isVerifiedUsta = row[Users.isVerifiedUsta],
        isAvailable = row[Users.isAvailable],
        serviceCategories = decodeStringList(row[Users.serviceCategories]),
        serviceAreas = decodeAreas(row[Users.serviceAreas]),
    )
}

fun Route.profileRoutes() {
    get("/me") {
        call.respond(meResponseFor(call.userId()))
    }

    patch("/me") {
        val req = call.receive<EditProfileRequest>()
        val id = call.userId()
        transaction {
            Users.update({ Users.id eq id }) { stmt ->
                req.firstName?.let { stmt[firstName] = it }
                req.lastName?.let { stmt[lastName] = it }
                req.email?.let { stmt[email] = it }
                req.phone?.let { stmt[phone] = it }
            }
        }
        call.respond(meResponseFor(id))
    }

    post("/me/toggle-availability") {
        val id = call.userId()
        transaction {
            val current = Users.selectAll().where { Users.id eq id }.single()[Users.isAvailable]
            Users.update({ Users.id eq id }) { it[isAvailable] = !current }
        }
        call.respond(meResponseFor(id))
    }

    patch("/me/categories") {
        val req = call.receive<CategoriesRequest>()
        val id = call.userId()
        transaction {
            Users.update({ Users.id eq id }) { it[serviceCategories] = encodeStringList(req.serviceCategories) }
        }
        call.respond(meResponseFor(id))
    }

    patch("/me/areas") {
        val req = call.receive<AreasRequest>()
        val id = call.userId()
        transaction {
            Users.update({ Users.id eq id }) { it[serviceAreas] = encodeAreas(req.serviceAreas) }
        }
        call.respond(meResponseFor(id))
    }

    delete("/me") {
        val id = call.userId()
        transaction { Users.deleteWhere { Users.id eq id } }
        call.respond(HttpStatusCode.NoContent)
    }
}
