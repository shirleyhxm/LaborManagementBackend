package org.labormanagement.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * Represents a persistent scheduling request with all business data.
 * This model decouples scheduling parameters from the API request,
 * allowing them to be stored, versioned, and reused.
 */
data class SchedulingRequest(
    /**
     * Unique identifier for this scheduling request
     */
    val id: UUID = UUID.randomUUID(),

    /**
     * Human-readable name for this request (e.g., "Week of 2025-01-15", "January Schedule")
     */
    val name: String,

    /**
     * Optional description providing context for this request
     */
    val description: String? = null,

    /**
     * Labor cost budget for this scheduling period
     */
    val laborCostBudget: Double,

    /**
     * Sales forecast: Day -> Hour -> Expected sales amount
     */
    val salesForecast: Map<DayOfWeek, Map<LocalTime, Double>>,

    /**
     * Scheduling period definition (days and operating hours)
     */
    val schedulingPeriod: SchedulingPeriod,

    /**
     * List of employee IDs to include in this schedule
     */
    val employeeIds: List<UUID>,

    /**
     * Version number for this request (incremented on updates)
     */
    val version: Long = 1,

    /**
     * Timestamp when this request was created
     */
    val createdTime: Instant = Instant.now(),

    /**
     * User who created this request
     */
    val createdBy: String = "system",

    /**
     * Timestamp when this request was last updated
     */
    val lastUpdatedTime: Instant = Instant.now(),

    /**
     * User who last updated this request
     */
    val lastUpdatedBy: String = "system",

    /**
     * Status of this request
     */
    val status: SchedulingRequestStatus = SchedulingRequestStatus.DRAFT
)

/**
 * Status lifecycle for scheduling requests
 */
enum class SchedulingRequestStatus {
    /**
     * Request is being prepared and not yet ready for scheduling
     */
    DRAFT,

    /**
     * Request is ready to be used for schedule generation
     */
    ACTIVE,

    /**
     * Request has been archived and is no longer active
     */
    ARCHIVED
}
