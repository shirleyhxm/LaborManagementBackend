package org.labormanagement.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.database.EmployeeShares
import org.labormanagement.model.EmployeeShare
import java.util.UUID

/**
 * Which employees are lent to which businesses.
 *
 * Read on every employee lookup, so the two finders that sit on the
 * authorization path ([findEmployeeIdsSharedInto], [isSharedInto]) stay single
 * indexed reads.
 */
class EmployeeShareRepository {

    /**
     * Lend an employee to a business. Idempotent: re-sharing an already-shared
     * employee returns the existing share rather than tripping the unique index.
     *
     * Callers must have already checked that both businesses have the same
     * owner - this repository does not know about ownership.
     */
    fun share(share: EmployeeShare): EmployeeShare = transaction {
        val existing = find(share.employeeId, share.businessId)
        if (existing != null) return@transaction existing

        EmployeeShares.insert {
            it[id] = share.id
            it[employeeId] = share.employeeId
            it[businessId] = share.businessId
            it[sharedBy] = share.sharedBy
            it[sharedAt] = share.sharedAt
        }
        share
    }

    fun find(employeeId: UUID, businessId: UUID): EmployeeShare? = transaction {
        EmployeeShares.selectAll()
            .where {
                (EmployeeShares.employeeId eq employeeId) and
                    (EmployeeShares.businessId eq businessId)
            }
            .singleOrNull()
            ?.toEmployeeShare()
    }

    /**
     * Whether this employee is currently lent to this business. Kept separate
     * from [find] because the authorization path only needs the boolean.
     */
    fun isSharedInto(employeeId: UUID, businessId: UUID): Boolean =
        find(employeeId, businessId) != null

    /**
     * Ids of every employee lent *into* this business. Used to widen employee
     * listings beyond the business's own staff.
     */
    fun findEmployeeIdsSharedInto(businessId: UUID): List<UUID> = transaction {
        EmployeeShares.selectAll()
            .where { EmployeeShares.businessId eq businessId }
            .map { it[EmployeeShares.employeeId] }
    }

    /**
     * Every business this employee is lent to - drives the sharing UI and
     * tells an admin who else is affected before they edit a shared record.
     */
    fun findBusinessIdsForEmployee(employeeId: UUID): List<UUID> = transaction {
        EmployeeShares.selectAll()
            .where { EmployeeShares.employeeId eq employeeId }
            .map { it[EmployeeShares.businessId] }
    }

    /**
     * Stop lending an employee to a business. Returns false if they weren't
     * shared there.
     */
    fun unshare(employeeId: UUID, businessId: UUID): Boolean = transaction {
        EmployeeShares.deleteWhere {
            (EmployeeShares.employeeId eq employeeId) and
                (EmployeeShares.businessId eq businessId)
        } > 0
    }

    /**
     * Drop every share for an employee, e.g. when they are deleted outright.
     */
    fun deleteByEmployee(employeeId: UUID): Int = transaction {
        EmployeeShares.deleteWhere { EmployeeShares.employeeId eq employeeId }
    }

    /**
     * Drop every share pointing at a business, e.g. when it is deleted.
     */
    fun deleteByBusiness(businessId: UUID): Int = transaction {
        EmployeeShares.deleteWhere { EmployeeShares.businessId eq businessId }
    }

    /**
     * Clear all data (for testing).
     */
    fun clear() = transaction {
        EmployeeShares.deleteAll()
    }

    private fun ResultRow.toEmployeeShare(): EmployeeShare = EmployeeShare(
        id = this[EmployeeShares.id],
        employeeId = this[EmployeeShares.employeeId],
        businessId = this[EmployeeShares.businessId],
        sharedBy = this[EmployeeShares.sharedBy],
        sharedAt = this[EmployeeShares.sharedAt]
    )
}
