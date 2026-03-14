package org.labormanagement.repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.database.PasswordResets
import org.labormanagement.model.PasswordResetToken
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*

/**
 * PostgreSQL-backed password reset repository using Exposed ORM.
 * Manages password reset tokens for forgot password flow.
 */
class PasswordResetRepository {
    private val logger = LoggerFactory.getLogger(PasswordResetRepository::class.java)

    companion object {
        // Token expires after 1 hour
        private const val TOKEN_EXPIRATION_HOURS = 1L
        private const val TOKEN_LENGTH = 32
        private val secureRandom = SecureRandom()
    }

    /**
     * Generate a new password reset token for a user
     */
    fun createResetToken(userId: String, email: String): PasswordResetToken = transaction {
        // Invalidate any existing tokens for this user
        PasswordResets.update({ (PasswordResets.userId eq userId) and (PasswordResets.used eq false) }) {
            it[used] = true
        }

        val tokenString = generateSecureToken()
        val now = LocalDateTime.now()
        val expiresAt = now.plusHours(TOKEN_EXPIRATION_HOURS)

        PasswordResets.insert {
            it[token] = tokenString
            it[PasswordResets.userId] = userId
            it[PasswordResets.email] = email
            it[PasswordResets.expiresAt] = expiresAt.toInstant(ZoneOffset.UTC)
            it[createdAt] = now.toInstant(ZoneOffset.UTC)
            it[used] = false
        }

        PasswordResetToken(
            token = tokenString,
            userId = userId,
            email = email,
            expiresAt = expiresAt,
            createdAt = now,
            used = false
        )
    }

    /**
     * Find a reset token by token string
     */
    fun findByToken(token: String): PasswordResetToken? = transaction {
        PasswordResets.selectAll().where { PasswordResets.token eq token }
            .singleOrNull()
            ?.toPasswordResetToken()
    }

    /**
     * Validate a reset token
     * Returns true if token exists, is not used, and not expired
     */
    fun isValidToken(token: String): Boolean = transaction {
        val resetToken = findByToken(token) ?: return@transaction false

        !resetToken.used && resetToken.expiresAt.isAfter(LocalDateTime.now())
    }

    /**
     * Mark a token as used
     */
    fun markTokenAsUsed(token: String): Boolean = transaction {
        val resetToken = findByToken(token) ?: return@transaction false

        val updatedCount = PasswordResets.update({ PasswordResets.token eq token }) {
            it[used] = true
        }

        updatedCount > 0
    }

    /**
     * Clean up expired tokens (should be called periodically)
     */
    fun cleanupExpiredTokens() = transaction {
        val now = Instant.now()

        val deletedCount = PasswordResets.deleteWhere {
            (expiresAt less now) or (used eq true)
        }

        logger.info("Cleaned up $deletedCount expired/used password reset tokens")
    }

    /**
     * Generate a cryptographically secure random token
     */
    private fun generateSecureToken(): String {
        val bytes = ByteArray(TOKEN_LENGTH)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Extension function to convert ResultRow to PasswordResetToken domain model
     */
    private fun ResultRow.toPasswordResetToken(): PasswordResetToken {
        return PasswordResetToken(
            token = this[PasswordResets.token],
            userId = this[PasswordResets.userId],
            email = this[PasswordResets.email],
            expiresAt = LocalDateTime.ofInstant(this[PasswordResets.expiresAt], ZoneOffset.UTC),
            createdAt = LocalDateTime.ofInstant(this[PasswordResets.createdAt], ZoneOffset.UTC),
            used = this[PasswordResets.used]
        )
    }
}
