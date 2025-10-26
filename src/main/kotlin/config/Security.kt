package com.example.config

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.util.*

object Security {
    private val SECRET = System.getenv("JWT_SECRET")
        ?: throw IllegalStateException("JWT_SECRET must be set")
    private val algorithm = Algorithm.HMAC256(SECRET)
    private const val ISSUER = "auction-app"
    private const val VALIDITY_MS = 86_400_000L // 24 hours

    val jwtVerifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(ISSUER)
        .build()

    fun generateToken(userId: Int, email: String): String {
        return JWT.create()
            .withIssuer(ISSUER)
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withExpiresAt(Date(System.currentTimeMillis() + VALIDITY_MS))
            .withIssuedAt(Date())
            .sign(algorithm)
    }

    fun verifyToken(token: String): Int? {
        return try {
            val verifier = JWT.require(algorithm)
                .withIssuer(ISSUER)
                .build()
            val jwt = verifier.verify(token)
            jwt.getClaim("userId").asInt()
        } catch (e: Exception) {
            null
        }
    }
}