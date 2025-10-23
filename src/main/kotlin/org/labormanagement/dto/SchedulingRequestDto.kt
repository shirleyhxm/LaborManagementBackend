package org.labormanagement.dto

import org.labormanagement.model.SchedulingPeriod
import org.labormanagement.model.SchedulingRequest
import org.labormanagement.model.SchedulingRequestStatus
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

/**
 * Request to create a new scheduling request
 */
data class CreateSchedulingRequestDto(
    val name: String,
    val description: String? = null,
    val laborCostBudget: Double,
    val salesForecast: Map<String, Map<String, Double>>, // DayOfWeek -> Time -> Sales
    val schedulingPeriod: SchedulingPeriodDto,
    val employeeIds: List<String>,
    val createdBy: String? = null
)

/**
 * Request to update an existing scheduling request
 */
data class UpdateSchedulingRequestDto(
    val name: String? = null,
    val description: String? = null,
    val laborCostBudget: Double? = null,
    val salesForecast: Map<String, Map<String, Double>>? = null,
    val schedulingPeriod: SchedulingPeriodDto? = null,
    val employeeIds: List<String>? = null,
    val status: String? = null,
    val updatedBy: String? = null
)

/**
 * Response containing scheduling request details
 */
data class SchedulingRequestResponse(
    val id: String,
    val name: String,
    val description: String?,
    val laborCostBudget: Double,
    val salesForecast: Map<String, Map<String, Double>>,
    val schedulingPeriod: SchedulingPeriodDto,
    val employeeIds: List<String>,
    val version: Long,
    val createdTime: String,
    val createdBy: String,
    val lastUpdatedTime: String,
    val lastUpdatedBy: String,
    val status: String
)

// Extension functions for conversions

fun SchedulingRequest.toResponse(): SchedulingRequestResponse {
    return SchedulingRequestResponse(
        id = this.id.toString(),
        name = this.name,
        description = this.description,
        laborCostBudget = this.laborCostBudget,
        salesForecast = this.salesForecast.mapKeys { it.key.name }
            .mapValues { (_, timeMap) ->
                timeMap.mapKeys { it.key.toString() }
            },
        schedulingPeriod = SchedulingPeriodDto(
            daysToSchedule = this.schedulingPeriod.daysToSchedule.map { it.name },
            operatingHours = this.schedulingPeriod.operatingHours.mapKeys { it.key.name }
                .mapValues { (_, hours) ->
                    OperatingHoursDto(
                        openTime = hours.openTime.toString(),
                        closeTime = hours.closeTime.toString()
                    )
                }
        ),
        employeeIds = this.employeeIds.map { it.toString() },
        version = this.version,
        createdTime = this.createdTime.toString(),
        createdBy = this.createdBy,
        lastUpdatedTime = this.lastUpdatedTime.toString(),
        lastUpdatedBy = this.lastUpdatedBy,
        status = this.status.name
    )
}

fun CreateSchedulingRequestDto.parseSalesForecast(): Map<DayOfWeek, Map<LocalTime, Double>> {
    return this.salesForecast.mapKeys { DayOfWeek.valueOf(it.key.uppercase()) }
        .mapValues { (_, timeMap) ->
            timeMap.mapKeys { LocalTime.parse(it.key) }
        }
}

fun UpdateSchedulingRequestDto.parseSalesForecast(): Map<DayOfWeek, Map<LocalTime, Double>>? {
    return this.salesForecast?.mapKeys { DayOfWeek.valueOf(it.key.uppercase()) }
        ?.mapValues { (_, timeMap) ->
            timeMap.mapKeys { LocalTime.parse(it.key) }
        }
}

fun CreateSchedulingRequestDto.parseEmployeeIds(): List<UUID> {
    return this.employeeIds.map { UUID.fromString(it) }
}

fun UpdateSchedulingRequestDto.parseEmployeeIds(): List<UUID>? {
    return this.employeeIds?.map { UUID.fromString(it) }
}

fun UpdateSchedulingRequestDto.parseStatus(): SchedulingRequestStatus? {
    return this.status?.let {
        try {
            SchedulingRequestStatus.valueOf(it.uppercase())
        } catch (e: Exception) {
            null
        }
    }
}