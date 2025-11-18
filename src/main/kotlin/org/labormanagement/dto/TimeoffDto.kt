package org.labormanagement.dto

import org.labormanagement.model.TimeoffRequest
import org.labormanagement.model.TimeoffStatus
import java.time.LocalDate
import java.util.*

/**
 * Request to submit a timeoff request
 */
data class SubmitTimeoffRequest(
    val employeeId: String,
    val startDate: String,  // Format: YYYY-MM-DD
    val endDate: String,    // Format: YYYY-MM-DD
    val reason: String
)

/**
 * Request to cancel a timeoff request
 */
data class CancelTimeoffRequest(
    val employeeId: String
)

/**
 * Request to approve/deny a timeoff request
 */
data class ReviewTimeoffRequest(
    val reviewNotes: String = ""
)

/**
 * Response for a timeoff request
 */
data class TimeoffRequestResponse(
    val id: String,
    val employeeId: String,
    val startDate: String,
    val endDate: String,
    val reason: String,
    val status: String,
    val requestedAt: String,
    val reviewedAt: String?,
    val reviewedBy: String?,
    val reviewNotes: String,
    val totalDays: Int,
    val isApproved: Boolean,
    val isActive: Boolean
)

/**
 * Extension function for conversion
 */
fun TimeoffRequest.toResponse(): TimeoffRequestResponse {
    return TimeoffRequestResponse(
        id = id.toString(),
        employeeId = employeeId.toString(),
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        reason = reason,
        status = status.name,
        requestedAt = requestedAt.toString(),
        reviewedAt = reviewedAt?.toString(),
        reviewedBy = reviewedBy,
        reviewNotes = reviewNotes,
        totalDays = totalDays,
        isApproved = isApproved(),
        isActive = isActive()
    )
}
