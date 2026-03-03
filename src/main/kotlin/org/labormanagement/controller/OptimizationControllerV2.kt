package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.labormanagement.dto.OptimizationRequestV2
import org.labormanagement.service.OptimizationJobService

/**
 * Simplified V2 API controller for optimization-focused workflow.
 * Provides async job-based optimization with minimal complexity.
 * Uses business-scoped routes for multi-tenant support.
 */
class OptimizationControllerV2(
    private val optimizationJobService: OptimizationJobService
) {

    fun Route.optimizationRoutesV2() {
        route("/api/businesses/{businessId}/v2") {

            /**
             * POST /api/businesses/{businessId}/v2/optimize
             * Submit a new optimization job for a specific business.
             * Returns immediately with jobId for status tracking.
             */
            post("/optimize") {
                try {
                    val businessIdStr = call.parameters["businessId"]
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Missing businessId parameter")
                            )
                            return@post
                        }

                    val businessId = try {
                        java.util.UUID.fromString(businessIdStr)
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid businessId format: $businessIdStr")
                        )
                        return@post
                    }

                    val request = call.receive<OptimizationRequestV2>()

                    call.application.log.info("[OptimizationV2] Received optimization request for business $businessId")

                    // Validate request
                    val validation = validateOptimizationRequest(request)
                    if (!validation.isValid) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to "Invalid optimization request",
                                "details" to validation.errors
                            )
                        )
                        return@post
                    }

                    // Submit job with businessId from path
                    val response = optimizationJobService.submitJob(businessId, request)

                    call.respond(HttpStatusCode.Accepted, response)

                } catch (e: Exception) {
                    call.application.log.error("[OptimizationV2] Failed to submit optimization job", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to submit optimization job: ${e.message}")
                    )
                }
            }

            /**
             * GET /api/businesses/{businessId}/v2/optimize/{jobId}
             * Get optimization job status and results for a specific business.
             */
            get("/optimize/{jobId}") {
                try {
                    val jobId = call.parameters["jobId"]
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Missing jobId parameter")
                            )
                            return@get
                        }

                    val jobStatus = optimizationJobService.getJobStatus(jobId)

                    if (jobStatus == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Job not found: $jobId")
                        )
                        return@get
                    }

                    call.respond(HttpStatusCode.OK, jobStatus)

                } catch (e: Exception) {
                    call.application.log.error("[OptimizationV2] Failed to get job status", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get job status: ${e.message}")
                    )
                }
            }

            /**
             * GET /api/businesses/{businessId}/v2/health
             * Health check endpoint for v2 API.
             */
            get("/health") {
                val businessIdStr = call.parameters["businessId"] ?: "unknown"
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "status" to "healthy",
                        "version" to "2.0",
                        "service" to "OptimalAssign",
                        "businessId" to businessIdStr,
                        "employeeManagement" to "Use /api/businesses/{businessId}/employees endpoints for employee CRUD and CSV import"
                    )
                )
            }
        }
    }

    /**
     * Validate optimization request.
     */
    private fun validateOptimizationRequest(request: OptimizationRequestV2): ValidationResult {
        val errors = mutableListOf<String>()

        // Validate demand matrix
        if (request.demandMatrix.slots.isEmpty()) {
            errors.add("Demand matrix must contain at least one slot")
        }

        // Validate employee IDs if provided
        request.employeeIds?.forEach { employeeId ->
            try {
                java.util.UUID.fromString(employeeId)
            } catch (e: Exception) {
                errors.add("Invalid employee ID format: $employeeId")
            }
        }

        // Validate date range
        try {
            val startDate = java.time.LocalDate.parse(request.demandMatrix.startDate)
            val endDate = java.time.LocalDate.parse(request.demandMatrix.endDate)
            if (endDate.isBefore(startDate)) {
                errors.add("End date must be after start date")
            }
        } catch (e: Exception) {
            errors.add("Invalid date format. Use YYYY-MM-DD format.")
        }

        // Validate objective
        val validObjectives = listOf(
            "MINIMIZE_COST", "MINIMIZE_LABOR_COST",
            "MAXIMIZE_SALES", "MAXIMIZE_COVERAGE",
            "BALANCE_WORKLOAD", "MAXIMIZE_FAIRNESS",
            "BALANCED"
        )
        if (request.objective.primary.uppercase() !in validObjectives) {
            errors.add("Invalid objective: ${request.objective.primary}. Valid values: ${validObjectives.joinToString()}")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>
    )
}
