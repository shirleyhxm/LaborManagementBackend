package org.labormanagement.repository

import org.labormanagement.model.EmployeeGroup
import java.util.concurrent.ConcurrentHashMap

class EmployeeGroupRepository {
    // Simple set of group tags (like enums) - stored in lowercase for case-insensitive comparison
    // Maps lowercase name -> display name
    private val groups = ConcurrentHashMap<String, String>()

    // Add a new group tag (case-insensitive)
    fun add(name: String): EmployeeGroup? {
        val key = name.lowercase()
        return if (groups.containsKey(key)) {
            null // Already exists
        } else {
            groups[key] = name
            EmployeeGroup(name)
        }
    }

    // Check if group exists (case-insensitive)
    fun exists(name: String): Boolean {
        return groups.containsKey(name.lowercase())
    }

    // Get canonical name (returns the stored display name)
    fun getCanonicalName(name: String): String? {
        return groups[name.lowercase()]
    }

    // Get all group tags
    fun findAll(): List<EmployeeGroup> {
        return groups.values.sorted().map { EmployeeGroup(it) }
    }

    // Delete a group tag (case-insensitive)
    fun delete(name: String): Boolean {
        return groups.remove(name.lowercase()) != null
    }

    // Rename a group tag (case-insensitive)
    fun rename(oldName: String, newName: String): Boolean {
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
}
