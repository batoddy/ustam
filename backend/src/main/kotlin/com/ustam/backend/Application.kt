package com.ustam.backend

import com.ustam.backend.routes.ForbiddenError
import com.ustam.backend.routes.addressRoutes
import com.ustam.backend.routes.authRoutes
import com.ustam.backend.routes.catalogRoutes
import com.ustam.backend.routes.geoRoutes
import com.ustam.backend.routes.jobRoutes
import com.ustam.backend.routes.messageRoutes
import com.ustam.backend.routes.profileRoutes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    configureDatabase()

    install(ContentNegotiation) { json(json) }

    install(CallLogging)

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
    }

    install(StatusPages) {
        exception<ScheduleConflictError> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(cause.message ?: "schedule conflict"))
        }
        exception<NoSuchElementException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(cause.message ?: "not found"))
        }
        exception<ForbiddenError> { call, cause ->
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(cause.message ?: "forbidden"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "bad request"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error"))
        }
    }

    install(Authentication) {
        jwt("auth-jwt") {
            verifier(JwtConfig.verifier)
            validate { credential ->
                val userId = credential.payload.getClaim("userId").asInt()
                val type = credential.payload.getClaim("type").asString()
                if (userId != null && type == "access") JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid or expired token"))
            }
        }
    }

    seedIfEmpty()

    routing {
        get("/") { call.respond(mapOf("status" to "ok", "service" to "ustam-backend")) }
        authRoutes()
        catalogRoutes()
        geoRoutes()
        authenticate("auth-jwt") {
            profileRoutes()
            addressRoutes()
            jobRoutes()
            messageRoutes()
        }
    }
}

fun ApplicationCall.currentUserId(): Int =
    principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
