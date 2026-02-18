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
 * Date Range Uniqueness:
 * - Only one schedule can exist for a given date range (startDate + endDate)
 * - Saving a new schedule with an existing date range will replace the previous schedule
 * - Assumes frontend never creates overlapping but not identical date ranges
 */
class ScheduleRepository {
    private val schedules = ConcurrentHashMap<UUID, Schedule>()
    // Index: (startDate, endDate) -> scheduleId for fast date range lookups
    private val dateRangeIndex = ConcurrentHashMap<DateRange, UUID>()

    /**
     * Save a schedule. If a schedule with the same date range already exists,
     * it will be removed and replaced with the new schedule.
     */
    fun save(schedule: Schedule): Schedule {
        val dateRange = DateRange(
            schedule.schedulePeriod.startDate,
            schedule.schedulePeriod.endDate
        )

        // Check if there's an existing schedule for this date range
        val existingScheduleId = dateRangeIndex[dateRange]
        if (existingScheduleId != null && existingScheduleId != schedule.id) {
            // Remove the old schedule
            schedules.remove(existingScheduleId)
        }

        // Save the new schedule and update the index
        schedules[schedule.id] = schedule
        dateRangeIndex[dateRange] = schedule.id

        return schedule
    }

    /**
     * Find schedule by ID
     */
    fun findById(id: UUID): Schedule? {
        return schedules[id]
    }

    /**
     * Find schedule by date range (startDate and endDate)
     */
    fun findByDateRange(startDate: LocalDate, endDate: LocalDate): Schedule? {
        val dateRange = DateRange(startDate, endDate)
        val scheduleId = dateRangeIndex[dateRange] ?: return null
        return schedules[scheduleId]
    }

    /**
     * Find all schedules, sorted by creation date (newest first)
     */
    fun findAll(): List<Schedule> {
        return schedules.values
            .sortedByDescending { it.createdAt }
            .toList()
    }

    /**
     * Find schedules by status
     */
    fun findByStatus(status: ScheduleStatus): List<Schedule> {
        return schedules.values
            .filter { it.status == status }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    /**
     * Update an existing schedule
     */
    fun update(id: UUID, schedule: Schedule): Schedule {
        val oldSchedule = schedules[id]
            ?: throw IllegalArgumentException("Schedule not found: $id")

        // Remove old index entry if date range changed
        val oldDateRange = DateRange(
            oldSchedule.schedulePeriod.startDate,
            oldSchedule.schedulePeriod.endDate
        )
        val newDateRange = DateRange(
            schedule.schedulePeriod.startDate,
            schedule.schedulePeriod.endDate
        )

        if (oldDateRange != newDateRange) {
            dateRangeIndex.remove(oldDateRange)

            // Check if new date range conflicts with another schedule
            val existingScheduleId = dateRangeIndex[newDateRange]
            if (existingScheduleId != null && existingScheduleId != id) {
                schedules.remove(existingScheduleId)
            }
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

        // Remove from both maps
        val dateRange = DateRange(
            schedule.schedulePeriod.startDate,
            schedule.schedulePeriod.endDate
        )
        dateRangeIndex.remove(dateRange)

        return schedules.remove(id) != null
    }
}

/**
 * Key for date range indexing.
 * Two schedules have the same date range if their start and end dates are identical.
 */
private data class DateRange(
    val startDate: LocalDate,
    val endDate: LocalDate
)
