package org.labormanagement.model

import java.time.LocalDateTime

data class LoginRequest(
    val email: String,
    val password: String
)

data class RegisterRequest(
    val email: String,
    val firstName: String,
    val lastName: String,
    val password: String,
    val businessName: String
)

data class AuthResponse(
    val user: UserDTO,
    val token: String,
    val refreshToken: String,
    val businessId: String? = null // Include businessId for initial business created during registration
)

/**
 * Refresh token record backing the /api/auth/refresh flow.
 * Tokens are single-use: refreshing rotates in a new token and revokes this one.
 */
data class RefreshTokenRecord(
    val token: String,
    val userId: String,
    val expiresAt: LocalDateTime,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val revoked: Boolean = false
)

data class RefreshTokenRequest(
    val refreshToken: String
)

data class UserDTO(
    val id: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val role: UserRole,
    val twoFactorEnabled: Boolean
)

data class ValidateResponse(
    val valid: Boolean,
    val user: UserDTO?
)

data class ErrorResponse(
    val message: String
)

data class LogoutResponse(
    val message: String
)

// Extension function to convert User to UserDTO (never expose password hash or 2FA secret)
fun User.toDTO() = UserDTO(
    id = id,
    email = email,
    firstName = firstName,
    lastName = lastName,
    role = role,
    twoFactorEnabled = twoFactorEnabled
)