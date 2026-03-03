package org.labormanagement.repository

import org.labormanagement.model.Schedule
import org.labormanagement.model.ScheduleStatus
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for managing schedules with lifecycle support.
 * Stores complete schedules with their shifts and lifecycle metadata.
 *
 * Multi-Tenancy:
 * - All schedules are scoped to a business via businessId
 * - Business index enables fast lookup of all schedules for a business
 *
 * Date Range Uniqueness:
 * - Only one schedule can exist per business for a given date range (startDate + endDate)
 * - Saving a new schedule with an existing date range will replace the previous schedule
 * - Assumes frontend never creates overlapping but not identical date ranges
 */
class ScheduleRepository {
    private val schedules = ConcurrentHashMap<UUID, Schedule>()
    // Business index: businessId -> Set of schedule IDs
    private val businessIndex = ConcurrentHashMap<UUID, MutableSet<UUID>>()
    // Index: (businessId, startDate, endDate) -> scheduleId for fast date range lookups
    private val dateRangeIndex = ConcurrentHashMap<BusinessDateRange, UUID>()

    /**
     * Save a schedule. If a schedule with the same date range already exists for this business,
     * it will be removed and replaced with the new schedule.
     */
    fun save(schedule: Schedule): Schedule {
        val dateRange = BusinessDateRange(
            schedule.businessId,
            schedule.schedulePeriod.startDate,
            schedule.schedulePeriod.endDate
        )

        // Check if there's an existing schedule for this date range in this business
        val existingScheduleId = dateRangeIndex[dateRange]
        if (existingScheduleId != null && existingScheduleId != schedule.id) {
            // Remove the old schedule from all indices
            businessIndex[schedule.businessId]?.remove(existingScheduleId)
            schedules.remove(existingScheduleId)
        }

        // Save the new schedule and update indices
        schedules[schedule.id] = schedule
        businessIndex.computeIfAbsent(schedule.businessId) { ConcurrentHashMap.newKeySet() }.add(schedule.id)
        dateRangeIndex[dateRange] = schedule.id

        return schedule
    }

    // ===== Business-Scoped Methods (Multi-Tenant) =====

    /**
     * Find schedule by business ID and schedule ID
     */
    fun findById(businessId: UUID, id: UUID): Schedule? {
        val schedule = schedules[id]
        return if (schedule?.businessId == businessId) schedule else null
    }

    /**
     * Find all schedules for a business, sorted by creation date (newest first)
     */
    fun findAllByBusiness(businessId: UUID): List<Schedule> {
        val scheduleIds = businessIndex[businessId] ?: return emptyList()
        return scheduleIds
            .mapNotNull { schedules[it] }
            .sortedByDescending { it.createdAt }
    }

    /**
     * Find schedules by business and status
     */
    fun findByBusinessAndStatus(businessId: UUID, status: ScheduleStatus): List<Schedule> {
        val scheduleIds = businessIndex[businessId] ?: return emptyList()
        return scheduleIds
            .mapNotNull { schedules[it] }
            .filter { it.status == status }
            .sortedByDescending { it.createdAt }
    }

    /**
     * Find schedule by business ID and date range (startDate and endDate)
     */
    fun findByBusinessAndDateRange(businessId: UUID, startDate: LocalDate, endDate: LocalDate): Schedule? {
        val dateRange = BusinessDateRange(businessId, startDate, endDate)
        val scheduleId = dateRangeIndex[dateRange] ?: return null
        val schedule = schedules[scheduleId]
        return if (schedule?.businessId == businessId) schedule else null
    }

    /**
     * Update an existing schedule
     */
    fun update(id: UUID, schedule: Schedule): Schedule {
        val oldSchedule = schedules[id]
            ?: throw IllegalArgumentException("Schedule not found: $id")

        // Remove old index entry if date range changed
        val oldDateRange = BusinessDateRange(
            oldSchedule.businessId,
            oldSchedule.schedulePeriod.startDate,
            oldSchedule.schedulePeriod.endDate
        )
        val newDateRange = BusinessDateRange(
            schedule.businessId,
            schedule.schedulePeriod.startDate,
            schedule.schedulePeriod.endDate
        )

        if (oldDateRange != newDateRange) {
            dateRangeIndex.remove(oldDateRange)

            // Check if new date range conflicts with another schedule in this business
            val existingScheduleId = dateRangeIndex[newDateRange]
            if (existingScheduleId != null && existingScheduleId != id) {
                businessIndex[schedule.businessId]?.remove(existingScheduleId)
                schedules.remove(existingScheduleId)
            }
        }

        // If business changed, update business index
        if (oldSchedule.businessId != schedule.businessId) {
            businessIndex[oldSchedule.businessId]?.remove(id)
            businessIndex.computeIfAbsent(schedule.businessId) { ConcurrentHashMap.newKeySet() }.add(id)
        }

        // Update schedule and index
        schedules[id] = schedule
        dateRangeIndex[newDateRange] = schedule.id

        return schedule
    }

    /**
     * Delete a schedule (only drafts can be deleted)
     */
    fun delete(id: UUID): Boolean {
        val schedule = schedules[id]
            ?: throw IllegalArgumentException("Schedule not found: $id")

        if (schedule.status != ScheduleStatus.DRAFT) {
            throw IllegalStateException("Cannot delete published or archived schedule. Only drafts can be deleted.")
        }

        // Remove from all indices
        val dateRange = BusinessDateRange(
            schedule.businessId,
            schedule.schedulePeriod.startDate,
            schedule.schedulePeriod.endDate
        )
        dateRangeIndex.remove(dateRange)
        businessIndex[schedule.businessId]?.remove(id)

        return schedules.remove(id) != null
    }

    /**
     * Force delete a schedule regardless of status.
     * Used for TTL cleanup and administrative operations.
     *
     * @param id The schedule ID to delete
     * @return true if deleted, false if not found
     */
    fun forceDelete(id: UUID): Boolean {
        val schedule = schedules[id] ?: return false

        // Remove from all indices
        val dateRange = BusinessDateRange(
            schedule.businessId,
            schedule.schedulePeriod.startDate,
            schedule.schedulePeriod.endDate
        )
        dateRangeIndex.remove(dateRange)
        businessIndex[schedule.businessId]?.remove(id)

        return schedules.remove(id) != null
    }
}

/**
 * Key for business-scoped date range indexing.
 * Two schedules in the same business have the same date range if their start and end dates are identical.
 */
private data class BusinessDateRange(
    val businessId: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate
)
