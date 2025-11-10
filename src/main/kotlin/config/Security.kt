package com.example.config

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.util.*

object Security {
    private val SECRET = System.getenv("JWT_SECRET")
        ?: throw IllegalStateException("JWT_SECRET must be set")
    private val REFRESH_SECRET = System.getenv("JWT_REFRESH_SECRET")
        ?: throw IllegalStateException("JWT_REFRESH_SECRET must be set")
    private val algorithm = Algorithm.HMAC256(SECRET)
    private val refreshAlgorithm = Algorithm.HMAC256(REFRESH_SECRET)
    private const val ISSUER = "auction-app"

    // User logs in once. Stay logged in for 20 days (or until logged out). API calls use short-lived 30-minute access tokens.
    // When access token expires, use refresh token to get a new access token.
    private const val ACCESS_TOKEN_VALIDITY_MS = 1_800_000L // 30 minutes
    private const val REFRESH_TOKEN_VALIDITY_MS = 86_400_000L * 20 // 20 days

    val jwtVerifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(ISSUER)
        .build()

    data class TokenPair(val accessToken: String, val refreshToken: String)

    fun generateTokenPair(userId: Int, email: String): TokenPair {
        val accessToken = JWT.create()
            .withIssuer(ISSUER)
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withClaim("type", "access")
            .withExpiresAt(Date(System.currentTimeMillis() + ACCESS_TOKEN_VALIDITY_MS))
            .withIssuedAt(Date())
            .sign(algorithm)

        val refreshToken = JWT.create()
            .withIssuer(ISSUER)
            .withClaim("userId", userId)
            .withClaim("type", "refresh")
            .withExpiresAt(Date(System.currentTimeMillis() + REFRESH_TOKEN_VALIDITY_MS))
            .withIssuedAt(Date())
            .sign(refreshAlgorithm)

        return TokenPair(accessToken, refreshToken)
    }

    fun verifyAccessToken(token: String): Int? {
        return try {
            val jwt = jwtVerifier.verify(token)
            if (jwt.getClaim("type").asString() == "access") {
                jwt.getClaim("userId").asInt()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun verifyRefreshToken(token: String): Int? {
        return try {
            val verifier = JWT.require(refreshAlgorithm)
                .withIssuer(ISSUER)
                .build()
            val jwt = verifier.verify(token)
            if (jwt.getClaim("type").asString() == "refresh") {
                jwt.getClaim("userId").asInt()
            } else null
        } catch (e: Exception) {
            null
        }
    }
}