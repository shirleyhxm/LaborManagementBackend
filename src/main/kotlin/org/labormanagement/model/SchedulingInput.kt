package org.labormanagement.model

import java.time.DayOfWeek
import java.time.LocalTime

data class SchedulingInput(
    val employees: List<Employee>,
    val laborCostBudget: Double,
    val salesForecast: Map<DayOfWeek, Map<LocalTime, Double>>, // Day -> Hour -> Expected sales
    val schedulingPeriod: SchedulingPeriod,
    val shiftDurationHours: Double = 1.0, // Minimum shift duration in hours - shifts can be longer and start/end at any time
    val optimizationObjective: OptimizationObjective = OptimizationObjective.BALANCED // Default optimization strategy
)

data class SchedulingPeriod(
    val daysToSchedule: List<DayOfWeek>,
    val operatingHours: Map<DayOfWeek, OperatingHours>
)

data class OperatingHours(
    val openTime: LocalTime,
    val closeTime: LocalTime
)
