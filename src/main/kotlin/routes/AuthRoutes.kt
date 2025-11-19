package com.example.routes

import com.example.config.DatabaseFactory.dbQuery
import com.example.config.Security
import com.example.models.*
import com.example.plugins.userId
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.jetbrains.exposed.sql.*
import java.security.SecureRandom
import java.util.Base64

fun Route.authRoutes() {
    route("/api/auth") {
        post("/register") {
            val request = call.receive<RegisterRequest>()

            val validationError = validateRegistration(request)

            if (validationError != null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
                return@post
            }

            val trimmedEmail = request.email.trim().lowercase()
            val trimmedUsername = request.username.trim()

            val existingUser = dbQuery {
                Users.select(Users.email).where { Users.email eq trimmedEmail }.singleOrNull()
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
                    it[email] = trimmedEmail
                    it[passwordHash] = hashedPassword
                    it[username] = trimmedUsername
                }[Users.id].value
            }

            call.respond(HttpStatusCode.Created, mapOf("message" to "Successful registration. Please log in to continue."))
        }


        post("/login") {
            val request = call.receive<LoginRequest>()

            val validationError = validateLogin(request)

            if (validationError != null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
                return@post
            }

            val trimmedEmail = request.email.trim().lowercase()

            val user = dbQuery {
                Users.selectAll().where { (Users.email eq trimmedEmail) and (Users.status eq Status.ACTIVE) }.singleOrNull()
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

            val tokenPair = Security.generateTokenPair(user[Users.id].value, request.email)

            call.respond(HttpStatusCode.OK, LoginResponse(tokenPair.accessToken, UserData(user[Users.id].value, user[Users.email], user[Users.username]), tokenPair.refreshToken))
        }

        post("/refresh") {
            val request = call.receive<RefreshTokenRequest>()

            if (request.refreshToken.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Refresh token cannot be blank"))
                return@post
            }

            val userId = Security.verifyRefreshToken(request.refreshToken)

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or expired refresh token"))
                return@post
            }

            val user = dbQuery {
                Users.selectAll()
                    .where { (Users.id eq userId) and (Users.status eq Status.ACTIVE) }
                    .singleOrNull()
            }

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "User not found or inactive"))
                return@post
            }

            val tokenPair = Security.generateTokenPair(userId, user[Users.email])

            call.respond(HttpStatusCode.OK, TokenResponse(tokenPair.accessToken, tokenPair.refreshToken))
        }

    }
}

private fun isValidEmail(email: String): Boolean {
    val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
    return email.matches(emailRegex)
}

private fun isValidPassword(password: String): Boolean {
    // At least 8 characters, contains uppercase, lowercase, digit, and special character
    return password.length >= 8 &&
            password.any { it.isUpperCase() } &&
            password.any { it.isLowerCase() } &&
            password.any { it.isDigit() } &&
            password.any { !it.isLetterOrDigit() }
}

private fun validateRegistration(request: RegisterRequest): String? {
    val email = request.email.trim()
    val username = request.username.trim()
    val password = request.password

    return when {
        username.isBlank() -> "Username cannot be blank"
        username.length < 3 -> "Username must be at least 3 characters"
        username.length > 50 -> "Username cannot exceed 50 characters"
        email.isBlank() -> "Email cannot be blank"
        !isValidEmail(email) -> "Invalid email format"
        email.length > 255 -> "Email cannot exceed 255 characters"
        password.isBlank() -> "Password cannot be blank"
        !isValidPassword(password) -> "Password must be at least 8 characters and contain uppercase, lowercase, digit, and special character"
        password.length > 128 -> "Password cannot exceed 128 characters"
        else -> null
    }
}

private fun validateLogin(request: LoginRequest): String? {
    val email = request.email.trim()
    val password = request.password

    return when {
        email.isBlank() -> "Email cannot be blank"
        !isValidEmail(email) -> "Invalid email format"
        password.isBlank() -> "Password cannot be blank"
        else -> null
    }
}