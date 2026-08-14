package org.labormanagement.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import java.util.UUID

data class ScheduleInput(
    val employeeIds: List<UUID>,
    val laborCostBudget: Double,
    val schedulePeriod: SchedulePeriod,
    val optimizationObjective: OptimizationObjective = OptimizationObjective.BALANCED, // Optional
    // Unused downstream: ShiftScheduler.generateSchedule() takes businessId as its own
    // parameter, sourced from the URL path by ScheduleController. Kept here only for
    // backward compatibility with existing callers that still set it explicitly, and
    // defaulted so the REST /generate endpoint (which never sends it in the body) can
    // deserialize successfully.
    val businessId: UUID? = null
)

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
