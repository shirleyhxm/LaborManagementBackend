package org.labormanagement.repository

import org.labormanagement.model.SalesForecast
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Repository for managing sales forecast data with multi-tenant support.
 * Each business has its own sales forecast.
 */
class SalesForecastRepository {
    private val lock = ReentrantReadWriteLock()

    // Map of businessId -> SalesForecast
    private val forecasts = ConcurrentHashMap<UUID, SalesForecast>()

    // ===== Business-Scoped Methods (Multi-Tenant) =====

    /**
     * Get the sales forecast for a specific business.
     * Returns default forecast if none exists.
     */
    fun getByBusiness(businessId: UUID): SalesForecast = lock.read {
        forecasts.computeIfAbsent(businessId) { createDefaultForecast(businessId) }
    }

    /**
     * Update the sales forecast for a specific business.
     */
    fun updateForBusiness(
        businessId: UUID,
        dateSpecificForecast: Map<LocalDate, Map<LocalTime, Double>>? = null,
        weeklyPattern: Map<DayOfWeek, Map<LocalTime, Double>>? = null,
        updatedBy: String = "system"
    ): SalesForecast = lock.write {
        val forecast = SalesForecast(
            businessId = businessId,
            id = "forecast-$businessId",
            dateSpecificForecast = dateSpecificForecast,
            weeklyPattern = weeklyPattern,
            lastUpdatedAt = Instant.now(),
            lastUpdatedBy = updatedBy
        )
        forecasts[businessId] = forecast
        forecast
    }

    /**
     * Reset forecast to default for a specific business.
     */
    fun resetForBusiness(businessId: UUID): SalesForecast = lock.write {
        val forecast = createDefaultForecast(businessId)
        forecasts[businessId] = forecast
        forecast
    }

    /**
     * Delete forecast for a specific business.
     */
    fun deleteForBusiness(businessId: UUID): Boolean = lock.write {
        forecasts.remove(businessId) != null
    }

    companion object {
        /**
         * Create a default sales forecast with moderate sales throughout the week.
         */
        fun createDefaultForecast(businessId: UUID): SalesForecast {
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
                businessId = businessId,
                id = "forecast-$businessId",
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
