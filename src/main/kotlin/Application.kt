package com.example

import com.example.config.DatabaseFactory
import com.example.plugins.configureRouting
import com.example.plugins.configureSecurity
import com.example.plugins.configureSerialization
import com.example.websocket.configureSockets
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

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
}
