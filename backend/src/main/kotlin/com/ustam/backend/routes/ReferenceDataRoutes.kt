package com.ustam.backend.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val referenceJson = Json { ignoreUnknownKeys = true }

private fun loadResourceJson(path: String): JsonElement {
    val text = object {}.javaClass.getResourceAsStream(path)
        ?.bufferedReader(Charsets.UTF_8)?.readText()
        ?: error("Resource not found: $path")
    return referenceJson.parseToJsonElement(text)
}

private val catalogData: JsonElement by lazy { loadResourceJson("/catalog.json") }
private val geoData: JsonElement by lazy { loadResourceJson("/istanbul_geo.json") }

fun Route.catalogRoutes() {
    get("/catalog") { call.respond(catalogData) }
}

fun Route.geoRoutes() {
    get("/geo") { call.respond(geoData) }
}
