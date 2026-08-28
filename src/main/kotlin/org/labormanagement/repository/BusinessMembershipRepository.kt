package org.labormanagement.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.labormanagement.database.BusinessMemberships
import org.labormanagement.model.BusinessMembership
import org.labormanagement.model.MembershipStatus
import org.labormanagement.model.UserRole
import java.util.UUID

/**
 * PostgreSQL-backed repository for per-business role grants.
 *
 * Holds MANAGER grants only - see [BusinessMemberships] for why ADMIN is
 * derived from business ownership instead of stored here.
 */
class BusinessMembershipRepository {

    /**
     * Grant a user a role in a business, or update the role if a grant already
     * exists. Upsert rather than insert, since re-assigning someone who is
     * already a manager should be idempotent rather than a unique-index error.
     */
    fun upsert(membership: BusinessMembership): BusinessMembership = transaction {
        val existing = findByBusinessAndUser(membership.businessId, membership.userId)

        if (existing == null) {
            BusinessMemberships.insert {
                it[id] = membership.id
                it[businessId] = membership.businessId
                it[userId] = membership.userId
                it[role] = membership.role.name
                it[status] = membership.status.name
                it[invitedBy] = membership.invitedBy
                it[invitedAt] = membership.invitedAt
            }
            membership
        } else {
            BusinessMemberships.update({
                (BusinessMemberships.businessId eq membership.businessId) and
                    (BusinessMemberships.userId eq membership.userId)
            }) {
                it[role] = membership.role.name
                it[status] = membership.status.name
            }
            existing.copy(role = membership.role, status = membership.status)
        }
    }

    /**
     * Find the grant a user holds in a specific business, if any.
     * This is the lookup on the authorization path, so it stays a single
     * indexed row read.
     */
    fun findByBusinessAndUser(businessId: UUID, userId: String): BusinessMembership? = transaction {
        BusinessMemberships.selectAll()
            .where {
                (BusinessMemberships.businessId eq businessId) and
                    (BusinessMemberships.userId eq userId)
            }
            .singleOrNull()
            ?.toBusinessMembership()
    }

    /**
     * All grants for a business - drives the Team/Members list.
     */
    fun findByBusiness(businessId: UUID): List<BusinessMembership> = transaction {
        BusinessMemberships.selectAll()
            .where { BusinessMemberships.businessId eq businessId }
            .map { it.toBusinessMembership() }
    }

    /**
     * All grants held by a user across businesses - drives which businesses a
     * manager sees in their switcher.
     */
    fun findByUser(userId: String): List<BusinessMembership> = transaction {
        BusinessMemberships.selectAll()
            .where { BusinessMemberships.userId eq userId }
            .map { it.toBusinessMembership() }
    }

    /**
     * Revoke a grant. Returns false if the user held no grant in this business.
     */
    fun delete(businessId: UUID, userId: String): Boolean = transaction {
        BusinessMemberships.deleteWhere {
            (BusinessMemberships.businessId eq businessId) and
                (BusinessMemberships.userId eq userId)
        } > 0
    }

    /**
     * Remove every grant for a business, e.g. when the business is deleted.
     */
    fun deleteByBusiness(businessId: UUID): Int = transaction {
        BusinessMemberships.deleteWhere { BusinessMemberships.businessId eq businessId }
    }

    /**
     * Clear all data (for testing).
     */
    fun clear() = transaction {
        BusinessMemberships.deleteAll()
    }

    private fun ResultRow.toBusinessMembership(): BusinessMembership {
        return BusinessMembership(
            id = this[BusinessMemberships.id],
            businessId = this[BusinessMemberships.businessId],
            userId = this[BusinessMemberships.userId],
            role = UserRole.valueOf(this[BusinessMemberships.role]),
            invitedBy = this[BusinessMemberships.invitedBy],
            invitedAt = this[BusinessMemberships.invitedAt],
            status = MembershipStatus.valueOf(this[BusinessMemberships.status])
        )
    }
}
