package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import org.labormanagement.dto.CreateSchedulingRequestDto
import org.labormanagement.dto.UpdateSchedulingRequestDto
import org.labormanagement.dto.parseEmployeeIds
import org.labormanagement.dto.parseSalesForecast
import org.labormanagement.dto.toModel
import org.labormanagement.dto.toResponse
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.SchedulingRequestRepository

/**
 * Controller for managing the single latest scheduling request.
 * Provides REST APIs for viewing and updating the current scheduling configuration.
 */
class SchedulingRequestController(
    private val schedulingRequestRepository: SchedulingRequestRepository,
    private val employeeRepository: EmployeeRepository
) {

    fun Route.schedulingRequestRoutes() {
        route("/api/scheduling-request") {

            // Get the latest scheduling request
            get {
                try {
                    val request = schedulingRequestRepository.getLatest()
                    if (request != null) {
                        call.respond(HttpStatusCode.OK, request.toResponse())
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("message" to "No scheduling request found. One will be created automatically when you generate a schedule.")
                        )
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to retrieve scheduling request: ${e.message}")
                    )
                }
            }

            // Save or update the latest scheduling request
            put {
                try {
                    val request = call.receive<CreateSchedulingRequestDto>()

                    // Validate labor cost budget
                    if (request.laborCostBudget <= 0) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "laborCostBudget must be greater than 0")
                        )
                        return@put
                    }

                    // Validate name is not blank
                    if (request.name.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "name cannot be blank")
                        )
                        return@put
                    }

                    // Parse and validate employee IDs
                    val employeeIds = try {
                        request.parseEmployeeIds()
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid employee ID format: ${e.message}")
                        )
                        return@put
                    }

                    // Validate that all employee IDs exist
                    val existingEmployees = employeeRepository.findByIds(employeeIds)
                    if (existingEmployees.size != employeeIds.size) {
                        val missingIds = employeeIds.filterNot { id ->
                            existingEmployees.any { it.id == id }
                        }
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to "Employee IDs not found: ${missingIds.joinToString(", ")}"
                            )
                        )
                        return@put
                    }

                    // Validate at least one employee is provided
                    if (employeeIds.isEmpty()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "At least one employee ID must be provided")
                        )
                        return@put
                    }

                    // Parse and validate sales forecast
                    val salesForecast = try {
                        request.parseSalesForecast()
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid sales forecast format: ${e.message}")
                        )
                        return@put
                    }

                    // Validate sales forecast has positive values
                    salesForecast.forEach { (day, timeMap) ->
                        timeMap.forEach { (time, sales) ->
                            if (sales < 0) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to "Sales forecast values must be non-negative")
                                )
                                return@put
                            }
                        }
                    }

                    // Parse scheduling period
                    val schedulingPeriod = try {
                        request.schedulingPeriod.toModel()
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid scheduling period format: ${e.message}")
                        )
                        return@put
                    }

                    // Validate scheduling period has at least one day
                    if (schedulingPeriod.daysToSchedule.isEmpty()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "At least one day must be specified in scheduling period")
                        )
                        return@put
                    }

                    // Save or update the scheduling request
                    val saved = schedulingRequestRepository.save(
                        name = request.name,
                        description = request.description,
                        laborCostBudget = request.laborCostBudget,
                        salesForecast = salesForecast,
                        schedulingPeriod = schedulingPeriod,
                        employeeIds = employeeIds,
                        updatedBy = request.createdBy ?: "anonymous"
                    )

                    call.respond(HttpStatusCode.OK, saved.toResponse())
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.message}")
                    )
                }
            }

            // Update specific fields of the latest scheduling request
            put("/update") {
                try {
                    val request = call.receive<UpdateSchedulingRequestDto>()

                    // Check if scheduling request exists
                    val existing = schedulingRequestRepository.getLatest()
                    if (existing == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "No scheduling request found. Use PUT /api/scheduling-request to create one.")
                        )
                        return@put
                    }

                    // Validate labor cost budget if provided
                    if (request.laborCostBudget != null && request.laborCostBudget <= 0) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "laborCostBudget must be greater than 0")
                        )
                        return@put
                    }

                    // Validate name if provided
                    if (request.name != null && request.name.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "name cannot be blank")
                        )
                        return@put
                    }

                    // Parse and validate employee IDs if provided
                    val employeeIds = if (request.employeeIds != null) {
                        try {
                            val ids = request.parseEmployeeIds()!!

                            // Validate that all employee IDs exist
                            val existingEmployees = employeeRepository.findByIds(ids)
                            if (existingEmployees.size != ids.size) {
                                val missingIds = ids.filterNot { eid ->
                                    existingEmployees.any { it.id == eid }
                                }
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf(
                                        "error" to "Employee IDs not found: ${missingIds.joinToString(", ")}"
                                    )
                                )
                                return@put
                            }

                            // Validate at least one employee
                            if (ids.isEmpty()) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to "At least one employee ID must be provided")
                                )
                                return@put
                            }

                            ids
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid employee ID format: ${e.message}")
                            )
                            return@put
                        }
                    } else {
                        null
                    }

                    // Parse and validate sales forecast if provided
                    val salesForecast = if (request.salesForecast != null) {
                        try {
                            val forecast = request.parseSalesForecast()!!

                            // Validate sales forecast has positive values
                            forecast.forEach { (day, timeMap) ->
                                timeMap.forEach { (time, sales) ->
                                    if (sales < 0) {
                                        call.respond(
                                            HttpStatusCode.BadRequest,
                                            mapOf("error" to "Sales forecast values must be non-negative")
                                        )
                                        return@put
                                    }
                                }
                            }

                            forecast
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid sales forecast format: ${e.message}")
                            )
                            return@put
                        }
                    } else {
                        null
                    }

                    // Parse and validate scheduling period if provided
                    val schedulingPeriod = if (request.schedulingPeriod != null) {
                        try {
                            val period = request.schedulingPeriod.toModel()

                            // Validate at least one day
                            if (period.daysToSchedule.isEmpty()) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to "At least one day must be specified in scheduling period")
                                )
                                return@put
                            }

                            period
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid scheduling period format: ${e.message}")
                            )
                            return@put
                        }
                    } else {
                        null
                    }

                    // Update the scheduling request
                    val updated = schedulingRequestRepository.update(
                        name = request.name,
                        description = request.description,
                        laborCostBudget = request.laborCostBudget,
                        salesForecast = salesForecast,
                        schedulingPeriod = schedulingPeriod,
                        employeeIds = employeeIds,
                        updatedBy = request.updatedBy ?: "anonymous"
                    )

                    if (updated != null) {
                        call.respond(HttpStatusCode.OK, updated.toResponse())
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to update scheduling request")
                        )
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.message}")
                    )
                }
            }

            // Delete the latest scheduling request
            delete {
                try {
                    val deleted = schedulingRequestRepository.delete()
                    if (deleted) {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "Scheduling request deleted successfully"))
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "No scheduling request found")
                        )
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to delete scheduling request: ${e.message}")
                    )
                }
            }
        }
    }
}
