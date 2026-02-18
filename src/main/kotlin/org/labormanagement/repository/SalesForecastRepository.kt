package org.labormanagement.repository

import org.labormanagement.model.SalesForecast
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Repository for managing sales forecast data.
 * Maintains a single forecast entry that can be read and updated.
 */
class SalesForecastRepository {
    private val lock = ReentrantReadWriteLock()

    // Single forecast entry with default data
    private var forecast: SalesForecast = createDefaultForecast()

    /**
     * Get the current sales forecast.
     */
    fun get(): SalesForecast = lock.read {
        forecast
    }

    /**
     * Update the sales forecast with date-specific and/or weekly pattern data.
     */
    fun update(
        dateSpecificForecast: Map<LocalDate, Map<LocalTime, Double>>? = null,
        weeklyPattern: Map<DayOfWeek, Map<LocalTime, Double>>? = null,
        updatedBy: String = "system"
    ): SalesForecast = lock.write {
        forecast = SalesForecast(
            id = "default",
            dateSpecificForecast = dateSpecificForecast,
            weeklyPattern = weeklyPattern,
            lastUpdatedAt = Instant.now(),
            lastUpdatedBy = updatedBy
        )
        forecast
    }

    /**
     * Reset to default forecast.
     */
    fun reset(): SalesForecast = lock.write {
        forecast = createDefaultForecast()
        forecast
    }

    companion object {
        /**
         * Create a default sales forecast with moderate sales throughout the week.
         */
        fun createDefaultForecast(): SalesForecast {
            // Default forecast: moderate sales Monday-Friday, higher on weekends
            val weekdayForecast = (8..20).associate { hour ->
                LocalTime.of(hour, 0) to when (hour) {
                    in 8..10 -> 300.0   // Morning ramp-up
                    in 11..13 -> 600.0  // Lunch rush
                    in 14..16 -> 400.0  // Afternoon
                    in 17..19 -> 500.0  // Evening rush
                    else -> 250.0       // Opening/closing hours
                }
            }

            val weekendForecast = (8..20).associate { hour ->
                LocalTime.of(hour, 0) to when (hour) {
                    in 8..10 -> 400.0   // Brunch starts
                    in 11..13 -> 700.0  // Brunch/lunch rush
                    in 14..16 -> 550.0  // Afternoon
                    in 17..19 -> 650.0  // Dinner rush
                    else -> 300.0       // Opening/closing hours
                }
            }

            return SalesForecast(
                id = "default",
                weeklyPattern = mapOf(
                    DayOfWeek.MONDAY to weekdayForecast,
                    DayOfWeek.TUESDAY to weekdayForecast,
                    DayOfWeek.WEDNESDAY to weekdayForecast,
                    DayOfWeek.THURSDAY to weekdayForecast,
                    DayOfWeek.FRIDAY to weekdayForecast,
                    DayOfWeek.SATURDAY to weekendForecast,
                    DayOfWeek.SUNDAY to weekendForecast
                ),
                lastUpdatedAt = Instant.now(),
                lastUpdatedBy = "system"
            )
        }
    }
}