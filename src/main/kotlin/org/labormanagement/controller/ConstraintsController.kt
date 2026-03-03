package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import org.labormanagement.dto.*
import org.labormanagement.service.ConstraintsService
import org.labormanagement.service.TenantContextHolder
import java.util.UUID

class ConstraintsController(
    private val constraintsService: ConstraintsService
) {

    fun Route.constraintsRoutes() {
        route("/api/businesses/{businessId}/constraints") {

            // ====== Budget Constraints ======

            route("/budget") {
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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@get
                        }

                        val budget = constraintsService.getBudgetConstraints(businessId)
                        if (budget != null) {
                            call.respond(HttpStatusCode.OK, budget.toResponse())
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Budget constraints not found"))
                        }
                    } catch (e: Exception) {
                        call.application.log.error("Failed to get budget constraints", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }

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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@put
                        }

                        val request = call.receive<BudgetConstraintsRequest>()
                        val budget = constraintsService.updateBudgetConstraints(businessId, request)
                        call.respond(HttpStatusCode.OK, budget.toResponse())
                    } catch (e: Exception) {
                        call.application.log.error("Failed to update budget constraints", e)
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    }
                }
            }

            // ====== Hourly Rate Rules ======

            route("/hourly-rates") {
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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@get
                        }

                        val roleId = call.request.queryParameters["roleId"]
                        val rates = constraintsService.getHourlyRateRules(businessId, roleId)
                        call.respond(HttpStatusCode.OK, rates.map { it.toResponse() })
                    } catch (e: Exception) {
                        call.application.log.error("Failed to get hourly rate rules", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }

                post {
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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@post
                        }

                        val request = call.receive<HourlyRateRuleRequest>()
                        val rate = constraintsService.createHourlyRateRule(businessId, request)
                        call.respond(HttpStatusCode.Created, rate.toResponse())
                    } catch (e: Exception) {
                        call.application.log.error("Failed to create hourly rate rule", e)
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    }
                }

                delete {
                    try {
                        // Extract and validate businessId from path
                        val businessId = call.parameters["businessId"]?.let {
                            try { UUID.fromString(it) } catch (e: Exception) { null }
                        }
                        if (businessId == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                            return@delete
                        }

                        // Validate businessId matches tenant context
                        val contextBusinessId = TenantContextHolder.getContext()?.businessId
                        if (contextBusinessId != null && contextBusinessId != businessId) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@delete
                        }

                        val roleId = call.request.queryParameters["roleId"]
                        val deleted = constraintsService.deleteHourlyRateRule(businessId, roleId)
                        if (deleted) {
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Hourly rate rule not found"))
                        }
                    } catch (e: Exception) {
                        call.application.log.error("Failed to delete hourly rate rule", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }
            }

            // ====== Working Hours Rules ======

            route("/working-hours") {
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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@get
                        }

                        val rules = constraintsService.getWorkingHoursRules(businessId)
                        if (rules != null) {
                            call.respond(HttpStatusCode.OK, rules.toResponse())
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Working hours rules not found"))
                        }
                    } catch (e: Exception) {
                        call.application.log.error("Failed to get working hours rules", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }

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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@put
                        }

                        val request = call.receive<WorkingHoursRulesRequest>()
                        val rules = constraintsService.updateWorkingHoursRules(businessId, request)
                        call.respond(HttpStatusCode.OK, rules.toResponse())
                    } catch (e: Exception) {
                        call.application.log.error("Failed to update working hours rules", e)
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    }
                }
            }

            // ====== Employee Contracted Hours ======

            route("/contracted-hours") {
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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@get
                        }

                        val employeeIdParam = call.request.queryParameters["employeeId"]
                        val employeeId = employeeIdParam?.let { UUID.fromString(it) }

                        val hours = constraintsService.getContractedHours(businessId, employeeId)
                        call.respond(HttpStatusCode.OK, hours.map { it.toResponse() })
                    } catch (e: Exception) {
                        call.application.log.error("Failed to get contracted hours", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }

                post {
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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@post
                        }

                        val request = call.receive<EmployeeContractedHoursRequest>()
                        val hours = constraintsService.createContractedHours(businessId, request)
                        call.respond(HttpStatusCode.Created, hours.toResponse())
                    } catch (e: Exception) {
                        call.application.log.error("Failed to create contracted hours", e)
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    }
                }

                put("/{employeeId}") {
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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@put
                        }

                        val employeeId = call.parameters["employeeId"]?.let { UUID.fromString(it) }
                            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid employee ID"))

                        val request = call.receive<EmployeeContractedHoursRequest>()

                        if (constraintsService.updateContractedHours(businessId, employeeId, request)) {
                            val updated = constraintsService.getContractedHours(businessId, employeeId).first()
                            call.respond(HttpStatusCode.OK, updated.toResponse())
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Contracted hours not found"))
                        }
                    } catch (e: Exception) {
                        call.application.log.error("Failed to update contracted hours", e)
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    }
                }

                delete("/{employeeId}") {
                    try {
                        // Extract and validate businessId from path
                        val businessId = call.parameters["businessId"]?.let {
                            try { UUID.fromString(it) } catch (e: Exception) { null }
                        }
                        if (businessId == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                            return@delete
                        }

                        // Validate businessId matches tenant context
                        val contextBusinessId = TenantContextHolder.getContext()?.businessId
                        if (contextBusinessId != null && contextBusinessId != businessId) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@delete
                        }

                        val employeeId = call.parameters["employeeId"]?.let { UUID.fromString(it) }
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid employee ID"))

                        if (constraintsService.deleteContractedHours(businessId, employeeId)) {
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Contracted hours not found"))
                        }
                    } catch (e: Exception) {
                        call.application.log.error("Failed to delete contracted hours", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }
            }

            // ====== Compliance Rules ======

            route("/compliance") {
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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@get
                        }

                        val rules = constraintsService.getComplianceRules(businessId)
                        if (rules != null) {
                            call.respond(HttpStatusCode.OK, rules.toResponse())
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Compliance rules not found"))
                        }
                    } catch (e: Exception) {
                        call.application.log.error("Failed to get compliance rules", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }

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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@put
                        }

                        val request = call.receive<ComplianceRulesRequest>()
                        val rules = constraintsService.updateComplianceRules(businessId, request)
                        call.respond(HttpStatusCode.OK, rules.toResponse())
                    } catch (e: Exception) {
                        call.application.log.error("Failed to update compliance rules", e)
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    }
                }
            }

            // ====== Custom Compliance Rules ======

            route("/custom-compliance") {
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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@get
                        }

                        val rules = constraintsService.getCustomComplianceRules(businessId)
                        call.respond(HttpStatusCode.OK, rules.map { it.toResponse() })
                    } catch (e: Exception) {
                        call.application.log.error("Failed to get custom compliance rules", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }

                post {
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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@post
                        }

                        val request = call.receive<CustomComplianceRuleRequest>()
                        val rule = constraintsService.createCustomComplianceRule(businessId, request)
                        call.respond(HttpStatusCode.Created, rule.toResponse())
                    } catch (e: Exception) {
                        call.application.log.error("Failed to create custom compliance rule", e)
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    }
                }

                put("/{name}") {
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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@put
                        }

                        val name = call.parameters["name"]
                            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid name"))

                        val request = call.receive<CustomComplianceRuleRequest>()

                        val rule = constraintsService.updateCustomComplianceRule(businessId, name, request)
                        if (rule != null) {
                            call.respond(HttpStatusCode.OK, rule.toResponse())
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Custom compliance rule not found"))
                        }
                    } catch (e: Exception) {
                        call.application.log.error("Failed to update custom compliance rule", e)
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    }
                }

                delete("/{name}") {
                    try {
                        // Extract and validate businessId from path
                        val businessId = call.parameters["businessId"]?.let {
                            try { UUID.fromString(it) } catch (e: Exception) { null }
                        }
                        if (businessId == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                            return@delete
                        }

                        // Validate businessId matches tenant context
                        val contextBusinessId = TenantContextHolder.getContext()?.businessId
                        if (contextBusinessId != null && contextBusinessId != businessId) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@delete
                        }

                        val name = call.parameters["name"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid name"))

                        val deleted = constraintsService.deleteCustomComplianceRule(businessId, name)
                        if (deleted) {
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Custom compliance rule not found"))
                        }
                    } catch (e: Exception) {
                        call.application.log.error("Failed to delete custom compliance rule", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }
            }

            // ====== Scheduling Priorities ======

            route("/priorities") {
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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@get
                        }

                        val priorities = constraintsService.getSchedulingPriorities(businessId)
                        call.respond(HttpStatusCode.OK, priorities.map { it.toResponse() })
                    } catch (e: Exception) {
                        call.application.log.error("Failed to get scheduling priorities", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }

                put("/reorder") {
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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@put
                        }

                        val request = call.receive<PriorityReorderRequest>()
                        val priorities = constraintsService.reorderPriorities(businessId, request)
                        call.respond(HttpStatusCode.OK, priorities.map { it.toResponse() })
                    } catch (e: Exception) {
                        call.application.log.error("Failed to reorder priorities", e)
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    }
                }
            }

            // ====== Fairness Settings ======

            route("/fairness") {
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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@get
                        }

                        val settings = constraintsService.getFairnessSettings(businessId)
                        if (settings != null) {
                            call.respond(HttpStatusCode.OK, settings.toResponse())
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Fairness settings not found"))
                        }
                    } catch (e: Exception) {
                        call.application.log.error("Failed to get fairness settings", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }

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
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                            return@put
                        }

                        val request = call.receive<FairnessSettingsRequest>()
                        val settings = constraintsService.updateFairnessSettings(businessId, request)
                        call.respond(HttpStatusCode.OK, settings.toResponse())
                    } catch (e: Exception) {
                        call.application.log.error("Failed to update fairness settings", e)
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    }
                }
            }

            // ====== Bulk Operations ======

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
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                        return@get
                    }

                    val allConstraints = constraintsService.getAllConstraints(businessId)
                    call.respond(HttpStatusCode.OK, allConstraints)
                } catch (e: Exception) {
                    call.application.log.error("Failed to get all constraints", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // ====== Validation ======

            post("/validate") {
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
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access constraints from different business"))
                        return@post
                    }

                    val request = call.receive<ConstraintValidationRequest>()
                    val validation = constraintsService.validateConstraints(request)
                    call.respond(HttpStatusCode.OK, validation)
                } catch (e: Exception) {
                    call.application.log.error("Failed to validate constraints", e)
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }
        }
    }
}
