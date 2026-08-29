package org.labormanagement.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.labormanagement.database.EmployeeInvites
import org.labormanagement.model.EmployeeInvite
import org.labormanagement.model.InviteStatus
import org.labormanagement.model.UserRole
import java.time.Instant
import java.util.UUID

/**
 * PostgreSQL-backed repository for employee invite tokens using Exposed ORM.
 */
class EmployeeInviteRepository {

    fun create(invite: EmployeeInvite): EmployeeInvite = transaction {
        EmployeeInvites.insert {
            it[id] = invite.id
            it[employeeId] = invite.employeeId
            it[businessId] = invite.businessId
            it[email] = invite.email
            it[token] = invite.token
            it[role] = invite.role.name
            it[status] = invite.status.name
            it[invitedBy] = invite.invitedBy
            it[invitedAt] = invite.invitedAt
            it[expiresAt] = invite.expiresAt
            it[acceptedAt] = invite.acceptedAt
        }
        invite
    }

    fun findByToken(token: String): EmployeeInvite? = transaction {
        EmployeeInvites.selectAll().where { EmployeeInvites.token eq token }
            .singleOrNull()
            ?.toEmployeeInvite()
    }

    fun findPendingByEmployeeId(employeeId: UUID): EmployeeInvite? = transaction {
        EmployeeInvites.selectAll()
            .where { (EmployeeInvites.employeeId eq employeeId) and (EmployeeInvites.status eq InviteStatus.PENDING.name) }
            .singleOrNull()
            ?.toEmployeeInvite()
    }

    fun markAccepted(id: UUID) = transaction {
        EmployeeInvites.update({ EmployeeInvites.id eq id }) {
            it[status] = InviteStatus.ACCEPTED.name
            it[acceptedAt] = Instant.now()
        }
    }

    fun markRevoked(id: UUID) = transaction {
        EmployeeInvites.update({ EmployeeInvites.id eq id }) {
            it[status] = InviteStatus.REVOKED.name
        }
    }

    private fun ResultRow.toEmployeeInvite(): EmployeeInvite = EmployeeInvite(
        id = this[EmployeeInvites.id],
        employeeId = this[EmployeeInvites.employeeId],
        businessId = this[EmployeeInvites.businessId],
        email = this[EmployeeInvites.email],
        token = this[EmployeeInvites.token],
        role = try {
            UserRole.valueOf(this[EmployeeInvites.role])
        } catch (e: IllegalArgumentException) {
            // Rows created before this column existed default to EMPLOYEE.
            UserRole.EMPLOYEE
        },
        status = InviteStatus.valueOf(this[EmployeeInvites.status]),
        invitedBy = this[EmployeeInvites.invitedBy],
        invitedAt = this[EmployeeInvites.invitedAt],
        expiresAt = this[EmployeeInvites.expiresAt],
        acceptedAt = this[EmployeeInvites.acceptedAt]
    )
}
