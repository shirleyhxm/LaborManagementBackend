package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.labormanagement.repository.SalesForecastRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class SalesForecastController(
    private val salesForecastRepository: SalesForecastRepository
) {
    fun Route.salesForecastRoutes() {
        // GET /api/sales-forecast - Get current sales forecast
        get("/api/sales-forecast") {
            val forecast = salesForecastRepository.get()
            call.respond(HttpStatusCode.OK, forecast)
        }

        // PUT /api/sales-forecast - Update sales forecast
        put("/api/sales-forecast") {
            try {
                val request = call.receive<UpdateSalesForecastRequest>()

                // Validate that at least one forecast type is provided
                if (request.dateSpecificForecast == null && request.weeklyPattern == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "At least one of dateSpecificForecast or weeklyPattern must be provided")
                    )
                    return@put
                }

                val forecast = salesForecastRepository.update(
                    dateSpecificForecast = request.dateSpecificForecast,
                    weeklyPattern = request.weeklyPattern,
                    updatedBy = request.updatedBy ?: "system"
                )
                call.respond(HttpStatusCode.OK, forecast)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid request: ${e.message}")
                )
            }
        }

        // POST /api/sales-forecast/reset - Reset to default forecast
        post("/api/sales-forecast/reset") {
            val forecast = salesForecastRepository.reset()
            call.respond(HttpStatusCode.OK, forecast)
        }
    }
}

/**
 * Request DTO for updating sales forecast.
 * Supports both date-specific forecasts and recurring weekly patterns.
 * At least one of dateSpecificForecast or weeklyPattern must be provided.
 */
data class UpdateSalesForecastRequest(
    // Optional: date-specific forecasts (e.g., {"2024-01-15": {"09:00": 1000.0, "10:00": 1200.0}})
    val dateSpecificForecast: Map<LocalDate, Map<LocalTime, Double>>? = null,
    // Optional: weekly pattern forecasts (e.g., {"MONDAY": {"09:00": 800.0, "10:00": 1000.0}})
    val weeklyPattern: Map<DayOfWeek, Map<LocalTime, Double>>? = null,
    val updatedBy: String? = null
)