package org.labormanagement.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

/**
 * Represents sales forecast data for a typical week.
 * This is a singleton resource - only one forecast exists at a time.
 */
data class SalesForecast(
    val id: String = "default",
    val weeklyForecast: Map<DayOfWeek, Map<LocalTime, Double>>, // Day -> Hour -> Expected sales
    val lastUpdatedAt: Instant = Instant.now(),
    val lastUpdatedBy: String = "system"
)
