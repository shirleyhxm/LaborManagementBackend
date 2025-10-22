package org.labormanagement.repository

import org.labormanagement.model.OptimizationObjective
import org.labormanagement.model.SchedulingConfiguration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for managing scheduling configuration settings.
 * Maintains versioning and audit information for all configuration changes.
 * For now, uses in-memory storage. In production, this would persist to a database.
 */
class SchedulingConfigurationRepository {
    private val configurations = ConcurrentHashMap<String, SchedulingConfiguration>()

    init {
        // Initialize with default configuration
        configurations["default"] = SchedulingConfiguration()
    }

    /**
     * Get configuration by ID.
     * Returns the default configuration if the specified ID is not found.
     */
    fun get(configId: String = "default"): SchedulingConfiguration {
        return configurations.getOrDefault(configId, SchedulingConfiguration())
    }

    /**
     * Get the latest configuration (always returns the most recent version).
     * This is the method that should be used by services to fetch configuration.
     */
    fun getLatest(configId: String = "default"): SchedulingConfiguration {
        return get(configId)
    }

    /**
     * Get the default configuration (always returns a valid configuration).
     */
    fun getDefault(): SchedulingConfiguration {
        return getLatest("default")
    }

    /**
     * Update configuration settings.
     * Increments version and records audit information.
     * Returns the updated configuration.
     */
    fun update(
        configId: String = "default",
        minShiftDurationHours: Double? = null,
        defaultOptimizationObjective: OptimizationObjective? = null,
        updatedBy: String = "system"
    ): SchedulingConfiguration {
        val current = get(configId)

        val updated = SchedulingConfiguration(
            minShiftDurationHours = minShiftDurationHours ?: current.minShiftDurationHours,
            defaultOptimizationObjective = defaultOptimizationObjective ?: current.defaultOptimizationObjective,
            configId = configId,
            version = current.version + 1,
            lastUpdatedTime = Instant.now(),
            lastUpdatedBy = updatedBy
        )

        configurations[configId] = updated
        return updated
    }

    /**
     * Reset configuration to default values.
     * Increments version and records audit information.
     */
    fun reset(configId: String = "default", resetBy: String = "system"): SchedulingConfiguration {
        val current = get(configId)

        val defaultConfig = SchedulingConfiguration(
            configId = configId,
            version = current.version + 1,
            lastUpdatedTime = Instant.now(),
            lastUpdatedBy = resetBy
        )

        configurations[configId] = defaultConfig
        return defaultConfig
    }

    /**
     * Get all configurations (for admin purposes).
     */
    fun getAll(): List<SchedulingConfiguration> {
        return configurations.values.toList()
    }

    /**
     * Get configuration history for a specific config ID.
     * In production, this would return historical versions from the database.
     * For now, only returns the current version.
     */
    fun getHistory(configId: String = "default"): List<SchedulingConfiguration> {
        val current = configurations[configId]
        return if (current != null) listOf(current) else emptyList()
    }
}