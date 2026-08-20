package org.labormanagement.dto

data class CreateSwapRequestRequest(
    val targetShiftId: String,
    val message: String? = null
)

data class SwapRequestResponse(
    val id: String,
    val requestingEmployeeId: String,
    val requestingEmployeeName: String,
    val targetEmployeeId: String,
    val targetEmployeeName: String,
    val targetShiftId: String,
    val shiftDate: String,
    val shiftStartTime: String,
    val shiftEndTime: String,
    val message: String?,
    val status: String,
    val requestedAt: String,
    val respondedAt: String?,
    val reviewedAt: String?,
    val reviewedBy: String?
)

data class SwapRequestsListResponse(
    val incoming: List<SwapRequestResponse>,
    val outgoing: List<SwapRequestResponse>
)
