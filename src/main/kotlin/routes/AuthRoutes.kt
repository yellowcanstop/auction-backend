package com.example.routes

import com.example.config.DatabaseFactory.dbQuery
import com.example.config.Security
import com.example.models.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.security.SecureRandom
import java.util.Base64

fun Route.authRoutes() {
    route("/api/auth") {
        post("/register") {
            val request = call.receive<RegisterRequest>()

            if (!request.email.contains("@")) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid email"))
                return@post
            }

            val existingUser = dbQuery {
                Users.select(Users.email).where { Users.email eq request.email }.singleOrNull()
            }

            if (existingUser != null) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "Email already registered"))
                return@post
            }

            // Hash password using Argon2 (Bouncy Castle library implementation)
            // Parameters and code from Java tutorial: https://www.baeldung.com/java-argon2-hashing
            val salt = ByteArray(16)
            SecureRandom().nextBytes(salt)

            val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(2)
                .withMemoryAsKB(66536)
                .withParallelism(1)
                .withSalt(salt)

            val generate = Argon2BytesGenerator()
            generate.init(builder.build())
            val result = ByteArray(32)
            val passwordBytes = request.password.toByteArray(Charsets.UTF_8)
            generate.generateBytes(passwordBytes, result, 0, result.size)
            
            val saltAndHash = salt + result
            val hashedPassword = Base64.getEncoder().encodeToString(saltAndHash)

            val userId = dbQuery {
                Users.insert {
                    it[email] = request.email
                    it[passwordHash] = hashedPassword
                    it[username] = request.username
                }[Users.id].value
            }

            val token = Security.generateToken(userId, request.email)

            call.respond(HttpStatusCode.Created, LoginResponse(token, UserData(userId, request.email, request.username)))
        }


        post("/login") {
            val request = call.receive<LoginRequest>()

            val user = dbQuery {
                Users.selectAll().where { (Users.email eq request.email) and (Users.status eq Status.ACTIVE) }.singleOrNull()
            }

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                return@post
            }

            val saltAndHash = Base64.getDecoder().decode(user[Users.passwordHash])
            val salt = saltAndHash.copyOfRange(0, 16)
            val storedHash = saltAndHash.copyOfRange(16, saltAndHash.size)

            val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(2)
                .withMemoryAsKB(66536)
                .withParallelism(1)
                .withSalt(salt)

            val verifier = Argon2BytesGenerator()
            verifier.init(builder.build())
            val result = ByteArray(32)
            val passwordBytes = request.password.toByteArray(Charsets.UTF_8)
            verifier.generateBytes(passwordBytes, result, 0, result.size)
            val passwordMatches = result.contentEquals(storedHash)

            if (!passwordMatches) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                return@post
            }

            val token = Security.generateToken(user[Users.id].value, request.email)

            call.respond(LoginResponse(token, UserData(user[Users.id].value, user[Users.email], user[Users.username])))
        }
    }
}
