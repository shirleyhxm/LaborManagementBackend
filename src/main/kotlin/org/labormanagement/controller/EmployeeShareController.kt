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
import org.labormanagement.dto.EmployeeShareResponse
import org.labormanagement.dto.EmployeeSharesListResponse
import org.labormanagement.dto.ShareEmployeeRequest
import org.labormanagement.model.EmployeeShare
import org.labormanagement.repository.BusinessRepository
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.EmployeeShareRepository
import org.labormanagement.service.BusinessService
import java.time.Instant
import java.util.UUID

/**
 * Lending employees between businesses of one account.
 *
 * Owner-only throughout, and both businesses must have the same owner - the
 * point is moving staff between locations of a chain, not across account
 * boundaries. Only the employee's *home* business can lend them on, so a
 * borrowing business cannot re-share staff it does not own.
 */
class EmployeeShareController(
    private val shareRepository: EmployeeShareRepository,
    private val employeeRepository: EmployeeRepository,
    private val businessRepository: BusinessRepository,
    private val businessService: BusinessService
) {

    fun Route.employeeShareRoutes() {
        route("/api/businesses/{businessId}/employees/{employeeId}/shares") {
            authenticate("auth-jwt") {

                get {
                    val ctx = call.requireOwnerOfHomeBusiness() ?: return@get

                    val shares = shareRepository.findBusinessIdsForEmployee(ctx.employeeId)
                        .mapNotNull { bid ->
                            val business = businessRepository.findById(bid) ?: return@mapNotNull null
                            EmployeeShareResponse(
                                employeeId = ctx.employeeId.toString(),
                                businessId = bid.toString(),
                                businessName = business.name,
                                sharedAt = shareRepository.find(ctx.employeeId, bid)
                                    ?.sharedAt?.toString() ?: ""
                            )
                        }

                    call.respond(
                        HttpStatusCode.OK,
                        EmployeeSharesListResponse(
                            employeeId = ctx.employeeId.toString(),
                            homeBusinessId = ctx.businessId.toString(),
                            sharedWith = shares
                        )
                    )
                }

                post {
                    val ctx = call.requireOwnerOfHomeBusiness() ?: return@post

                    val request = try {
                        call.receive<ShareEmployeeRequest>()
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                        return@post
                    }

                    val targetId = try {
                        UUID.fromString(request.businessId)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid target business ID"))
                        return@post
                    }

                    if (targetId == ctx.businessId) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "This employee already belongs to that business")
                        )
                        return@post
                    }

                    val target = businessRepository.findById(targetId)
                    if (target == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Target business not found"))
                        return@post
                    }

                    // The invariant that keeps sharing inside one account:
                    // both businesses must have the same owner. Checked here
                    // rather than trusted from the caller, so sharing can never
                    // become a route between two accounts.
                    if (!businessService.isOwner(ctx.userId, targetId)) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "You can only share employees between businesses you own")
                        )
                        return@post
                    }

                    val saved = shareRepository.share(
                        EmployeeShare(
                            employeeId = ctx.employeeId,
                            businessId = targetId,
                            sharedBy = ctx.userId,
                            sharedAt = Instant.now()
                        )
                    )

                    call.application.log.info(
                        "[EmployeeShareController] ${ctx.userId} shared employee ${ctx.employeeId} " +
                            "from business ${ctx.businessId} into $targetId"
                    )

                    call.respond(
                        HttpStatusCode.Created,
                        EmployeeShareResponse(
                            employeeId = ctx.employeeId.toString(),
                            businessId = targetId.toString(),
                            businessName = target.name,
                            sharedAt = saved.sharedAt.toString()
                        )
                    )
                }

                delete("/{targetBusinessId}") {
                    val ctx = call.requireOwnerOfHomeBusiness() ?: return@delete

                    val targetId = call.parameters["targetBusinessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (targetId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid target business ID"))
                        return@delete
                    }

                    if (!shareRepository.unshare(ctx.employeeId, targetId)) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "This employee is not shared with that business")
                        )
                        return@delete
                    }

                    call.application.log.info(
                        "[EmployeeShareController] ${ctx.userId} unshared employee ${ctx.employeeId} " +
                            "from business $targetId"
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }

    private data class ShareContext(
        val userId: String,
        val businessId: UUID,
        val employeeId: UUID
    )

    /**
     * Require that the caller owns the business in the path *and* that the
     * employee actually belongs to it.
     *
     * Deliberately uses findOwnedById rather than findById: a business that has
     * merely borrowed someone must not be able to lend them on to a third
     * business, or to revoke a share it did not create.
     */
    private suspend fun io.ktor.server.application.ApplicationCall.requireOwnerOfHomeBusiness(): ShareContext? {
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
            respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business or employee ID"))
            return null
        }

        if (!businessService.isOwner(userId, businessId)) {
            respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "Only the business owner can share employees")
            )
            return null
        }

        if (employeeRepository.findOwnedById(businessId, employeeId) == null) {
            respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "This employee does not belong to this business")
            )
            return null
        }

        return ShareContext(userId, businessId, employeeId)
    }
}
