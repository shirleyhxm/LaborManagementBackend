package org.labormanagement.repository

import org.labormanagement.model.TimeoffRequest
import org.labormanagement.model.TimeoffStatus
import java.time.LocalDate
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for managing timeoff requests
 */
class TimeoffRepository {
    private val timeoffRequests = ConcurrentHashMap<UUID, TimeoffRequest>()

    /**
     * Create a new timeoff request
     */
    fun create(request: TimeoffRequest): TimeoffRequest {
        timeoffRequests[request.id] = request
        return request
    }

    /**
     * Find a timeoff request by ID
     */
    fun findById(id: UUID): TimeoffRequest? {
        return timeoffRequests[id]
    }

    /**
     * Find all timeoff requests
     */
    fun findAll(): List<TimeoffRequest> {
        return timeoffRequests.values.toList()
    }

    /**
     * Find timeoff requests by employee ID
     */
    fun findByEmployeeId(employeeId: UUID): List<TimeoffRequest> {
        return timeoffRequests.values.filter { it.employeeId == employeeId }
    }

    /**
     * Find timeoff requests by status
     */
    fun findByStatus(status: TimeoffStatus): List<TimeoffRequest> {
        return timeoffRequests.values.filter { it.status == status }
    }

    /**
     * Find pending timeoff requests for an employee
     */
    fun findPendingByEmployeeId(employeeId: UUID): List<TimeoffRequest> {
        return timeoffRequests.values.filter {
            it.employeeId == employeeId && it.status == TimeoffStatus.PENDING
        }
    }

    /**
     * Find approved timeoff requests for an employee in a date range
     */
    fun findApprovedByEmployeeIdAndDateRange(
        employeeId: UUID,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<TimeoffRequest> {
        return timeoffRequests.values.filter { request ->
            request.employeeId == employeeId &&
            request.status == TimeoffStatus.APPROVED &&
            // Check if date ranges overlap
            !(request.endDate.isBefore(startDate) || request.startDate.isAfter(endDate))
        }
    }

    /**
     * Check if employee has approved timeoff on a specific date
     */
    fun hasApprovedTimeoffOnDate(employeeId: UUID, date: LocalDate): Boolean {
        return timeoffRequests.values.any { request ->
            request.employeeId == employeeId &&
            request.status == TimeoffStatus.APPROVED &&
            !date.isBefore(request.startDate) &&
            !date.isAfter(request.endDate)
        }
    }

    /**
     * Update a timeoff request
     */
    fun update(request: TimeoffRequest): TimeoffRequest {
        timeoffRequests[request.id] = request
        return request
    }

    /**
     * Delete a timeoff request
     */
    fun delete(id: UUID): Boolean {
        return timeoffRequests.remove(id) != null
    }

    /**
     * Delete all timeoff requests (for testing)
     */
    fun deleteAll() {
        timeoffRequests.clear()
    }
}
