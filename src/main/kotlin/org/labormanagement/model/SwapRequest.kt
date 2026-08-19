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
    val respondedAt: Instant? = null,
    val respondedBy: String? = null
)

enum class SwapRequestStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    CANCELLED
}
