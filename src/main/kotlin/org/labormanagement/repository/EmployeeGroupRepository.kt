package org.labormanagement.repository

import org.labormanagement.model.EmployeeGroup
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for managing employee groups (tags) with multi-tenant support.
 * Each business has its own set of group tags.
 */
class EmployeeGroupRepository {
    // Map of businessId -> (lowercase name -> display name)
    private val businessGroups = ConcurrentHashMap<UUID, ConcurrentHashMap<String, String>>()

    // ===== Business-Scoped Methods (Multi-Tenant) =====

    /**
     * Add a new group tag for a specific business (case-insensitive)
     */
    fun addForBusiness(businessId: UUID, name: String): EmployeeGroup? {
        val groups = businessGroups.computeIfAbsent(businessId) { ConcurrentHashMap() }
        val key = name.lowercase()
        return if (groups.containsKey(key)) {
            null // Already exists
        } else {
            groups[key] = name
            EmployeeGroup(businessId, name)
        }
    }

    /**
     * Check if group exists for a specific business (case-insensitive)
     */
    fun existsForBusiness(businessId: UUID, name: String): Boolean {
        val groups = businessGroups[businessId] ?: return false
        return groups.containsKey(name.lowercase())
    }

    /**
     * Get canonical name for a specific business (returns the stored display name)
     */
    fun getCanonicalNameForBusiness(businessId: UUID, name: String): String? {
        val groups = businessGroups[businessId] ?: return null
        return groups[name.lowercase()]
    }

    /**
     * Get all group tags for a specific business
     */
    fun findAllForBusiness(businessId: UUID): List<EmployeeGroup> {
        val groups = businessGroups[businessId] ?: return emptyList()
        return groups.values.sorted().map { EmployeeGroup(businessId, it) }
    }

    /**
     * Delete a group tag for a specific business (case-insensitive)
     */
    fun deleteForBusiness(businessId: UUID, name: String): Boolean {
        val groups = businessGroups[businessId] ?: return false
        return groups.remove(name.lowercase()) != null
    }

    /**
     * Rename a group tag for a specific business (case-insensitive)
     */
    fun renameForBusiness(businessId: UUID, oldName: String, newName: String): Boolean {
        val groups = businessGroups[businessId] ?: return false
        val oldKey = oldName.lowercase()
        val newKey = newName.lowercase()

        return if (groups.containsKey(oldKey) && !groups.containsKey(newKey)) {
            groups.remove(oldKey)
            groups[newKey] = newName
            true
        } else {
            false
        }
    }

    /**
     * Delete all groups for a business (used when business is deleted)
     */
    fun deleteAllForBusiness(businessId: UUID): Boolean {
        return businessGroups.remove(businessId) != null
    }
}
