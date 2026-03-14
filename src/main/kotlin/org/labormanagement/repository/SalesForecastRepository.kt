package org.labormanagement.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.config.GsonConfig.createGson
import org.labormanagement.database.SalesForecasts
import org.labormanagement.model.SalesForecast
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * PostgreSQL-backed sales forecast repository using Exposed ORM.
 * Manages sales forecasts with multi-tenant support.
 */
class SalesForecastRepository(
    private val gson: Gson = createGson()
) {
    private val logger = LoggerFactory.getLogger(SalesForecastRepository::class.java)

    /**
     * Get the sales forecast for a specific business.
     * Returns default forecast if none exists.
     */
    fun getByBusiness(businessId: UUID): SalesForecast = transaction {
        val id = "forecast-$businessId"

        val existing = SalesForecasts.selectAll().where {
            (SalesForecasts.id eq id) and (SalesForecasts.businessId eq businessId)
        }.singleOrNull()

        if (existing != null) {
            existing.toSalesForecast()
        } else {
            val defaultForecast = createDefaultForecast(businessId)
            // Save default forecast to database
            SalesForecasts.insert {
                it[SalesForecasts.id] = defaultForecast.id
                it[SalesForecasts.businessId] = defaultForecast.businessId
                it[SalesForecasts.dateSpecificForecast] = defaultForecast.dateSpecificForecast?.let { forecast ->
                    gson.toJson(forecast)
                }
                it[SalesForecasts.weeklyPattern] = defaultForecast.weeklyPattern?.let { pattern ->
                    gson.toJson(pattern)
                }
                it[SalesForecasts.lastUpdatedAt] = defaultForecast.lastUpdatedAt
                it[SalesForecasts.lastUpdatedBy] = defaultForecast.lastUpdatedBy
            }
            defaultForecast
        }
    }

    /**
     * Update the sales forecast for a specific business.
     */
    fun updateForBusiness(
        businessId: UUID,
        dateSpecificForecast: Map<LocalDate, Map<LocalTime, Double>>? = null,
        weeklyPattern: Map<DayOfWeek, Map<LocalTime, Double>>? = null,
        updatedBy: String = "system"
    ): SalesForecast = transaction {
        val id = "forecast-$businessId"
        val forecast = SalesForecast(
            businessId = businessId,
            id = id,
            dateSpecificForecast = dateSpecificForecast,
            weeklyPattern = weeklyPattern,
            lastUpdatedAt = Instant.now(),
            lastUpdatedBy = updatedBy
        )

        // Use upsert for PostgreSQL compatibility (composite primary key)
        SalesForecasts.upsert(
            keys = arrayOf(SalesForecasts.id, SalesForecasts.businessId)
        ) {
            it[SalesForecasts.id] = forecast.id
            it[SalesForecasts.businessId] = forecast.businessId
            it[SalesForecasts.dateSpecificForecast] = forecast.dateSpecificForecast?.let { df ->
                gson.toJson(df)
            }
            it[SalesForecasts.weeklyPattern] = forecast.weeklyPattern?.let { wp ->
                gson.toJson(wp)
            }
            it[SalesForecasts.lastUpdatedAt] = forecast.lastUpdatedAt
            it[SalesForecasts.lastUpdatedBy] = forecast.lastUpdatedBy
        }

        forecast
    }

    /**
     * Reset forecast to default for a specific business.
     */
    fun resetForBusiness(businessId: UUID): SalesForecast = transaction {
        val forecast = createDefaultForecast(businessId)
        val id = "forecast-$businessId"

        SalesForecasts.upsert(
            keys = arrayOf(SalesForecasts.id, SalesForecasts.businessId)
        ) {
            it[SalesForecasts.id] = id
            it[SalesForecasts.businessId] = forecast.businessId
            it[SalesForecasts.dateSpecificForecast] = forecast.dateSpecificForecast?.let { df ->
                gson.toJson(df)
            }
            it[SalesForecasts.weeklyPattern] = forecast.weeklyPattern?.let { wp ->
                gson.toJson(wp)
            }
            it[SalesForecasts.lastUpdatedAt] = forecast.lastUpdatedAt
            it[SalesForecasts.lastUpdatedBy] = forecast.lastUpdatedBy
        }

        forecast
    }

    /**
     * Delete forecast for a specific business.
     */
    fun deleteForBusiness(businessId: UUID): Boolean = transaction {
        val id = "forecast-$businessId"
        val deletedCount = SalesForecasts.deleteWhere {
            (SalesForecasts.id eq id) and (SalesForecasts.businessId eq businessId)
        }
        deletedCount > 0
    }

    /**
     * Extension function to convert ResultRow to SalesForecast domain model
     */
    private fun ResultRow.toSalesForecast(): SalesForecast {
        // Define type tokens for complex types
        val dateSpecificType = object : TypeToken<Map<LocalDate, Map<LocalTime, Double>>>() {}.type
        val weeklyPatternType = object : TypeToken<Map<DayOfWeek, Map<LocalTime, Double>>>() {}.type

        return SalesForecast(
            id = this[SalesForecasts.id],
            businessId = this[SalesForecasts.businessId],
            dateSpecificForecast = this[SalesForecasts.dateSpecificForecast]?.let { json ->
                gson.fromJson(json, dateSpecificType)
            },
            weeklyPattern = this[SalesForecasts.weeklyPattern]?.let { json ->
                gson.fromJson(json, weeklyPatternType)
            },
            lastUpdatedAt = this[SalesForecasts.lastUpdatedAt],
            lastUpdatedBy = this[SalesForecasts.lastUpdatedBy]
        )
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
