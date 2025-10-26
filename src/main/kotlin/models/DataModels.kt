package com.example.models

import kotlinx.serialization.Serializable

// Define JSON structure for API requests/responses

@Serializable
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val token: String,
    val user: UserData // so a separate API call is not needed
)

@Serializable
data class UserData(
    val id: Int,
    val email: String,
    val username: String
)

