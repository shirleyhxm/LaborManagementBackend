package org.labormanagement.repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.database.EmployeeGroups
import org.labormanagement.model.EmployeeGroup
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * PostgreSQL-backed employee group repository using Exposed ORM.
 * Manages employee groups (tags) with multi-tenant support.
 */
class EmployeeGroupRepository {
    private val logger = LoggerFactory.getLogger(EmployeeGroupRepository::class.java)

    /**
     * Add a new group tag for a specific business (case-insensitive)
     */
    fun addForBusiness(businessId: UUID, name: String): EmployeeGroup? = transaction {
        val key = name.lowercase()

        // Check if group already exists (case-insensitive)
        val exists = EmployeeGroups.selectAll().where {
            (EmployeeGroups.businessId eq businessId) and (EmployeeGroups.name.lowerCase() eq key)
        }.singleOrNull() != null

        if (exists) {
            return@transaction null
        }

        EmployeeGroups.insert {
            it[EmployeeGroups.businessId] = businessId
            it[EmployeeGroups.name] = name
        }

        EmployeeGroup(businessId, name)
    }

    /**
     * Check if group exists for a specific business (case-insensitive)
     */
    fun existsForBusiness(businessId: UUID, name: String): Boolean = transaction {
        val key = name.lowercase()
        !EmployeeGroups.selectAll().where {
            (EmployeeGroups.businessId eq businessId) and (EmployeeGroups.name.lowerCase() eq key)
        }.empty()
    }

    /**
     * Get canonical name for a specific business (returns the stored display name)
     */
    fun getCanonicalNameForBusiness(businessId: UUID, name: String): String? = transaction {
        val key = name.lowercase()
        EmployeeGroups.selectAll().where {
            (EmployeeGroups.businessId eq businessId) and (EmployeeGroups.name.lowerCase() eq key)
        }.singleOrNull()?.let { it[EmployeeGroups.name] }
    }

    /**
     * Get all group tags for a specific business
     */
    fun findAllForBusiness(businessId: UUID): List<EmployeeGroup> = transaction {
        EmployeeGroups.selectAll().where { EmployeeGroups.businessId eq businessId }
            .orderBy(EmployeeGroups.name, SortOrder.ASC)
            .map { row ->
                EmployeeGroup(
                    businessId = row[EmployeeGroups.businessId],
                    name = row[EmployeeGroups.name]
                )
            }
    }

    /**
     * Delete a group tag for a specific business (case-insensitive)
     */
    fun deleteForBusiness(businessId: UUID, name: String): Boolean = transaction {
        val key = name.lowercase()
        val deletedCount = EmployeeGroups.deleteWhere {
            (EmployeeGroups.businessId eq businessId) and (EmployeeGroups.name.lowerCase() eq key)
        }
        deletedCount > 0
    }

    /**
     * Rename a group tag for a specific business (case-insensitive)
     */
    fun renameForBusiness(businessId: UUID, oldName: String, newName: String): Boolean = transaction {
        val oldKey = oldName.lowercase()
        val newKey = newName.lowercase()

        // Check if old name exists
        val oldExists = EmployeeGroups.selectAll().where {
            (EmployeeGroups.businessId eq businessId) and (EmployeeGroups.name.lowerCase() eq oldKey)
        }.singleOrNull()

        if (oldExists == null) {
            return@transaction false
        }

        // Check if new name already exists
        val newExists = EmployeeGroups.selectAll().where {
            (EmployeeGroups.businessId eq businessId) and (EmployeeGroups.name.lowerCase() eq newKey)
        }.singleOrNull()

        if (newExists != null) {
            return@transaction false
        }

        // Update the name
        val updatedCount = EmployeeGroups.update({
            (EmployeeGroups.businessId eq businessId) and (EmployeeGroups.name.lowerCase() eq oldKey)
        }) {
            it[name] = newName
        }

        updatedCount > 0
    }

    /**
     * Delete all groups for a business (used when business is deleted)
     */
    fun deleteAllForBusiness(businessId: UUID): Boolean = transaction {
        val deletedCount = EmployeeGroups.deleteWhere { EmployeeGroups.businessId eq businessId }
        deletedCount > 0
    }
}
