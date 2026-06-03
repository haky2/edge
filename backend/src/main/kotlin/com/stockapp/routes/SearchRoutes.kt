package com.stockapp.routes

import com.stockapp.master.StockMaster
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.searchRoutes(master: StockMaster) {
    // GET /search?q=삼성  또는  /search?q=0091
    get("/search") {
        val q = call.request.queryParameters["q"].orEmpty()
        call.respond(master.search(q))
    }
}
