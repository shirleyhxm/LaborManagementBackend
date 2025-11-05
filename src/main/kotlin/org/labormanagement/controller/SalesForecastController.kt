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
                val forecast = salesForecastRepository.update(
                    weeklyForecast = request.weeklyForecast,
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

data class UpdateSalesForecastRequest(
    val weeklyForecast: Map<DayOfWeek, Map<LocalTime, Double>>,
    val updatedBy: String? = null
)