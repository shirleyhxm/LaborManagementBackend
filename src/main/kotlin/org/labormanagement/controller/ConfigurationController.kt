package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.post
import org.labormanagement.dto.ConfigurationResponse
import org.labormanagement.dto.UpdateConfigurationRequest
import org.labormanagement.dto.parseOptimizationObjective
import org.labormanagement.dto.toResponse
import org.labormanagement.repository.SchedulingConfigurationRepository

/**
 * Controller for managing scheduling configuration settings.
 * Provides REST APIs for viewing and updating configuration.
 */
class ConfigurationController(
    private val configRepository: SchedulingConfigurationRepository
) {

    fun Route.configurationRoutes() {
        route("/api/configuration") {

            // Get current configuration
            get {
                try {
                    val config = configRepository.getDefault()
                    call.respond(HttpStatusCode.OK, config.toResponse())
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to retrieve configuration: ${e.message}")
                    )
                }
            }

            // Get configuration by ID (for future multi-tenant support)
            get("/{configId}") {
                try {
                    val configId = call.parameters["configId"] ?: "default"
                    val config = configRepository.get(configId)
                    call.respond(HttpStatusCode.OK, config.toResponse())
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to retrieve configuration: ${e.message}")
                    )
                }
            }

            // Update configuration
            put {
                try {
                    val request = call.receive<UpdateConfigurationRequest>()

                    // Validate minShiftDurationHours if provided
                    if (request.minShiftDurationHours != null && request.minShiftDurationHours <= 0) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "minShiftDurationHours must be greater than 0")
                        )
                        return@put
                    }

                    // Validate and parse optimization objective if provided
                    val objective = request.parseOptimizationObjective()
                    if (request.defaultOptimizationObjective != null && objective == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to "Invalid optimization objective. Valid values: MAXIMIZE_SALES, MINIMIZE_LABOR_COST, BALANCED, MAXIMIZE_FAIRNESS"
                            )
                        )
                        return@put
                    }

                    // Update configuration
                    val updatedConfig = configRepository.update(
                        minShiftDurationHours = request.minShiftDurationHours,
                        defaultOptimizationObjective = objective,
                        updatedBy = request.updatedBy ?: "anonymous"
                    )

                    call.respond(HttpStatusCode.OK, updatedConfig.toResponse())
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.message}")
                    )
                }
            }

            // Update configuration by ID (for future multi-tenant support)
            put("/{configId}") {
                try {
                    val configId = call.parameters["configId"] ?: "default"
                    val request = call.receive<UpdateConfigurationRequest>()

                    // Validate minShiftDurationHours if provided
                    if (request.minShiftDurationHours != null && request.minShiftDurationHours <= 0) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "minShiftDurationHours must be greater than 0")
                        )
                        return@put
                    }

                    // Validate and parse optimization objective if provided
                    val objective = request.parseOptimizationObjective()
                    if (request.defaultOptimizationObjective != null && objective == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to "Invalid optimization objective. Valid values: MAXIMIZE_SALES, MINIMIZE_LABOR_COST, BALANCED, MAXIMIZE_FAIRNESS"
                            )
                        )
                        return@put
                    }

                    // Update configuration
                    val updatedConfig = configRepository.update(
                        configId = configId,
                        minShiftDurationHours = request.minShiftDurationHours,
                        defaultOptimizationObjective = objective,
                        updatedBy = request.updatedBy ?: "anonymous"
                    )

                    call.respond(HttpStatusCode.OK, updatedConfig.toResponse())
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.message}")
                    )
                }
            }

            // Reset configuration to defaults
            post("/reset") {
                try {
                    // Try to parse request body for updatedBy field, but make it optional
                    val updatedBy = try {
                        val request = call.receive<UpdateConfigurationRequest>()
                        request.updatedBy
                    } catch (e: Exception) {
                        null
                    }

                    val resetConfig = configRepository.reset(resetBy = updatedBy ?: "anonymous")
                    call.respond(HttpStatusCode.OK, resetConfig.toResponse())
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to reset configuration: ${e.message}")
                    )
                }
            }

            // Get all configurations (admin endpoint for future multi-tenant support)
            get("/all") {
                try {
                    val configs = configRepository.getAll().map { it.toResponse() }
                    call.respond(HttpStatusCode.OK, configs)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to retrieve configurations: ${e.message}")
                    )
                }
            }
        }
    }
}