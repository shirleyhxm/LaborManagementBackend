package org.labormanagement.repository

import org.labormanagement.model.PasswordResetToken
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.*

/**
 * Repository for managing password reset tokens
 */
class PasswordResetRepository {
    // In-memory storage for password reset tokens
    private val tokens = mutableMapOf<String, PasswordResetToken>()

    companion object {
        // Token expires after 1 hour
        private const val TOKEN_EXPIRATION_HOURS = 1L
        private const val TOKEN_LENGTH = 32
        private val secureRandom = SecureRandom()
    }

    /**
     * Generate a new password reset token for a user
     */
    fun createResetToken(userId: String, email: String): PasswordResetToken {
        // Invalidate any existing tokens for this user
        tokens.values.removeIf { it.userId == userId && !it.used }

        val token = generateSecureToken()
        val resetToken = PasswordResetToken(
            token = token,
            userId = userId,
            email = email,
            expiresAt = LocalDateTime.now().plusHours(TOKEN_EXPIRATION_HOURS)
        )

        tokens[token] = resetToken
        return resetToken
    }

    /**
     * Find a reset token by token string
     */
    fun findByToken(token: String): PasswordResetToken? {
        return tokens[token]
    }

    /**
     * Validate a reset token
     * Returns true if token exists, is not used, and not expired
     */
    fun isValidToken(token: String): Boolean {
        val resetToken = tokens[token] ?: return false

        return !resetToken.used &&
               resetToken.expiresAt.isAfter(LocalDateTime.now())
    }

    /**
     * Mark a token as used
     */
    fun markTokenAsUsed(token: String): Boolean {
        val resetToken = tokens[token] ?: return false
        tokens[token] = resetToken.copy(used = true)
        return true
    }

    /**
     * Clean up expired tokens (should be called periodically)
     */
    fun cleanupExpiredTokens() {
        val now = LocalDateTime.now()
        tokens.values.removeIf { it.expiresAt.isBefore(now) || it.used }
    }

    /**
     * Generate a cryptographically secure random token
     */
    private fun generateSecureToken(): String {
        val bytes = ByteArray(TOKEN_LENGTH)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
