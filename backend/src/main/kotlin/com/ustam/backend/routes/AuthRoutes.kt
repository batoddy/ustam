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

fun Route.authRoutes() {
    post("/auth/signup") {
        val req = call.receive<SignUpRequest>()

        if (req.username.isBlank() || req.email.isBlank() || req.password.length < 8) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Kullanıcı adı, e-posta zorunlu; şifre en az 8 karakter olmalı."))
            return@post
        }
        if (req.role != "customer" && req.role != "provider") {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Geçersiz rol."))
            return@post
        }

        val existing = transaction {
            Users.selectAll().where { (Users.username eq req.username) or (Users.email eq req.email) }.firstOrNull()
        }
        if (existing != null) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Bu kullanıcı adı veya e-posta zaten kayıtlı."))
            return@post
        }

        val userId = transaction {
            Users.insertAndGetId {
                it[username] = req.username
                it[email] = req.email
                it[passwordHash] = PasswordHasher.hash(req.password)
                it[firstName] = req.firstName
                it[lastName] = req.lastName
                it[role] = req.role
                it[phone] = req.phone
                it[createdAt] = LocalDateTime.now()
            }.value
        }

        call.respond(HttpStatusCode.Created, TokenPair(JwtConfig.generateAccessToken(userId), JwtConfig.generateRefreshToken(userId)))
    }

    post("/auth/login") {
        val req = call.receive<LoginRequest>()

        val userRow = transaction {
            Users.selectAll().where {
                (Users.email eq req.usernameOrEmail) or (Users.username eq req.usernameOrEmail)
            }.firstOrNull()
        }

        if (userRow == null || !PasswordHasher.verify(req.password, userRow[Users.passwordHash])) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Kullanıcı adı/e-posta veya şifre hatalı."))
            return@post
        }

        val userId = userRow[Users.id].value
        call.respond(TokenPair(JwtConfig.generateAccessToken(userId), JwtConfig.generateRefreshToken(userId)))
    }

    post("/auth/refresh") {
        val req = call.receive<RefreshRequest>()
        val decoded = try {
            JwtConfig.verifier.verify(req.refreshToken)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Geçersiz refresh token."))
            return@post
        }
        if (decoded.getClaim("type").asString() != "refresh") {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Geçersiz token tipi."))
            return@post
        }
        val userId = decoded.getClaim("userId").asInt()
        call.respond(TokenPair(JwtConfig.generateAccessToken(userId), JwtConfig.generateRefreshToken(userId)))
    }
}
