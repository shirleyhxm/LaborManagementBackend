package org.labormanagement.model

import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

data class ScheduleInput(
    val employeeIds: List<UUID>,
    val laborCostBudget: Double,
    val salesForecast: Map<DayOfWeek, Map<LocalTime, Double>>, // Day -> Hour -> Expected sales
    val schedulePeriod: SchedulePeriod,
    val minShiftDurationHours: Double = 0.0, // Optional
    val optimizationObjective: OptimizationObjective = OptimizationObjective.BALANCED // Optional
)

data class SchedulePeriod(
    val daysToSchedule: List<DayOfWeek>,
    val operatingHours: Map<DayOfWeek, OperatingHours>
)

data class OperatingHours(
    val openTime: LocalTime,
    val closeTime: LocalTime
)
