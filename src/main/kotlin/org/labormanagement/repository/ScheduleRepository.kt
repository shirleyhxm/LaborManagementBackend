package org.labormanagement.repository

import org.labormanagement.model.Schedule
import org.labormanagement.model.ScheduleStatus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for managing schedules with lifecycle support.
 * Stores complete schedules with their shifts and lifecycle metadata.
 */
class ScheduleRepository {
    private val schedules = ConcurrentHashMap<UUID, Schedule>()

    /**
     * Save a schedule
     */
    fun save(schedule: Schedule): Schedule {
        schedules[schedule.id] = schedule
        return schedule
    }

    /**
     * Find schedule by ID
     */
    fun findById(id: UUID): Schedule? {
        return schedules[id]
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
        if (!schedules.containsKey(id)) {
            throw IllegalArgumentException("Schedule not found: $id")
        }
        schedules[id] = schedule
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

        return schedules.remove(id) != null
    }
}
