package org.labormanagement.service

import org.labormanagement.model.*
import org.labormanagement.repository.TimeoffRepository
import org.labormanagement.repository.EmployeeRepository
import java.time.Instant
import java.time.LocalDate
import java.util.*

/**
 * Service for managing employee timeoff requests
 */
class TimeoffService(
    private val timeoffRepository: TimeoffRepository,
    private val employeeRepository: EmployeeRepository
) {
    /**
     * Submit a timeoff request
     */
    fun submitTimeoffRequest(
        businessId: UUID,
        employeeId: UUID,
        startDate: LocalDate,
        endDate: LocalDate,
        reason: String
    ): Result<TimeoffRequest> {
        // Verify employee exists
        val employee = employeeRepository.findById(businessId, employeeId)
            ?: return Result.failure(Exception("Employee not found"))

        // Validate dates
        if (endDate.isBefore(startDate)) {
            return Result.failure(Exception("End date cannot be before start date"))
        }

        // Check for overlapping timeoff requests
        val overlapping = timeoffRepository.findApprovedByEmployeeIdAndDateRange(
            employeeId, startDate, endDate
        )
        if (overlapping.isNotEmpty()) {
            return Result.failure(Exception("Overlapping approved timeoff request already exists"))
        }

        val request = TimeoffRequest(
            businessId = businessId,
            employeeId = employeeId,
            startDate = startDate,
            endDate = endDate,
            reason = reason,
            status = TimeoffStatus.PENDING
        )

        val created = timeoffRepository.create(request)
        return Result.success(created)
    }

    /**
     * Cancel a pending timeoff request
     */
    fun cancelTimeoffRequest(
        requestId: UUID,
        employeeId: UUID
    ): Result<TimeoffRequest> {
        val request = timeoffRepository.findById(requestId)
            ?: return Result.failure(Exception("Timeoff request not found"))

        // Verify request belongs to employee
        if (request.employeeId != employeeId) {
            return Result.failure(Exception("Timeoff request does not belong to this employee"))
        }

        // Can only cancel pending or approved requests
        if (request.status == TimeoffStatus.CANCELLED || request.status == TimeoffStatus.DENIED) {
            return Result.failure(Exception("Cannot cancel a request that is already ${request.status.name.lowercase()}"))
        }

        val cancelled = request.copy(
            status = TimeoffStatus.CANCELLED,
            reviewedAt = Instant.now()
        )

        val updated = timeoffRepository.update(cancelled)
        return Result.success(updated)
    }

    /**
     * Approve a timeoff request (manager/admin only)
     */
    fun approveTimeoffRequest(
        businessId: UUID,
        requestId: UUID,
        reviewerId: String,
        reviewNotes: String = ""
    ): Result<TimeoffRequest> {
        val request = timeoffRepository.findById(requestId)
            ?: return Result.failure(Exception("Timeoff request not found"))

        if (request.status != TimeoffStatus.PENDING) {
            return Result.failure(Exception("Can only approve pending requests"))
        }

        // Get employee to update availability
        val employee = employeeRepository.findById(businessId, request.employeeId)
            ?: return Result.failure(Exception("Employee not found"))

        // Approve the request
        val approved = request.copy(
            status = TimeoffStatus.APPROVED,
            reviewedAt = Instant.now(),
            reviewedBy = reviewerId,
            reviewNotes = reviewNotes
        )

        val updated = timeoffRepository.update(approved)

        // Update employee availability
        updateEmployeeAvailability(employee, request)

        return Result.success(updated)
    }

    /**
     * Deny a timeoff request (manager/admin only)
     */
    fun denyTimeoffRequest(
        requestId: UUID,
        reviewerId: String,
        reviewNotes: String = ""
    ): Result<TimeoffRequest> {
        val request = timeoffRepository.findById(requestId)
            ?: return Result.failure(Exception("Timeoff request not found"))

        if (request.status != TimeoffStatus.PENDING) {
            return Result.failure(Exception("Can only deny pending requests"))
        }

        val denied = request.copy(
            status = TimeoffStatus.DENIED,
            reviewedAt = Instant.now(),
            reviewedBy = reviewerId,
            reviewNotes = reviewNotes
        )

        val updated = timeoffRepository.update(denied)
        return Result.success(updated)
    }

    /**
     * Update employee availability based on approved timeoff
     * Note: This removes availability for the days of the timeoff request
     */
    private fun updateEmployeeAvailability(employee: Employee, timeoffRequest: TimeoffRequest) {
        // Get days of week affected by timeoff
        val daysAffected = mutableSetOf<java.time.DayOfWeek>()
        var currentDate = timeoffRequest.startDate
        while (!currentDate.isAfter(timeoffRequest.endDate)) {
            daysAffected.add(currentDate.dayOfWeek)
            currentDate = currentDate.plusDays(1)
        }

        // Note: In a real system, we would temporarily remove or mark availability as unavailable
        // For this implementation, we're noting that the availability should be checked
        // against approved timeoff when scheduling
        // The actual scheduling system should query timeoff when checking availability
    }

    /**
     * Get timeoff requests for an employee
     */
    fun getTimeoffRequestsByEmployee(employeeId: UUID): List<TimeoffRequest> {
        return timeoffRepository.findByEmployeeId(employeeId)
    }

    /**
     * Get pending timeoff requests (for managers)
     */
    fun getPendingTimeoffRequests(): List<TimeoffRequest> {
        return timeoffRepository.findByStatus(TimeoffStatus.PENDING)
    }

    /**
     * Get all timeoff requests (for admins)
     */
    fun getAllTimeoffRequests(): List<TimeoffRequest> {
        return timeoffRepository.findAll()
    }

    /**
     * Get timeoff request by ID
     */
    fun getTimeoffRequestById(id: UUID): TimeoffRequest? {
        return timeoffRepository.findById(id)
    }

    /**
     * Check if employee has approved timeoff on a date
     */
    fun hasApprovedTimeoffOnDate(employeeId: UUID, date: LocalDate): Boolean {
        return timeoffRepository.hasApprovedTimeoffOnDate(employeeId, date)
    }

    /**
     * Get approved timeoff requests for an employee in a date range
     */
    fun getApprovedTimeoffInRange(
        employeeId: UUID,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<TimeoffRequest> {
        return timeoffRepository.findApprovedByEmployeeIdAndDateRange(employeeId, startDate, endDate)
    }
}
