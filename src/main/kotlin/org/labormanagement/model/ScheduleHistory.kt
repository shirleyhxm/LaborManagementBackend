package org.labormanagement.model

import java.time.Instant
import java.util.UUID

/**
 * Audit record of a schedule generation event.
 * Contains a complete snapshot of the scheduling parameters, configuration,
 * and resulting schedule for historical tracking and analysis.
 */
data class ScheduleHistory(
    /**
     * Unique identifier for this history record
     */
    val id: UUID = UUID.randomUUID(),

    /**
     * Timestamp when the schedule was generated
     */
    val generatedAt: Instant = Instant.now(),

    /**
     * User or system that triggered the schedule generation
     */
    val generatedBy: String = "system",

    /**
     * Snapshot of the scheduling request used for generation
     */
    val schedulingRequest: SchedulingRequest,

    /**
     * Snapshot of the configuration used for generation
     */
    val configuration: SchedulingConfiguration,

    /**
     * The complete scheduling output (shifts, metrics, violations, etc.)
     */
    val scheduleOutput: SchedulingOutput,

    /**
     * Optional notes or description for this schedule generation
     */
    val notes: String? = null,

    /**
     * Version number for this history record (for future schema migrations)
     */
    val version: Int = 1
)