package org.labormanagement.model

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

const val INVITE_VALIDITY_DAYS = 30L

data class EmployeeInvite(
    val id: UUID = UUID.randomUUID(),
    val employeeId: UUID,
    val businessId: UUID,
    val email: String,
    val token: String,
    val status: InviteStatus = InviteStatus.PENDING,
    val invitedBy: String,
    val invitedAt: Instant = Instant.now(),
    val expiresAt: Instant? = invitedAt.plus(INVITE_VALIDITY_DAYS, ChronoUnit.DAYS),
    val acceptedAt: Instant? = null
) {
    // Rows created before expiresAt existed have no value and are treated as
    // never expiring, rather than retroactively invalidating old invites.
    fun isExpired(now: Instant = Instant.now()): Boolean = expiresAt != null && now.isAfter(expiresAt)
}

enum class InviteStatus {
    PENDING,
    ACCEPTED,
    REVOKED
}
