package org.labormanagement.model

data class LoginRequest(
    val username: String,
    val password: String
)

data class AuthResponse(
    val user: UserDTO,
    val token: String
)

data class UserDTO(
    val id: String,
    val username: String,
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
    username = username,
    email = email,
    firstName = firstName,
    lastName = lastName,
    role = role,
    twoFactorEnabled = twoFactorEnabled
)