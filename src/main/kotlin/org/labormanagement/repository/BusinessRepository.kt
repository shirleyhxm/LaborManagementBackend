package org.labormanagement.repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.database.Businesses
import org.labormanagement.database.Employees
import org.labormanagement.model.Business
import org.labormanagement.model.BusinessSettings
import org.labormanagement.model.BusinessStatus
import org.labormanagement.model.SubscriptionPlan
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.util.UUID

/**
 * PostgreSQL-backed business repository using Exposed ORM.
 * Handles multi-tenancy business management.
 */
class BusinessRepository {
    private val logger = LoggerFactory.getLogger(BusinessRepository::class.java)

    /**
     * Create a new business.
     */
    fun create(business: Business): Business = transaction {
        Businesses.insert {
            it[id] = business.id
            it[name] = business.name
            it[ownerId] = business.ownerId
            it[subdomain] = business.subdomain
            it[plan] = business.plan.name
            it[status] = business.status.name
            it[createdAt] = business.createdAt
            it[subscriptionId] = business.subscriptionId
            it[billingEmail] = business.billingEmail
            it[subscriptionExpiresAt] = business.subscriptionExpiresAt
            it[maxEmployees] = business.maxEmployees
            it[maxLocations] = business.maxLocations

            // Business settings
            it[timezone] = business.settings.timezone
            it[currency] = business.settings.currency
            it[weekStartsOn] = business.settings.weekStartsOn.name
            it[dateFormat] = business.settings.dateFormat
        }

        business
    }

    /**
     * Find a business by ID.
     */
    fun findById(id: UUID): Business? = transaction {
        Businesses.selectAll().where { Businesses.id eq id }
            .singleOrNull()
            ?.toBusiness()
    }

    /**
     * Find all businesses owned by a specific user.
     */
    fun findByOwnerId(ownerId: String): List<Business> = transaction {
        Businesses.selectAll().where { Businesses.ownerId eq ownerId }
            .map { it.toBusiness() }
    }

    /**
     * Find all active businesses for a user (owned or member).
     * Note: For now, we only return owned businesses.
     */
    fun findByUserId(userId: String): List<Business> {
        return findByOwnerId(userId)
    }

    /**
     * Find all businesses (admin function).
     */
    fun findAll(): List<Business> = transaction {
        Businesses.selectAll().map { it.toBusiness() }
    }

    /**
     * Update a business.
     */
    fun update(id: UUID, business: Business): Business? = transaction {
        val existing = Businesses.selectAll().where { Businesses.id eq id }.singleOrNull()
            ?: return@transaction null

        Businesses.update({ Businesses.id eq id }) {
            it[name] = business.name
            it[ownerId] = business.ownerId
            it[subdomain] = business.subdomain
            it[plan] = business.plan.name
            it[status] = business.status.name
            it[subscriptionId] = business.subscriptionId
            it[billingEmail] = business.billingEmail
            it[subscriptionExpiresAt] = business.subscriptionExpiresAt
            it[maxEmployees] = business.maxEmployees
            it[maxLocations] = business.maxLocations

            // Business settings
            it[timezone] = business.settings.timezone
            it[currency] = business.settings.currency
            it[weekStartsOn] = business.settings.weekStartsOn.name
            it[dateFormat] = business.settings.dateFormat
        }

        business
    }

    /**
     * Delete a business (soft delete by changing status).
     */
    fun delete(id: UUID): Boolean = transaction {
        val existing = Businesses.selectAll().where { Businesses.id eq id }.singleOrNull()
            ?: return@transaction false

        Businesses.update({ Businesses.id eq id }) {
            it[status] = BusinessStatus.CANCELLED.name
        }

        true
    }

    /**
     * Hard delete a business (permanently remove from storage).
     * Should only be used for testing or admin operations.
     */
    fun hardDelete(id: UUID): Boolean = transaction {
        Businesses.deleteWhere { Businesses.id eq id } > 0
    }

    /**
     * Check if a business with the given ID exists.
     */
    fun exists(id: UUID): Boolean = transaction {
        !Businesses.selectAll().where { Businesses.id eq id }.empty()
    }

    /**
     * Check if a user owns a specific business.
     */
    fun isOwner(userId: String, businessId: UUID): Boolean = transaction {
        val business = Businesses.selectAll().where { Businesses.id eq businessId }
            .singleOrNull()
            ?.toBusiness()
            ?: return@transaction false

        business.ownerId == userId
    }

    /**
     * Check if a user has access to a specific business - either as owner,
     * or as an employee linked to that business (Employees.userId).
     */
    fun hasAccess(userId: String, businessId: UUID): Boolean {
        if (isOwner(userId, businessId)) return true

        return transaction {
            Employees.selectAll()
                .where { (Employees.userId eq userId) and (Employees.businessId eq businessId) }
                .empty().not()
        }
    }

    /**
     * Clear all data (for testing).
     */
    fun clear() = transaction {
        Businesses.deleteAll()
    }

    /**
     * Extension function to convert ResultRow to Business domain model
     */
    private fun ResultRow.toBusiness(): Business {
        return Business(
            id = this[Businesses.id],
            name = this[Businesses.name],
            ownerId = this[Businesses.ownerId],
            subdomain = this[Businesses.subdomain],
            plan = SubscriptionPlan.valueOf(this[Businesses.plan]),
            status = BusinessStatus.valueOf(this[Businesses.status]),
            createdAt = this[Businesses.createdAt],
            subscriptionId = this[Businesses.subscriptionId],
            billingEmail = this[Businesses.billingEmail],
            subscriptionExpiresAt = this[Businesses.subscriptionExpiresAt],
            maxEmployees = this[Businesses.maxEmployees],
            maxLocations = this[Businesses.maxLocations],
            settings = BusinessSettings(
                timezone = this[Businesses.timezone],
                currency = this[Businesses.currency],
                weekStartsOn = DayOfWeek.valueOf(this[Businesses.weekStartsOn]),
                dateFormat = this[Businesses.dateFormat]
            )
        )
    }
}
