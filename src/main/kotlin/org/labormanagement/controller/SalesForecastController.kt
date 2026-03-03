package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.labormanagement.repository.SalesForecastRepository
import org.labormanagement.service.TenantContextHolder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class SalesForecastController(
    private val salesForecastRepository: SalesForecastRepository
) {
    fun Route.salesForecastRoutes() {
        route("/api/businesses/{businessId}/sales-forecast") {
            // GET - Get current sales forecast for business
            get {
                try {
                    // Extract and validate businessId from path
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@get
                    }

                    // Validate businessId matches tenant context
                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access sales forecast from different business"))
                        return@get
                    }

                    val forecast = salesForecastRepository.getByBusiness(businessId)
                    call.respond(HttpStatusCode.OK, forecast)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            // PUT - Update sales forecast
            put {
                try {
                    // Extract and validate businessId from path
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@put
                    }

                    // Validate businessId matches tenant context
                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access sales forecast from different business"))
                        return@put
                    }

                    val request = call.receive<UpdateSalesForecastRequest>()

                    // Validate that at least one forecast type is provided
                    if (request.dateSpecificForecast == null && request.weeklyPattern == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "At least one of dateSpecificForecast or weeklyPattern must be provided")
                        )
                        return@put
                    }

                    val forecast = salesForecastRepository.updateForBusiness(
                        businessId = businessId,
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

            // POST /reset - Reset to default forecast
            post("/reset") {
                try {
                    // Extract and validate businessId from path
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@post
                    }

                    // Validate businessId matches tenant context
                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access sales forecast from different business"))
                        return@post
                    }

                    val forecast = salesForecastRepository.resetForBusiness(businessId)
                    call.respond(HttpStatusCode.OK, forecast)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }
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