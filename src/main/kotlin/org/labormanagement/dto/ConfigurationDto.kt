package org.labormanagement.dto

import org.labormanagement.model.OptimizationObjective
import org.labormanagement.model.SchedulingConfiguration

/**
 * Request to update scheduling configuration.
 * All fields are optional - only provided fields will be updated.
 */
data class UpdateConfigurationRequest(
    val minShiftDurationHours: Double? = null,
    val defaultOptimizationObjective: String? = null,
    val updatedBy: String? = null // Optional: identifies who is making the change
)

/**
 * Response containing current scheduling configuration with audit information.
 */
data class ConfigurationResponse(
    val configId: String,
    val minShiftDurationHours: Double,
    val defaultOptimizationObjective: String,
    val version: Long,
    val lastUpdatedTime: String, // ISO-8601 format
    val lastUpdatedBy: String
)

// Extension functions
fun SchedulingConfiguration.toResponse(): ConfigurationResponse {
    return ConfigurationResponse(
        configId = this.configId,
        minShiftDurationHours = this.minShiftDurationHours,
        defaultOptimizationObjective = this.defaultOptimizationObjective.name,
        version = this.version,
        lastUpdatedTime = this.lastUpdatedTime.toString(),
        lastUpdatedBy = this.lastUpdatedBy
    )
}

fun UpdateConfigurationRequest.parseOptimizationObjective(): OptimizationObjective? {
    return this.defaultOptimizationObjective?.let {
        try {
            OptimizationObjective.valueOf(it.uppercase())
        } catch (e: Exception) {
            null
        }
    }
}