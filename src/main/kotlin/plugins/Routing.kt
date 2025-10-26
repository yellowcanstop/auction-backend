package com.example.plugins

import com.example.routes.authRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {

//    install(StatusPages) {
//        exception<Throwable> { call, cause ->
//            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
//        }
//    }

    routing {
        authRoutes()
    }
}
