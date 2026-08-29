package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.labormanagement.dto.AssignEmployeeLocationRequest
import org.labormanagement.dto.EmployeeLocationResponse
import org.labormanagement.dto.EmployeeLocationsListResponse
import org.labormanagement.model.EmployeeLocation
import org.labormanagement.repository.BusinessRepository
import org.labormanagement.repository.EmployeeLocationRepository
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.service.BusinessService
import java.time.Instant
import java.util.UUID

/**
 * Assigning employees to additional locations within one account.
 *
 * Owner-only throughout, and both locations must have the same owner - the
 * point is moving staff between locations of a chain, not across account
 * boundaries. Only the employee's *home* location can assign them onward, so a
 * location that has merely borrowed someone cannot pass them along.
 */
class EmployeeLocationController(
    private val locationRepository: EmployeeLocationRepository,
    private val employeeRepository: EmployeeRepository,
    private val businessRepository: BusinessRepository,
    private val businessService: BusinessService
) {

    fun Route.employeeLocationRoutes() {
        route("/api/businesses/{businessId}/employees/{employeeId}/locations") {
            authenticate("auth-jwt") {

                get {
                    val ctx = call.requireOwnerOfHomeBusiness() ?: return@get

                    val assignments = locationRepository.findBusinessIdsForEmployee(ctx.employeeId)
                        .mapNotNull { bid ->
                            val business = businessRepository.findById(bid) ?: return@mapNotNull null
                            EmployeeLocationResponse(
                                employeeId = ctx.employeeId.toString(),
                                businessId = bid.toString(),
                                businessName = business.name,
                                assignedAt = locationRepository.find(ctx.employeeId, bid)
                                    ?.assignedAt?.toString() ?: ""
                            )
                        }

                    call.respond(
                        HttpStatusCode.OK,
                        EmployeeLocationsListResponse(
                            employeeId = ctx.employeeId.toString(),
                            homeBusinessId = ctx.businessId.toString(),
                            assignedTo = assignments
                        )
                    )
                }

                post {
                    val ctx = call.requireOwnerOfHomeBusiness() ?: return@post

                    val request = try {
                        call.receive<AssignEmployeeLocationRequest>()
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                        return@post
                    }

                    val targetId = try {
                        UUID.fromString(request.businessId)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid target location ID"))
                        return@post
                    }

                    if (targetId == ctx.businessId) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "This employee already works at that location")
                        )
                        return@post
                    }

                    val target = businessRepository.findById(targetId)
                    if (target == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Target location not found"))
                        return@post
                    }

                    // The invariant that keeps assignment inside one account:
                    // both locations must have the same owner. Checked here
                    // rather than trusted from the caller, so this can never
                    // become a route between two accounts.
                    if (!businessService.isOwner(ctx.userId, targetId)) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "You can only assign employees to locations you own")
                        )
                        return@post
                    }

                    val saved = locationRepository.assign(
                        EmployeeLocation(
                            employeeId = ctx.employeeId,
                            businessId = targetId,
                            assignedBy = ctx.userId,
                            assignedAt = Instant.now()
                        )
                    )

                    call.application.log.info(
                        "[EmployeeLocationController] ${ctx.userId} assigned employee ${ctx.employeeId} " +
                            "from location ${ctx.businessId} to $targetId"
                    )

                    call.respond(
                        HttpStatusCode.Created,
                        EmployeeLocationResponse(
                            employeeId = ctx.employeeId.toString(),
                            businessId = targetId.toString(),
                            businessName = target.name,
                            assignedAt = saved.assignedAt.toString()
                        )
                    )
                }

                delete("/{targetBusinessId}") {
                    val ctx = call.requireOwnerOfHomeBusiness() ?: return@delete

                    val targetId = call.parameters["targetBusinessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (targetId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid target location ID"))
                        return@delete
                    }

                    if (!locationRepository.unassign(ctx.employeeId, targetId)) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "This employee is not assigned to that location")
                        )
                        return@delete
                    }

                    call.application.log.info(
                        "[EmployeeLocationController] ${ctx.userId} unassigned employee ${ctx.employeeId} " +
                            "from location $targetId"
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }

    private data class LocationContext(
        val userId: String,
        val businessId: UUID,
        val employeeId: UUID
    )

    /**
     * Require that the caller owns the location in the path *and* that the
     * employee actually belongs to it.
     *
     * Deliberately uses findOwnedById rather than findById: a location that has
     * merely borrowed someone must not be able to assign them on to a third
     * location, or to revoke an assignment it did not create.
     *
     * Responds and returns null when the check fails, so callers can
     * `?: return@get`.
     */
    private suspend fun io.ktor.server.application.ApplicationCall.requireOwnerOfHomeBusiness(): LocationContext? {
        val principal = principal<JWTPrincipal>()
        if (principal == null) {
            respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            return null
        }
        val userId = principal.payload.getClaim("userId").asString()

        val businessId = parameters["businessId"]?.let {
            try { UUID.fromString(it) } catch (e: Exception) { null }
        }
        val employeeId = parameters["employeeId"]?.let {
            try { UUID.fromString(it) } catch (e: Exception) { null }
        }
        if (businessId == null || employeeId == null) {
            respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid location or employee ID"))
            return null
        }

        if (!businessService.isOwner(userId, businessId)) {
            respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "Only the business owner can assign employee locations")
            )
            return null
        }

        if (employeeRepository.findOwnedById(businessId, employeeId) == null) {
            respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "This employee does not belong to this location")
            )
            return null
        }

        return LocationContext(userId, businessId, employeeId)
    }
}
