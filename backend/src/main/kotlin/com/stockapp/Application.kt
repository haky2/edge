package com.stockapp

import com.stockapp.kis.KisClient
import com.stockapp.kis.KisException
import com.stockapp.routes.quoteRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

@Serializable
data class ErrorResponse(val error: String)

fun Application.module() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; prettyPrint = true })
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val status = if (cause is KisException) HttpStatusCode.BadGateway else HttpStatusCode.InternalServerError
            call.respond(status, ErrorResponse(cause.message ?: cause.toString()))
        }
    }

    val kis = KisClient(
        appKey = System.getenv("KIS_APP_KEY").orEmpty(),
        appSecret = System.getenv("KIS_APP_SECRET").orEmpty(),
        baseUrl = System.getenv("KIS_BASE_URL") ?: "https://openapi.koreainvestment.com:9443",
    )

    routing {
        get("/health") { call.respondText("OK") }
        quoteRoutes(kis)
    }
}
