package com.example.plugins

import com.auth0.jwt.JWT
import com.example.config.Security
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

fun Application.configureSecurity() {
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(Security.jwtVerifier)

            validate { credential ->
                val token = request.headers["Authorization"]?.removePrefix("Bearer ")
                val userId = token?.let { Security.verifyToken(it) }
                userId?.let { JWTPrincipal(credential.payload) }
            }

            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or expired token"))
            }
        }
    }
}

// Extension function to get current user ID from token
// UserId was originally encoded during token generation
fun ApplicationCall.userId(): Int {
    val principal = principal<JWTPrincipal>()
    val token = request.headers["Authorization"]?.removePrefix("Bearer ")
    return Security.verifyToken(token!!)!!
}
