package com.example

import com.example.config.DatabaseFactory
import com.example.plugins.configureRouting
import com.example.plugins.configureSecurity
import com.example.plugins.configureSerialization
import com.example.services.AuctionFinalizationService
import com.example.websocket.configureSockets
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

//fun main() {
//    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
//        .start(wait = true)
//}

fun Application.module() {
    // val config = environment.config

    DatabaseFactory.init()
    configureSecurity()
    configureSerialization()
    configureRouting()

    val serviceScope = CoroutineScope(SupervisorJob())
    AuctionFinalizationService.start(serviceScope, checkIntervalSeconds = 60)

    // subscribe to ktor's predefined events to stop background job when app stops
    monitor.subscribe(ApplicationStopped) { application ->
        application.environment.log.info("Server is stopped")
        AuctionFinalizationService.stop()
        monitor.unsubscribe(ApplicationStopped) {}
    }
}
