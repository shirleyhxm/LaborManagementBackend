package org.labormanagement.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Represents sales forecast data supporting both date-specific and pattern-based forecasts.
 * This is a singleton resource - only one forecast exists at a time.
 */
data class SalesForecast(
    val id: String = "default",
    // Support both date-specific and pattern-based forecasts
    val dateSpecificForecast: Map<LocalDate, Map<LocalTime, Double>>? = null,
    val weeklyPattern: Map<DayOfWeek, Map<LocalTime, Double>>? = null,  // For recurring patterns
    val lastUpdatedAt: Instant = Instant.now(),
    val lastUpdatedBy: String = "system"
) {
    // Helper to get forecast for a specific date
    fun getForecastForDate(date: LocalDate): Map<LocalTime, Double> {
        // First try exact date match
        dateSpecificForecast?.get(date)?.let { return it }

        // Fall back to weekly pattern
        weeklyPattern?.get(date.dayOfWeek)?.let { return it }

        return emptyMap()
    }

    // Backward compatibility - keep old property accessible
    @Deprecated("Use getForecastForDate(date) or weeklyPattern instead")
    val weeklyForecast: Map<DayOfWeek, Map<LocalTime, Double>>
        get() = weeklyPattern ?: emptyMap()
}
