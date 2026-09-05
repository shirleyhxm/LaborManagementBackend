package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.labormanagement.dto.SpecialEventRequest
import org.labormanagement.dto.toModel
import org.labormanagement.dto.toResponse
import org.labormanagement.model.SpecialEvent
import org.labormanagement.repository.EmployeeGroupRepository
import org.labormanagement.repository.SpecialEventRepository
import org.labormanagement.service.TenantContextHolder
import java.time.LocalDate
import java.util.UUID

/**
 * CRUD for special event definitions.
 *
 * Generation is deliberately not here: an event produces an ordinary schedule, so it goes
 * through the scheduling endpoints rather than growing a parallel path.
 */
class SpecialEventController(
    private val specialEventRepository: SpecialEventRepository,
    private val employeeGroupRepository: EmployeeGroupRepository
) {
    fun Route.specialEventRoutes() {
        route("/api/businesses/{businessId}/events") {

            get {
                val businessId = call.resolveBusinessId() ?: return@get
                val startDate = call.request.queryParameters["startDate"]
                val endDate = call.request.queryParameters["endDate"]

                try {
                    val events = if (startDate != null && endDate != null) {
                        specialEventRepository.findByBusinessAndDateRange(
                            businessId, LocalDate.parse(startDate), LocalDate.parse(endDate)
                        )
                    } else {
                        specialEventRepository.findByBusiness(businessId)
                    }
                    call.respond(HttpStatusCode.OK, mapOf(
                        "events" to events.map { it.toResponse() },
                        "total" to events.size
                    ))
                } catch (e: java.time.format.DateTimeParseException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "error" to "Invalid date range, expected YYYY-MM-DD"
                    ))
                } catch (e: Exception) {
                    call.application.log.error("Error listing events for business $businessId", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "error" to "Failed to list events: ${e.message}"
                    ))
                }
            }

            get("/{id}") {
                val businessId = call.resolveBusinessId() ?: return@get
                val id = call.resolveEventId() ?: return@get

                val event = specialEventRepository.findById(businessId, id)
                if (event == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Event not found"))
                } else {
                    call.respond(HttpStatusCode.OK, event.toResponse())
                }
            }

            post {
                val businessId = call.resolveBusinessId() ?: return@post

                try {
                    val request = call.receive<SpecialEventRequest>()
                    val event = request.toModel(
                        businessId = businessId,
                        createdBy = TenantContextHolder.getContext()?.userId ?: "system"
                    )

                    val canonical = canonicaliseGroups(businessId, event) ?: run {
                        call.respondUnknownGroups(businessId, event)
                        return@post
                    }

                    call.respond(HttpStatusCode.Created, specialEventRepository.create(canonical).toResponse())
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                } catch (e: Exception) {
                    call.application.log.error("Error creating event for business $businessId", e)
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "error" to (e.message ?: "Invalid request")
                    ))
                }
            }

            put("/{id}") {
                val businessId = call.resolveBusinessId() ?: return@put
                val id = call.resolveEventId() ?: return@put

                try {
                    val existing = specialEventRepository.findById(businessId, id)
                    if (existing == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Event not found"))
                        return@put
                    }

                    val request = call.receive<SpecialEventRequest>()
                    // Creation metadata and the generated-schedule link belong to the event's
                    // history, not to the edit - carrying them over stops an update from
                    // rewriting who created it or orphaning the schedule it produced.
                    val event = request.toModel(
                        businessId = businessId,
                        id = id,
                        createdBy = existing.createdBy,
                        createdAt = existing.createdAt,
                        scheduleId = existing.scheduleId
                    )

                    val canonical = canonicaliseGroups(businessId, event) ?: run {
                        call.respondUnknownGroups(businessId, event)
                        return@put
                    }

                    val updated = specialEventRepository.update(businessId, id, canonical)
                    if (updated == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Event not found"))
                    } else {
                        call.respond(HttpStatusCode.OK, updated.toResponse())
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                } catch (e: Exception) {
                    call.application.log.error("Error updating event $id", e)
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "error" to (e.message ?: "Invalid request")
                    ))
                }
            }

            delete("/{id}") {
                val businessId = call.resolveBusinessId() ?: return@delete
                val id = call.resolveEventId() ?: return@delete

                if (specialEventRepository.delete(businessId, id)) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Event not found"))
                }
            }
        }
    }

    /**
     * Resolve each requirement's group against the business's own groups, returning the
     * event with the stored display names substituted in.
     *
     * Null means at least one name matched nothing. This check is what makes an event's
     * staffing requirements meaningful: a name that matches no group selects no employees,
     * so the event would generate understaffed with nothing to explain why - the manager
     * would see an event missing its bartenders and no indication that "Bartenders" and
     * "Bartender" are different strings.
     *
     * Matching is case-insensitive and the canonical name is stored, so a requirement typed
     * as "bartender" stays joined to the "Bartender" group even after the group is renamed
     * in a different case.
     */
    private fun canonicaliseGroups(businessId: UUID, event: SpecialEvent): SpecialEvent? {
        if (event.requirements.isEmpty()) return event

        val resolved = event.requirements.map { requirement ->
            val canonicalName = employeeGroupRepository
                .getCanonicalNameForBusiness(businessId, requirement.groupName)
                ?: return null
            requirement.copy(groupName = canonicalName)
        }
        return event.copy(requirements = resolved)
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondUnknownGroups(
        businessId: UUID,
        event: SpecialEvent
    ) {
        val known = employeeGroupRepository.findAllForBusiness(businessId).map { it.name }
        val unknown = event.requirements
            .map { it.groupName }
            .filter { employeeGroupRepository.getCanonicalNameForBusiness(businessId, it) == null }

        respond(HttpStatusCode.BadRequest, mapOf(
            "error" to "Unknown employee group(s): ${unknown.joinToString()}",
            "unknownGroups" to unknown,
            "knownGroups" to known
        ))
    }

    private suspend fun io.ktor.server.application.ApplicationCall.resolveBusinessId(): UUID? {
        val businessId = parameters["businessId"]?.let {
            try { UUID.fromString(it) } catch (e: Exception) { null }
        }
        if (businessId == null) {
            respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
            return null
        }

        val contextBusinessId = TenantContextHolder.getContext()?.businessId
        if (contextBusinessId != null && contextBusinessId != businessId) {
            respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access events from a different business"))
            return null
        }
        return businessId
    }

    private suspend fun io.ktor.server.application.ApplicationCall.resolveEventId(): UUID? {
        val id = parameters["id"]?.let {
            try { UUID.fromString(it) } catch (e: Exception) { null }
        }
        if (id == null) {
            respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid event ID"))
            return null
        }
        return id
    }
}
