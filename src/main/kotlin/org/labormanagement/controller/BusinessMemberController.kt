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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.labormanagement.dto.AddBusinessMemberRequest
import org.labormanagement.dto.BusinessMemberResponse
import org.labormanagement.dto.BusinessMembersListResponse
import org.labormanagement.dto.UpdateBusinessMemberRequest
import org.labormanagement.model.BusinessMembership
import org.labormanagement.model.MembershipStatus
import org.labormanagement.model.UserRole
import org.labormanagement.repository.BusinessMembershipRepository
import org.labormanagement.repository.BusinessRepository
import org.labormanagement.repository.UserRepository
import org.labormanagement.service.BusinessService
import java.time.Instant
import java.util.UUID

/**
 * Manager access management for a business.
 *
 * Only the account owner administers this list. ADMIN is not assignable here -
 * it derives from owning the business, so the only grant this controller
 * issues is MANAGER.
 */
class BusinessMemberController(
    private val membershipRepository: BusinessMembershipRepository,
    private val businessRepository: BusinessRepository,
    private val userRepository: UserRepository,
    private val businessService: BusinessService
) {

    fun Route.businessMemberRoutes() {
        route("/api/businesses/{businessId}/members") {
            authenticate("auth-jwt") {

                get {
                    val ctx = call.requireOwner() ?: return@get

                    val members = mutableListOf<BusinessMemberResponse>()

                    // The owner always heads the list and is never editable
                    // here - their access comes from owning the account.
                    val business = businessRepository.findById(ctx.businessId)
                    if (business != null) {
                        userRepository.findById(business.ownerId)?.let { owner ->
                            members += BusinessMemberResponse(
                                userId = owner.id,
                                email = owner.email,
                                firstName = owner.firstName,
                                lastName = owner.lastName,
                                role = UserRole.ADMIN.name,
                                status = MembershipStatus.ACTIVE.name,
                                isOwner = true
                            )
                        }
                    }

                    membershipRepository.findByBusiness(ctx.businessId).forEach { m ->
                        val user = userRepository.findById(m.userId) ?: return@forEach
                        members += BusinessMemberResponse(
                            userId = user.id,
                            email = user.email,
                            firstName = user.firstName,
                            lastName = user.lastName,
                            role = m.role.name,
                            status = m.status.name,
                            isOwner = false
                        )
                    }

                    call.respond(HttpStatusCode.OK, BusinessMembersListResponse(members))
                }

                post {
                    val ctx = call.requireOwner() ?: return@post

                    val request = try {
                        call.receive<AddBusinessMemberRequest>()
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                        return@post
                    }

                    if (request.email.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Email is required"))
                        return@post
                    }

                    val role = parseAssignableRole(request.role) ?: run {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Only MANAGER can be assigned. ADMIN follows business ownership.")
                        )
                        return@post
                    }

                    val target = userRepository.findByEmail(request.email)
                    if (target == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "No user account found for ${request.email}")
                        )
                        return@post
                    }

                    // The owner already has admin over this business; a manager
                    // grant would be strictly narrower and never consulted.
                    if (businessRepository.isOwner(target.id, ctx.businessId)) {
                        call.respond(
                            HttpStatusCode.Conflict,
                            mapOf("error" to "This user owns the business and already has full access")
                        )
                        return@post
                    }

                    val saved = membershipRepository.upsert(
                        BusinessMembership(
                            businessId = ctx.businessId,
                            userId = target.id,
                            role = role,
                            invitedBy = ctx.userId,
                            invitedAt = Instant.now(),
                            status = MembershipStatus.ACTIVE
                        )
                    )

                    call.application.log.info(
                        "[BusinessMemberController] ${ctx.userId} granted ${saved.role} " +
                            "to ${target.id} in business ${ctx.businessId}"
                    )

                    call.respond(
                        HttpStatusCode.Created,
                        BusinessMemberResponse(
                            userId = target.id,
                            email = target.email,
                            firstName = target.firstName,
                            lastName = target.lastName,
                            role = saved.role.name,
                            status = saved.status.name,
                            isOwner = false
                        )
                    )
                }

                put("/{userId}") {
                    val ctx = call.requireOwner() ?: return@put

                    val targetUserId = call.parameters["userId"]
                    if (targetUserId.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
                        return@put
                    }

                    val request = try {
                        call.receive<UpdateBusinessMemberRequest>()
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                        return@put
                    }

                    val role = parseAssignableRole(request.role) ?: run {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Only MANAGER can be assigned. ADMIN follows business ownership.")
                        )
                        return@put
                    }

                    val existing = membershipRepository.findByBusinessAndUser(ctx.businessId, targetUserId)
                    if (existing == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "This user has no access to change"))
                        return@put
                    }

                    val saved = membershipRepository.upsert(existing.copy(role = role))
                    val user = userRepository.findById(targetUserId)

                    call.respond(
                        HttpStatusCode.OK,
                        BusinessMemberResponse(
                            userId = targetUserId,
                            email = user?.email ?: "",
                            firstName = user?.firstName ?: "",
                            lastName = user?.lastName ?: "",
                            role = saved.role.name,
                            status = saved.status.name,
                            isOwner = false
                        )
                    )
                }

                delete("/{userId}") {
                    val ctx = call.requireOwner() ?: return@delete

                    val targetUserId = call.parameters["userId"]
                    if (targetUserId.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
                        return@delete
                    }

                    // Guards against an owner locking themselves out of their
                    // own business by removing their own access.
                    if (targetUserId == ctx.userId) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "You cannot remove your own access to this business")
                        )
                        return@delete
                    }

                    val removed = membershipRepository.delete(ctx.businessId, targetUserId)
                    if (!removed) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "This user has no access to remove"))
                        return@delete
                    }

                    call.application.log.info(
                        "[BusinessMemberController] ${ctx.userId} revoked access for " +
                            "$targetUserId in business ${ctx.businessId}"
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }

    private data class OwnerContext(val userId: String, val businessId: UUID)

    /**
     * Validate the caller owns the business in the path. Managers deliberately
     * cannot reach these routes - widening access to a business is the account
     * owner's decision.
     *
     * Responds and returns null when the check fails, so callers can
     * `?: return@get`.
     */
    private suspend fun io.ktor.server.application.ApplicationCall.requireOwner(): OwnerContext? {
        val principal = principal<JWTPrincipal>()
        if (principal == null) {
            respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            return null
        }
        val userId = principal.payload.getClaim("userId").asString()

        val businessId = parameters["businessId"]?.let {
            try { UUID.fromString(it) } catch (e: Exception) { null }
        }
        if (businessId == null) {
            respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
            return null
        }

        if (!businessService.isOwner(userId, businessId)) {
            respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "Only the business owner can manage access")
            )
            return null
        }

        return OwnerContext(userId, businessId)
    }

    /**
     * MANAGER is the only role this endpoint hands out. ADMIN is derived from
     * ownership and EMPLOYEE comes from a linked employee record, so neither is
     * meaningful as a membership grant.
     */
    private fun parseAssignableRole(raw: String): UserRole? {
        val role = try {
            UserRole.valueOf(raw.uppercase())
        } catch (e: IllegalArgumentException) {
            return null
        }
        return if (role == UserRole.MANAGER) role else null
    }
}
