package org.labormanagement.repository

import org.labormanagement.model.SchedulingPeriod
import org.labormanagement.model.SchedulingRequest
import org.labormanagement.model.SchedulingRequestStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for managing the single latest scheduling request.
 * Stores and retrieves the current scheduling business data (forecasts, budgets, periods, etc.)
 * with full versioning and audit tracking.
 *
 * Only one scheduling request is persisted at a time - the latest one.
 * For now, uses in-memory storage. In production, this would persist to a database.
 */
class SchedulingRequestRepository {
    private var latestRequest: SchedulingRequest? = null

    /**
     * Save or replace the latest scheduling request
     */
    fun save(
        name: String,
        description: String? = null,
        laborCostBudget: Double,
        salesForecast: Map<DayOfWeek, Map<LocalTime, Double>>,
        schedulingPeriod: SchedulingPeriod,
        employeeIds: List<UUID>,
        updatedBy: String = "system"
    ): SchedulingRequest {
        val current = latestRequest

        val request = if (current == null) {
            // Create new request
            SchedulingRequest(
                name = name,
                description = description,
                laborCostBudget = laborCostBudget,
                salesForecast = salesForecast,
                schedulingPeriod = schedulingPeriod,
                employeeIds = employeeIds,
                createdBy = updatedBy,
                lastUpdatedBy = updatedBy,
                status = SchedulingRequestStatus.ACTIVE
            )
        } else {
            // Update existing request
            SchedulingRequest(
                id = current.id,
                name = name,
                description = description,
                laborCostBudget = laborCostBudget,
                salesForecast = salesForecast,
                schedulingPeriod = schedulingPeriod,
                employeeIds = employeeIds,
                version = current.version + 1,
                createdTime = current.createdTime,
                createdBy = current.createdBy,
                lastUpdatedTime = Instant.now(),
                lastUpdatedBy = updatedBy,
                status = SchedulingRequestStatus.ACTIVE
            )
        }

        latestRequest = request
        return request
    }

    /**
     * Get the latest scheduling request
     */
    fun getLatest(): SchedulingRequest? {
        return latestRequest
    }

    /**
     * Update the latest scheduling request
     */
    fun update(
        name: String? = null,
        description: String? = null,
        laborCostBudget: Double? = null,
        salesForecast: Map<DayOfWeek, Map<LocalTime, Double>>? = null,
        schedulingPeriod: SchedulingPeriod? = null,
        employeeIds: List<UUID>? = null,
        updatedBy: String = "system"
    ): SchedulingRequest? {
        val current = latestRequest ?: return null

        val updated = SchedulingRequest(
            id = current.id,
            name = name ?: current.name,
            description = description ?: current.description,
            laborCostBudget = laborCostBudget ?: current.laborCostBudget,
            salesForecast = salesForecast ?: current.salesForecast,
            schedulingPeriod = schedulingPeriod ?: current.schedulingPeriod,
            employeeIds = employeeIds ?: current.employeeIds,
            version = current.version + 1,
            createdTime = current.createdTime,
            createdBy = current.createdBy,
            lastUpdatedTime = Instant.now(),
            lastUpdatedBy = updatedBy,
            status = current.status
        )

        latestRequest = updated
        return updated
    }

    /**
     * Delete the latest scheduling request
     */
    fun delete(): Boolean {
        if (latestRequest == null) return false
        latestRequest = null
        return true
    }

    /**
     * Clear the repository
     */
    fun clear() {
        latestRequest = null
    }
}
