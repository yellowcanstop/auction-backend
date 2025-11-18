package com.example.plugins

import com.example.routes.auctionRoutes
import com.example.routes.authRoutes
import com.example.routes.groupRoutes
import com.example.routes.taskRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respond(HttpStatusCode.OK)
        }
        authRoutes()
        groupRoutes()
        taskRoutes()
        auctionRoutes()
    }
}
