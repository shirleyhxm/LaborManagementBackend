package org.labormanagement.model

import java.time.Instant
import java.util.UUID

data class SwapRequest(
    val id: UUID = UUID.randomUUID(),
    val businessId: UUID,
    val requestingEmployeeId: UUID,
    val targetShiftId: UUID,
    val targetEmployeeId: UUID,
    val offeredShiftId: UUID? = null,
    val message: String? = null,
    val status: SwapRequestStatus = SwapRequestStatus.PENDING,
    val requestedAt: Instant = Instant.now(),
    // Set when the target employee accepts/declines.
    val respondedAt: Instant? = null,
    val respondedBy: String? = null,
    // Set when an admin/manager approves/denies an employee-accepted request.
    val reviewedAt: Instant? = null,
    val reviewedBy: String? = null
)

enum class SwapRequestStatus {
    PENDING,
    // Target employee accepted; shift has NOT moved yet - awaiting
    // admin/manager sign-off before the reassignment takes effect.
    PENDING_APPROVAL,
    APPROVED,
    DENIED,
    DECLINED,
    CANCELLED
}
