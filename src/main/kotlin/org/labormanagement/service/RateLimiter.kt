package org.labormanagement.service

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Service for rate limiting login attempts to prevent brute force attacks
 */
class RateLimiter {
    // Track login attempts by identifier (IP address or username)
    private val loginAttempts = mutableMapOf<String, LoginAttemptTracker>()

    companion object {
        // Maximum failed login attempts before lockout
        private const val MAX_ATTEMPTS = 5

        // Lockout duration in minutes after max attempts exceeded
        private const val LOCKOUT_DURATION_MINUTES = 15L

        // Time window for tracking failed attempts (in minutes)
        private const val ATTEMPT_WINDOW_MINUTES = 10L
    }

    /**
     * Check if an identifier (IP or username) is currently locked out
     */
    fun isLockedOut(identifier: String): Boolean {
        val tracker = loginAttempts[identifier] ?: return false

        // Check if lockout has expired
        val lockedUntil = tracker.lockedUntil
        if (lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now())) {
            return true
        }

        // Lockout has expired, clean up
        if (lockedUntil != null && lockedUntil.isBefore(LocalDateTime.now())) {
            loginAttempts.remove(identifier)
            return false
        }

        return false
    }

    /**
     * Record a failed login attempt
     * Returns true if the identifier should be locked out
     */
    fun recordFailedAttempt(identifier: String): Boolean {
        val now = LocalDateTime.now()
        val tracker = loginAttempts.getOrPut(identifier) {
            LoginAttemptTracker(identifier)
        }

        // Clean up old attempts outside the time window
        tracker.cleanupOldAttempts(now)

        // Add new failed attempt
        tracker.failedAttempts.add(now)

        // Check if max attempts exceeded
        if (tracker.failedAttempts.size >= MAX_ATTEMPTS) {
            tracker.lockedUntil = now.plusMinutes(LOCKOUT_DURATION_MINUTES)
            return true
        }

        loginAttempts[identifier] = tracker
        return false
    }

    /**
     * Record a successful login (clears failed attempts)
     */
    fun recordSuccessfulLogin(identifier: String) {
        loginAttempts.remove(identifier)
    }

    /**
     * Get remaining attempts before lockout
     */
    fun getRemainingAttempts(identifier: String): Int {
        val tracker = loginAttempts[identifier] ?: return MAX_ATTEMPTS
        tracker.cleanupOldAttempts(LocalDateTime.now())
        return (MAX_ATTEMPTS - tracker.failedAttempts.size).coerceAtLeast(0)
    }

    /**
     * Get time remaining until lockout expires (in minutes)
     */
    fun getLockoutTimeRemaining(identifier: String): Long? {
        val tracker = loginAttempts[identifier] ?: return null
        val lockedUntilTime = tracker.lockedUntil ?: return null

        val now = LocalDateTime.now()
        if (lockedUntilTime.isBefore(now)) {
            return null
        }

        return ChronoUnit.MINUTES.between(now, lockedUntilTime)
    }

    /**
     * Clean up expired lockouts and old attempts
     * Should be called periodically
     */
    fun cleanup() {
        val now = LocalDateTime.now()
        loginAttempts.values.removeIf { tracker ->
            // Remove if lockout has expired and no recent attempts
            val lockedUntilTime = tracker.lockedUntil
            lockedUntilTime != null &&
            lockedUntilTime.isBefore(now) &&
            tracker.failedAttempts.isEmpty()
        }
    }

    /**
     * Tracker for login attempts per identifier
     */
    private data class LoginAttemptTracker(
        val identifier: String,
        val failedAttempts: MutableList<LocalDateTime> = mutableListOf(),
        var lockedUntil: LocalDateTime? = null
    ) {
        fun cleanupOldAttempts(now: LocalDateTime) {
            val cutoff = now.minusMinutes(ATTEMPT_WINDOW_MINUTES)
            failedAttempts.removeIf { it.isBefore(cutoff) }
        }
    }
}

/**
 * Exception thrown when rate limit is exceeded
 */
class RateLimitExceededException(
    val timeRemainingMinutes: Long
) : Exception("Too many failed login attempts. Account locked for $timeRemainingMinutes minutes.")
