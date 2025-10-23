package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import org.labormanagement.service.ShiftScheduler

class SchedulingController(
    private val shiftScheduler: ShiftScheduler
) {

    fun Route.schedulingRoutes() {
        route("/api/scheduling") {

            // Generate schedule from active scheduling request in repository
            post("/generate") {
                try {
                    // ShiftScheduler fetches the latest active SchedulingRequest from repository
                    val output = shiftScheduler.generateSchedule()

                    call.respond(HttpStatusCode.OK, output)
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to generate schedule: ${e.message}")
                    )
                }
            }

            // Get sample scheduling request data (for documentation)
            get("/sample-request") {
                val sampleData = mapOf(
                    "message" to "Use POST /api/scheduling-requests to create a scheduling request with business data",
                    "example" to mapOf(
                        "name" to "Week of 2025-01-15",
                        "description" to "Regular weekday schedule",
                        "laborCostBudget" to 5000.0,
                        "salesForecast" to mapOf(
                            "MONDAY" to mapOf(
                                "09:00" to 800.0,
                                "12:00" to 1200.0,
                                "15:00" to 1000.0,
                                "18:00" to 600.0
                            ),
                            "TUESDAY" to mapOf(
                                "09:00" to 700.0,
                                "12:00" to 1100.0,
                                "15:00" to 900.0,
                                "18:00" to 500.0
                            )
                        ),
                        "schedulingPeriod" to mapOf(
                            "daysToSchedule" to listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"),
                            "operatingHours" to mapOf(
                                "MONDAY" to mapOf(
                                    "openTime" to "09:00",
                                    "closeTime" to "21:00"
                                ),
                                "TUESDAY" to mapOf(
                                    "openTime" to "09:00",
                                    "closeTime" to "21:00"
                                )
                            )
                        ),
                        "employeeIds" to listOf("<employee-uuid-1>", "<employee-uuid-2>")
                    )
                )
                call.respond(HttpStatusCode.OK, sampleData)
            }
        }
    }
}
