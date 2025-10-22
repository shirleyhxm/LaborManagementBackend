package org.labormanagement.model

import java.time.Instant

/**
 * Configuration settings for the shift scheduling algorithm.
 * These settings control various aspects of how schedules are generated.
 * Includes versioning and audit information for tracking changes.
 */
data class SchedulingConfiguration(
    /**
     * Minimum shift duration in hours.
     * Shifts can be longer and start/end at any time, but must be at least this long.
     * Default: 1.0 hour
     */
    val minShiftDurationHours: Double = 1.0,

    /**
     * Default optimization objective to use when generating schedules.
     * Default: BALANCED
     */
    val defaultOptimizationObjective: OptimizationObjective = OptimizationObjective.BALANCED,

    /**
     * Configuration identifier for storage/retrieval.
     * For now, we use a singleton configuration ("default").
     * In the future, this could support multiple configurations per tenant/location.
     */
    val configId: String = "default",

    /**
     * Version number for this configuration.
     * Incremented each time the configuration is updated.
     * Used for optimistic locking and change tracking.
     */
    val version: Long = 1,

    /**
     * Timestamp when this configuration was last updated.
     * Used for audit and tracking purposes.
     */
    val lastUpdatedTime: Instant = Instant.now(),

    /**
     * Identifier of the user who last updated this configuration.
     * Used for audit and accountability purposes.
     * Defaults to "system" for initial/automated configurations.
     */
    val lastUpdatedBy: String = "system"
)