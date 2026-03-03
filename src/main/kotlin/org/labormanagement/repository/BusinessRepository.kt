package org.labormanagement.repository

import org.labormanagement.model.Business
import org.labormanagement.model.BusinessStatus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for managing Business entities with multi-tenancy support.
 * Provides CRUD operations and business lookup by owner.
 */
class BusinessRepository {
    private val businesses = ConcurrentHashMap<UUID, Business>()
    private val ownerIndex = ConcurrentHashMap<String, MutableSet<UUID>>()  // ownerId -> Set<businessId>

    /**
     * Create a new business.
     * Adds the business to the owner index for quick lookup.
     */
    fun create(business: Business): Business {
        businesses[business.id] = business
        ownerIndex.computeIfAbsent(business.ownerId) { ConcurrentHashMap.newKeySet() }.add(business.id)
        return business
    }

    /**
     * Find a business by ID.
     */
    fun findById(id: UUID): Business? {
        return businesses[id]
    }

    /**
     * Find all businesses owned by a specific user.
     */
    fun findByOwnerId(ownerId: String): List<Business> {
        val businessIds = ownerIndex[ownerId] ?: return emptyList()
        return businessIds.mapNotNull { businesses[it] }
    }

    /**
     * Find all active businesses for a user (owned or member).
     * Note: For now, we only return owned businesses.
     * In the future, this could include businesses where the user is a member.
     */
    fun findByUserId(userId: String): List<Business> {
        return findByOwnerId(userId)
    }

    /**
     * Find all businesses (admin function).
     */
    fun findAll(): List<Business> {
        return businesses.values.toList()
    }

    /**
     * Update a business.
     * Security note: Caller should verify ownership before calling this method.
     */
    fun update(id: UUID, business: Business): Business? {
        return if (businesses.containsKey(id)) {
            businesses[id] = business
            business
        } else {
            null
        }
    }

    /**
     * Delete a business (soft delete by changing status).
     * Security note: Caller should verify ownership before calling this method.
     */
    fun delete(id: UUID): Boolean {
        val business = businesses[id] ?: return false

        // Soft delete by changing status
        val updatedBusiness = business.copy(status = BusinessStatus.CANCELLED)
        businesses[id] = updatedBusiness

        return true
    }

    /**
     * Hard delete a business (permanently remove from storage).
     * Should only be used for testing or admin operations.
     */
    fun hardDelete(id: UUID): Boolean {
        val business = businesses.remove(id) ?: return false
        ownerIndex[business.ownerId]?.remove(id)
        return true
    }

    /**
     * Check if a business with the given ID exists.
     */
    fun exists(id: UUID): Boolean {
        return businesses.containsKey(id)
    }

    /**
     * Check if a user owns a specific business.
     */
    fun isOwner(userId: String, businessId: UUID): Boolean {
        println("isOwner check: userId=$userId, businessId=$businessId")
        println("businesses: ${businesses.keys}")
        val business = businesses[businessId] ?: return false
        return business.ownerId == userId
    }

    /**
     * Check if a user has access to a specific business (owner or member).
     * For now, only owners have access. Future: check BusinessMembership.
     */
    fun hasAccess(userId: String, businessId: UUID): Boolean {
        return isOwner(userId, businessId)
    }

    /**
     * Clear all data (for testing).
     */
    fun clear() {
        businesses.clear()
        ownerIndex.clear()
    }
}
