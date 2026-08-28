package org.labormanagement.service

import org.labormanagement.dto.BusinessResponse
import org.labormanagement.dto.BusinessSummary
import org.labormanagement.dto.CreateBusinessRequest
import org.labormanagement.dto.UpdateBusinessRequest
import org.labormanagement.dto.toModel
import org.labormanagement.dto.toResponse
import org.labormanagement.dto.toSummary
import org.labormanagement.model.Business
import org.labormanagement.model.BusinessStatus
import org.labormanagement.model.MembershipStatus
import org.labormanagement.model.UserRole
import org.labormanagement.repository.BusinessMembershipRepository
import org.labormanagement.repository.BusinessRepository
import org.labormanagement.repository.UserRepository
import java.util.UUID

/**
 * Service for managing businesses with multi-tenancy support.
 * Handles business CRUD operations and access validation.
 */
class BusinessService(
    private val businessRepository: BusinessRepository,
    private val userRepository: UserRepository,
    private val membershipRepository: BusinessMembershipRepository = BusinessMembershipRepository()
) {

    /**
     * Create a new business for a user.
     * Automatically adds the business ID to the user's ownedBusinessIds.
     */
    fun createBusiness(userId: String, request: CreateBusinessRequest): BusinessResponse {
        // Verify user exists
        val user = userRepository.findById(userId)
            ?: throw NotFoundException("User not found: $userId")

        // Create the business
        val business = request.toModel(ownerId = userId)
        val createdBusiness = businessRepository.create(business)

        // Update user's ownedBusinessIds
        val updatedUser = user.copy(
            ownedBusinessIds = user.ownedBusinessIds + createdBusiness.id
        )
        userRepository.update(userId, updatedUser)

        return createdBusiness.toResponse()
    }

    /**
     * Get a business by ID.
     * Validates that the requesting user has access to the business.
     */
    fun getBusiness(userId: String, businessId: UUID): BusinessResponse {
        val business = businessRepository.findById(businessId)
            ?: throw NotFoundException("Business not found: $businessId")

        // Verify user has access
        if (!businessRepository.hasAccess(userId, businessId)) {
            throw ForbiddenException("User does not have access to this business")
        }

        return business.toResponse()
    }

    /**
     * Get all businesses for a user (owned or member).
     * Returns a list of business summaries.
     */
    fun getBusinessesForUser(userId: String): List<BusinessSummary> {
        val businesses = businessRepository.findByUserId(userId)

        // Filter out cancelled businesses
        return businesses
            .filter { it.status != BusinessStatus.CANCELLED }
            .map { it.toSummary() }
    }

    /**
     * Get all businesses owned by a user.
     */
    fun getOwnedBusinesses(userId: String): List<BusinessResponse> {
        val businesses = businessRepository.findByOwnerId(userId)

        return businesses
            .filter { it.status != BusinessStatus.CANCELLED }
            .map { it.toResponse() }
    }

    /**
     * Update a business.
     * Only the owner can update the business.
     */
    fun updateBusiness(userId: String, businessId: UUID, request: UpdateBusinessRequest): BusinessResponse {
        val business = businessRepository.findById(businessId)
            ?: throw NotFoundException("Business not found: $businessId")

        // Verify ownership
        if (!businessRepository.isOwner(userId, businessId)) {
            throw ForbiddenException("Only the business owner can update the business")
        }

        // Apply updates
        val updatedBusiness = business.copy(
            name = request.name ?: business.name,
            subdomain = request.subdomain ?: business.subdomain,
            settings = request.settings?.toModel() ?: business.settings,
            billingEmail = request.billingEmail ?: business.billingEmail
        )

        businessRepository.update(businessId, updatedBusiness)
            ?: throw NotFoundException("Failed to update business")

        return updatedBusiness.toResponse()
    }

    /**
     * Delete a business (soft delete).
     * Only the owner can delete the business.
     * This sets the status to CANCELLED but doesn't remove the data.
     */
    fun deleteBusiness(userId: String, businessId: UUID) {
        val business = businessRepository.findById(businessId)
            ?: throw NotFoundException("Business not found: $businessId")

        // Verify ownership
        if (!businessRepository.isOwner(userId, businessId)) {
            throw ForbiddenException("Only the business owner can delete the business")
        }

        // Soft delete
        businessRepository.delete(businessId)

        // Remove from user's ownedBusinessIds
        val user = userRepository.findById(userId)
        if (user != null) {
            val updatedUser = user.copy(
                ownedBusinessIds = user.ownedBusinessIds.filter { it != businessId }
            )
            userRepository.update(userId, updatedUser)
        }
    }

    /**
     * Validate that a user has access to a business.
     * Throws ForbiddenException if access is denied.
     */
    fun validateAccess(userId: String, businessId: UUID) {
        if (!businessRepository.hasAccess(userId, businessId)) {
            throw ForbiddenException("User does not have access to business: $businessId")
        }

        val business = businessRepository.findById(businessId)
            ?: throw NotFoundException("Business not found: $businessId")

        if (business.status != BusinessStatus.ACTIVE) {
            throw ForbiddenException("Business is not active: ${business.status}")
        }
    }

    /**
     * Check if a user is the owner of a business.
     */
    fun isOwner(userId: String, businessId: UUID): Boolean {
        return businessRepository.isOwner(userId, businessId)
    }

    /**
     * Resolve what a user is allowed to do *in this specific business*.
     *
     * This is the authorization source of truth for business-scoped requests,
     * deliberately in preference to the `role` claim on the JWT. The claim is
     * account-level and cannot express "admin of my own chain, but only a
     * manager over there" - and because it is baked into the token, a
     * revocation would not take effect until the token expired. Resolving per
     * request costs one indexed lookup and makes revocation immediate.
     *
     * Order matters: ownership wins over a membership row, so an owner who
     * somehow also holds a manager grant is still treated as an admin.
     *
     * Returns null when the user has no access to the business at all.
     */
    fun resolveEffectiveRole(userId: String, businessId: UUID): UserRole? {
        // An admin owns the account, so they hold admin over every business
        // under it. Derived from ownership rather than stored per business -
        // see BusinessMemberships for the reasoning.
        if (businessRepository.isOwner(userId, businessId)) {
            return UserRole.ADMIN
        }

        val membership = membershipRepository.findByBusinessAndUser(businessId, userId)
        if (membership != null && membership.status == MembershipStatus.ACTIVE) {
            return membership.role
        }

        // Someone with a linked employee record in this business can reach
        // their own data (availability, their schedule) but nothing else.
        if (businessRepository.hasLinkedEmployee(userId, businessId)) {
            return UserRole.EMPLOYEE
        }

        return null
    }

    /**
     * Get a business without access validation (admin use only).
     */
    fun getBusinessAdmin(businessId: UUID): Business? {
        return businessRepository.findById(businessId)
    }
}
