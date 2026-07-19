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

private fun ResultRow.toAddressDto(): AddressDto = AddressDto(
    id = this[Addresses.id].value,
    label = this[Addresses.label],
    district = this[Addresses.district],
    neighborhood = this[Addresses.neighborhood],
    street = this[Addresses.street],
    buildingNo = this[Addresses.buildingNo],
    apartmentNo = this[Addresses.apartmentNo],
    isDefault = this[Addresses.isDefault],
    fullAddress = buildFullAddress(
        this[Addresses.district], this[Addresses.neighborhood], this[Addresses.street],
        this[Addresses.buildingNo], this[Addresses.apartmentNo],
    ),
)

fun Route.addressRoutes() {
    route("/addresses") {
        get {
            val userId = call.currentUserId()
            val list = transaction {
                Addresses.selectAll().where { Addresses.userId eq userId }
                    .orderBy(Addresses.isDefault to SortOrder.DESC)
                    .map { it.toAddressDto() }
            }
            call.respond(list)
        }

        post {
            val userId = call.currentUserId()
            val req = call.receive<AddressDto>()
            val newId = transaction {
                if (req.isDefault) {
                    Addresses.update({ Addresses.userId eq userId }) { it[isDefault] = false }
                }
                Addresses.insertAndGetId {
                    it[Addresses.userId] = userId
                    it[label] = req.label
                    it[district] = req.district
                    it[neighborhood] = req.neighborhood
                    it[street] = req.street
                    it[buildingNo] = req.buildingNo
                    it[apartmentNo] = req.apartmentNo
                    it[isDefault] = req.isDefault
                    it[createdAt] = LocalDateTime.now()
                }.value
            }
            val created = transaction { Addresses.selectAll().where { Addresses.id eq newId }.single().toAddressDto() }
            call.respond(HttpStatusCode.Created, created)
        }

        patch("/{id}") {
            val userId = call.currentUserId()
            val addressId = call.parameters["id"]!!.toInt()
            val req = call.receive<AddressDto>()
            transaction {
                val owned = Addresses.selectAll().where { (Addresses.id eq addressId) and (Addresses.userId eq userId) }.firstOrNull()
                    ?: throw NoSuchElementException("Address not found")
                if (req.isDefault) {
                    Addresses.update({ Addresses.userId eq userId }) { it[isDefault] = false }
                }
                Addresses.update({ Addresses.id eq addressId }) {
                    it[label] = req.label
                    it[district] = req.district
                    it[neighborhood] = req.neighborhood
                    it[street] = req.street
                    it[buildingNo] = req.buildingNo
                    it[apartmentNo] = req.apartmentNo
                    it[isDefault] = req.isDefault
                }
            }
            val updated = transaction { Addresses.selectAll().where { Addresses.id eq addressId }.single().toAddressDto() }
            call.respond(updated)
        }

        delete("/{id}") {
            val userId = call.currentUserId()
            val addressId = call.parameters["id"]!!.toInt()
            transaction {
                val target = Addresses.selectAll().where { (Addresses.id eq addressId) and (Addresses.userId eq userId) }.firstOrNull()
                    ?: throw NoSuchElementException("Address not found")
                val wasDefault = target[Addresses.isDefault]
                Addresses.deleteWhere { Addresses.id eq addressId }
                if (wasDefault) {
                    val next = Addresses.selectAll().where { Addresses.userId eq userId }
                        .orderBy(Addresses.createdAt to SortOrder.DESC)
                        .firstOrNull()
                    if (next != null) {
                        Addresses.update({ Addresses.id eq next[Addresses.id] }) { it[isDefault] = true }
                    }
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
