package org.labormanagement.model

import java.time.LocalDateTime

/**
 * Password reset token for forgot password flow
 */
data class PasswordResetToken(
    val token: String,
    val userId: String,
    val email: String,
    val expiresAt: LocalDateTime,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val used: Boolean = false
)

/**
 * Request to initiate password reset
 */
data class ForgotPasswordRequest(
    val email: String
)

/**
 * Request to reset password with token
 */
data class ResetPasswordRequest(
    val token: String,
    val newPassword: String
)

/**
 * Response for forgot password request
 */
data class ForgotPasswordResponse(
    val message: String
)

/**
 * Response for reset password request
 */
data class ResetPasswordResponse(
    val message: String
)
