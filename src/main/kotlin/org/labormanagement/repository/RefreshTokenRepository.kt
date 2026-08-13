package org.labormanagement.repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.database.RefreshTokens
import org.labormanagement.model.RefreshTokenRecord
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*

/**
 * PostgreSQL-backed refresh token repository using Exposed ORM.
 * Refresh tokens are long-lived and single-use: each successful refresh
 * revokes the token it was issued with and returns a newly rotated one.
 */
class RefreshTokenRepository {
    private val logger = LoggerFactory.getLogger(RefreshTokenRepository::class.java)

    companion object {
        private const val TOKEN_EXPIRATION_DAYS = 7L
        private const val TOKEN_LENGTH = 32
        private val secureRandom = SecureRandom()
    }

    /**
     * Issue a new refresh token for a user.
     */
    fun createRefreshToken(userId: String): RefreshTokenRecord = transaction {
        val tokenString = generateSecureToken()
        val now = LocalDateTime.now()
        val expiresAt = now.plusDays(TOKEN_EXPIRATION_DAYS)

        RefreshTokens.insert {
            it[token] = tokenString
            it[RefreshTokens.userId] = userId
            it[RefreshTokens.expiresAt] = expiresAt.toInstant(ZoneOffset.UTC)
            it[createdAt] = now.toInstant(ZoneOffset.UTC)
            it[revoked] = false
        }

        RefreshTokenRecord(
            token = tokenString,
            userId = userId,
            expiresAt = expiresAt,
            createdAt = now,
            revoked = false
        )
    }

    /**
     * Find a refresh token by token string.
     */
    fun findByToken(token: String): RefreshTokenRecord? = transaction {
        RefreshTokens.selectAll().where { RefreshTokens.token eq token }
            .singleOrNull()
            ?.toRefreshTokenRecord()
    }

    /**
     * Validate a refresh token: exists, not revoked, not expired.
     */
    fun isValidToken(token: String): Boolean = transaction {
        val record = findByToken(token) ?: return@transaction false
        !record.revoked && record.expiresAt.isAfter(LocalDateTime.now())
    }

    /**
     * Revoke a refresh token (used on logout or rotation).
     */
    fun revokeToken(token: String): Boolean = transaction {
        val updatedCount = RefreshTokens.update({ RefreshTokens.token eq token }) {
            it[revoked] = true
        }
        updatedCount > 0
    }

    /**
     * Revoke all refresh tokens for a user (e.g. on password change).
     */
    fun revokeAllForUser(userId: String) = transaction {
        RefreshTokens.update({ (RefreshTokens.userId eq userId) and (RefreshTokens.revoked eq false) }) {
            it[revoked] = true
        }
    }

    /**
     * Rotate a refresh token: revoke the given token and issue a new one
     * for the same user. Returns null if the token is not valid.
     */
    fun rotateToken(token: String): RefreshTokenRecord? = transaction {
        val record = findByToken(token) ?: return@transaction null
        if (record.revoked || !record.expiresAt.isAfter(LocalDateTime.now())) {
            return@transaction null
        }

        revokeToken(token)
        createRefreshToken(record.userId)
    }

    /**
     * Clean up expired or revoked tokens (should be called periodically).
     */
    fun cleanupExpiredTokens() = transaction {
        val now = Instant.now()

        val deletedCount = RefreshTokens.deleteWhere {
            (expiresAt less now) or (revoked eq true)
        }

        logger.info("Cleaned up $deletedCount expired/revoked refresh tokens")
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(TOKEN_LENGTH)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun ResultRow.toRefreshTokenRecord(): RefreshTokenRecord {
        return RefreshTokenRecord(
            token = this[RefreshTokens.token],
            userId = this[RefreshTokens.userId],
            expiresAt = LocalDateTime.ofInstant(this[RefreshTokens.expiresAt], ZoneOffset.UTC),
            createdAt = LocalDateTime.ofInstant(this[RefreshTokens.createdAt], ZoneOffset.UTC),
            revoked = this[RefreshTokens.revoked]
        )
    }
}
