package org.labormanagement.model

import java.time.Instant
import java.time.LocalDate
import java.util.*

/**
 * Represents a timeoff request by an employee
 */
data class TimeoffRequest(
    val id: UUID = UUID.randomUUID(),
    val employeeId: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val reason: String,
    val status: TimeoffStatus = TimeoffStatus.PENDING,
    val requestedAt: Instant = Instant.now(),
    val reviewedAt: Instant? = null,
    val reviewedBy: String? = null,      // User ID of approver/denier
    val reviewNotes: String = "",
    val totalDays: Int = calculateDays(startDate, endDate)
) {
    companion object {
        private fun calculateDays(start: LocalDate, end: LocalDate): Int {
            return (end.toEpochDay() - start.toEpochDay()).toInt() + 1
        }
    }

    /**
     * Check if the timeoff request is approved
     */
    fun isApproved(): Boolean = status == TimeoffStatus.APPROVED

    /**
     * Check if the timeoff request is active (approved and within date range)
     */
    fun isActive(currentDate: LocalDate = LocalDate.now()): Boolean {
        return isApproved() && !currentDate.isBefore(startDate) && !currentDate.isAfter(endDate)
    }
}

/**
 * Status of a timeoff request
 */
enum class TimeoffStatus {
    PENDING,    // Waiting for approval
    APPROVED,   // Approved by manager
    DENIED,     // Denied by manager
    CANCELLED   // Cancelled by employee
}
