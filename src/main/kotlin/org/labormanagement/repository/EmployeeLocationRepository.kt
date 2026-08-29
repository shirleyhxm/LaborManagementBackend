package org.labormanagement.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.database.EmployeeLocations
import org.labormanagement.model.EmployeeLocation
import java.util.UUID

/**
 * Which employees are assigned to which locations.
 *
 * Read on every employee lookup, so the two finders on the authorization path
 * ([findEmployeeIdsAssignedTo], [isAssignedTo]) stay single indexed reads.
 */
class EmployeeLocationRepository {

    /**
     * Assign an employee to a location. Idempotent: re-assigning an already
     * assigned employee returns the existing row rather than tripping the
     * unique index.
     *
     * Callers must have already checked that both locations have the same
     * owner - this repository does not know about ownership.
     */
    fun assign(location: EmployeeLocation): EmployeeLocation = transaction {
        val existing = find(location.employeeId, location.businessId)
        if (existing != null) return@transaction existing

        EmployeeLocations.insert {
            it[id] = location.id
            it[employeeId] = location.employeeId
            it[businessId] = location.businessId
            it[assignedBy] = location.assignedBy
            it[assignedAt] = location.assignedAt
        }
        location
    }

    fun find(employeeId: UUID, businessId: UUID): EmployeeLocation? = transaction {
        EmployeeLocations.selectAll()
            .where {
                (EmployeeLocations.employeeId eq employeeId) and
                    (EmployeeLocations.businessId eq businessId)
            }
            .singleOrNull()
            ?.toEmployeeLocation()
    }

    /**
     * Whether this employee is currently assigned to this location. Kept
     * separate from [find] because the authorization path only needs the
     * boolean.
     */
    fun isAssignedTo(employeeId: UUID, businessId: UUID): Boolean =
        find(employeeId, businessId) != null

    /**
     * Ids of every employee assigned *into* this location. Used to widen
     * employee listings beyond the location's own staff.
     */
    fun findEmployeeIdsAssignedTo(businessId: UUID): List<UUID> = transaction {
        EmployeeLocations.selectAll()
            .where { EmployeeLocations.businessId eq businessId }
            .map { it[EmployeeLocations.employeeId] }
    }

    /**
     * Every location this employee is assigned to - drives the Locations tab
     * and tells an admin who else is affected before they edit the record.
     */
    fun findBusinessIdsForEmployee(employeeId: UUID): List<UUID> = transaction {
        EmployeeLocations.selectAll()
            .where { EmployeeLocations.employeeId eq employeeId }
            .map { it[EmployeeLocations.businessId] }
    }

    /**
     * Remove an employee from a location. Returns false if they were not
     * assigned there.
     */
    fun unassign(employeeId: UUID, businessId: UUID): Boolean = transaction {
        EmployeeLocations.deleteWhere {
            (EmployeeLocations.employeeId eq employeeId) and
                (EmployeeLocations.businessId eq businessId)
        } > 0
    }

    /**
     * Drop every assignment for an employee, e.g. when they are deleted.
     */
    fun deleteByEmployee(employeeId: UUID): Int = transaction {
        EmployeeLocations.deleteWhere { EmployeeLocations.employeeId eq employeeId }
    }

    /**
     * Drop every assignment pointing at a location, e.g. when it is deleted.
     */
    fun deleteByBusiness(businessId: UUID): Int = transaction {
        EmployeeLocations.deleteWhere { EmployeeLocations.businessId eq businessId }
    }

    /**
     * Clear all data (for testing).
     */
    fun clear() = transaction {
        EmployeeLocations.deleteAll()
    }

    private fun ResultRow.toEmployeeLocation(): EmployeeLocation = EmployeeLocation(
        id = this[EmployeeLocations.id],
        employeeId = this[EmployeeLocations.employeeId],
        businessId = this[EmployeeLocations.businessId],
        assignedBy = this[EmployeeLocations.assignedBy],
        assignedAt = this[EmployeeLocations.assignedAt]
    )
}
