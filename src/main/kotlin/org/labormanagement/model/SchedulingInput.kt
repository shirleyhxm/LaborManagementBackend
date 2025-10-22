package org.labormanagement.model

import java.time.DayOfWeek
import java.time.LocalTime

data class SchedulingInput(
    val employees: List<Employee>,
    val laborCostBudget: Double,
    val salesForecast: Map<DayOfWeek, Map<LocalTime, Double>>, // Day -> Hour -> Expected sales
    val schedulingPeriod: SchedulingPeriod
)

data class SchedulingPeriod(
    val daysToSchedule: List<DayOfWeek>,
    val operatingHours: Map<DayOfWeek, OperatingHours>
)

data class OperatingHours(
    val openTime: LocalTime,
    val closeTime: LocalTime
)
