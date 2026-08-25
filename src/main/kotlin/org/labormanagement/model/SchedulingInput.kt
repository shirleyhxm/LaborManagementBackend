package org.labormanagement.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import java.util.UUID

data class ScheduleInput(
    val businessId: UUID,
    val employeeIds: List<UUID>,
    val laborCostBudget: Double,
    val schedulePeriod: SchedulePeriod,
    val optimizationObjective: OptimizationObjective = OptimizationObjective.BALANCED // Optional
)

/**
 * Wire shape for the POST /schedules/generate request body. Deliberately omits
 * businessId: the client never sends it in the body, only as the {businessId}
 * path segment, so ScheduleController combines this with the path value to
 * build the real (always-populated) ScheduleInput passed to ShiftScheduler.
 *
 * Also deliberately omits a labor budget - ShiftScheduler.generateSchedule
 * resolves the real cap itself from the business's saved Configurations
 * budget, so there's nothing for a caller to usefully supply here.
 */
data class ScheduleInputPayload(
    val employeeIds: List<UUID>,
    val schedulePeriod: SchedulePeriod,
    val optimizationObjective: OptimizationObjective = OptimizationObjective.BALANCED // Optional
) {
    fun toScheduleInput(businessId: UUID): ScheduleInput = ScheduleInput(
        businessId = businessId,
        employeeIds = employeeIds,
        laborCostBudget = Double.MAX_VALUE, // Overwritten by ShiftScheduler.generateSchedule
        schedulePeriod = schedulePeriod,
        optimizationObjective = optimizationObjective
    )
}

data class SchedulePeriod(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val operatingHours: Map<LocalDate, OperatingHours>
) {
    /**
     * Returns all dates in the scheduling period (inclusive)
     */
    fun getAllDates(): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var currentDate = startDate
        while (!currentDate.isAfter(endDate)) {
            dates.add(currentDate)
            currentDate = currentDate.plusDays(1)
        }
        return dates
    }

    /**
     * The duration of the scheduling period
     */
    val duration: Period
        get() = Period.between(startDate, endDate.plusDays(1))

    /**
     * Total number of days in the scheduling period (inclusive)
     */
    val totalDays: Int
        get() = getAllDates().size
}

data class OperatingHours(
    val openTime: LocalTime,
    val closeTime: LocalTime
)
