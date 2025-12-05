package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import org.labormanagement.dto.CreateEmployeeRequest
import org.labormanagement.dto.UpdateEmployeeRequest
import org.labormanagement.dto.toModel
import org.labormanagement.dto.toResponse
import org.labormanagement.repository.EmployeeRepository
import java.util.*

class EmployeeController(
    private val employeeRepository: EmployeeRepository
) {

    fun Route.employeeRoutes() {
        route("/api/employees") {

            // Create employee
            post {
                try {
                    val request = call.receive<CreateEmployeeRequest>()
                    val employee = request.toModel()
                    val created = employeeRepository.create(employee)
                    if (created == null) {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "Employee already exists"))
                    } else {
                        call.respond(HttpStatusCode.Created, created.toResponse())
                    }
                } catch (e: Exception) {
                    // Log full exception details for debugging
                    call.application.log.error("Failed to create employee", e)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to (e.message ?: "Invalid request"),
                            "type" to e::class.simpleName
                        )
                    )
                }
            }

            // Get all employees (optionally filtered by group)
            get {
                val groupName = call.request.queryParameters["group"]

                val employees = if (groupName != null) {
                    employeeRepository.findByGroup(groupName)
                } else {
                    employeeRepository.findAll()
                }

                call.respond(HttpStatusCode.OK, employees.map { it.toResponse() })
            }

            // Get employee by ID
            get("/{id}") {
                val id = call.parameters["id"]?.let {
                    try { UUID.fromString(it) } catch (e: Exception) { null }
                }

                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid employee ID"))
                    return@get
                }

                val employee = employeeRepository.findById(id)
                if (employee != null) {
                    call.respond(HttpStatusCode.OK, employee.toResponse())
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Employee not found"))
                }
            }

            // Update employee
            put("/{id}") {
                val id = call.parameters["id"]?.let {
                    try { UUID.fromString(it) } catch (e: Exception) { null }
                }

                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid employee ID"))
                    return@put
                }

                try {
                    val request = call.receive<UpdateEmployeeRequest>()
                    val existing = employeeRepository.findById(id)

                    if (existing == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Employee not found"))
                        return@put
                    }

                    val updated = existing.copy(
                        firstName = request.firstName ?: existing.firstName,
                        lastName = request.lastName ?: existing.lastName,
                        middleName = request.middleName ?: existing.middleName,
                        normalPayRate = request.normalPayRate ?: existing.normalPayRate,
                        overtimePayRate = request.overtimePayRate ?: existing.overtimePayRate,
                        productivity = request.productivity ?: existing.productivity,
                        contract = request.contract?.toModel() ?: existing.contract,
                        availability = request.availability?.map { it.toModel() } ?: existing.availability,
                        groups = request.groups ?: existing.groups
                    )

                    employeeRepository.update(id, updated)
                    call.respond(HttpStatusCode.OK, updated.toResponse())
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            // Delete employee
            delete("/{id}") {
                val id = call.parameters["id"]?.let {
                    try { UUID.fromString(it) } catch (e: Exception) { null }
                }

                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid employee ID"))
                    return@delete
                }

                val deleted = employeeRepository.delete(id)
                if (deleted) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Employee not found"))
                }
            }
        }
    }
}
