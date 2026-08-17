package org.labormanagement.model

import java.time.Instant
import java.util.UUID

data class EmployeeInvite(
    val id: UUID = UUID.randomUUID(),
    val employeeId: UUID,
    val businessId: UUID,
    val email: String,
    val token: String,
    val status: InviteStatus = InviteStatus.PENDING,
    val invitedBy: String,
    val invitedAt: Instant = Instant.now(),
    val acceptedAt: Instant? = null
)

enum class InviteStatus {
    PENDING,
    ACCEPTED
}
