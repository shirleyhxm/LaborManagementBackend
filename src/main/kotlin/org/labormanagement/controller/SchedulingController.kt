package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import org.labormanagement.dto.GenerateScheduleRequest
import org.labormanagement.dto.OperatingHoursDto
import org.labormanagement.dto.SchedulingPeriodDto
import org.labormanagement.dto.toModel
import org.labormanagement.dto.toResponse
import org.labormanagement.model.SchedulingInput
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

class SchedulingController(
    private val employeeRepository: org.labormanagement.repository.EmployeeRepository,
    private val shiftScheduler: org.labormanagement.service.ShiftScheduler
) {

    fun Route.schedulingRoutes() {
        route("/api/scheduling") {

            // Generate schedule
            post("/generate") {
                try {
                    val request = call.receive<GenerateScheduleRequest>()

                    // Validate and fetch employees
                    val employeeIds = request.employeeIds.mapNotNull {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }

                    if (employeeIds.size != request.employeeIds.size) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid employee IDs"))
                        return@post
                    }

                    val employees = employeeRepository.findByIds(employeeIds)

                    if (employees.size != employeeIds.size) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Some employees not found"))
                        return@post
                    }

                    // Convert sales forecast
                    val salesForecast = request.salesForecast.mapKeys {
                        DayOfWeek.valueOf(it.key.uppercase())
                    }.mapValues { (_, timeMap) ->
                        timeMap.mapKeys { LocalTime.parse(it.key) }
                    }

                    // Create scheduling input
                    // Configuration values will be fetched from repository by ShiftScheduler
                    val schedulingInput = SchedulingInput(
                        employees = employees,
                        laborCostBudget = request.laborCostBudget,
                        salesForecast = salesForecast,
                        schedulingPeriod = request.schedulingPeriod.toModel()
                    )

                    // Generate schedule
                    val output = shiftScheduler.generateSchedule(schedulingInput)

                    // Convert to response
                    val response = output.toResponse(employees)

                    call.respond(HttpStatusCode.OK, response)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            // Get sample scheduling request (for testing/documentation)
            get("/sample-request") {
                val sampleRequest = GenerateScheduleRequest(
                    employeeIds = listOf(),
                    laborCostBudget = 5000.0,
                    salesForecast = mapOf(
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
                    schedulingPeriod = SchedulingPeriodDto(
                        daysToSchedule = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"),
                        operatingHours = mapOf(
                            "MONDAY" to OperatingHoursDto(
                                "09:00",
                                "21:00"
                            ),
                            "TUESDAY" to OperatingHoursDto(
                                "09:00",
                                "21:00"
                            ),
                            "WEDNESDAY" to OperatingHoursDto(
                                "09:00",
                                "21:00"
                            ),
                            "THURSDAY" to OperatingHoursDto(
                                "09:00",
                                "21:00"
                            ),
                            "FRIDAY" to OperatingHoursDto(
                                "09:00",
                                "22:00"
                            )
                        )
                    )
                    // Note: Configuration settings (minShiftDurationHours, optimizationObjective) are managed via /api/configuration
                )
                call.respond(HttpStatusCode.OK, sampleRequest)
            }
        }
    }
}
