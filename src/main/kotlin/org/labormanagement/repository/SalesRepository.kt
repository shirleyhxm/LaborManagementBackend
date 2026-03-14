package org.labormanagement.repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.database.Sales
import org.labormanagement.model.SalesRecord
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * PostgreSQL-backed sales repository using Exposed ORM.
 * Manages sales records with multi-tenant support.
 */
class SalesRepository {
    private val logger = LoggerFactory.getLogger(SalesRepository::class.java)

    /**
     * Create a new sales record
     */
    fun create(record: SalesRecord): SalesRecord = transaction {
        Sales.insert {
            it[id] = record.id
            it[businessId] = record.businessId
            it[employeeId] = record.employeeId
            it[scheduleId] = record.scheduleId
            it[shiftId] = record.shiftId
            it[amount] = record.amount
            it[category] = record.category
            it[description] = record.description
            it[recordedAt] = record.recordedAt
            it[createdBy] = record.createdBy
        }

        record
    }

    /**
     * Find a sales record by ID with business verification
     */
    fun findById(businessId: UUID, id: UUID): SalesRecord? = transaction {
        Sales.selectAll().where { (Sales.id eq id) and (Sales.businessId eq businessId) }
            .singleOrNull()
            ?.toSalesRecord()
    }

    /**
     * Find all sales records for a specific business
     */
    fun findAllByBusiness(businessId: UUID): List<SalesRecord> = transaction {
        Sales.selectAll().where { Sales.businessId eq businessId }
            .orderBy(Sales.recordedAt, SortOrder.DESC)
            .map { it.toSalesRecord() }
    }

    /**
     * Find sales records by employee ID within a specific business
     */
    fun findByEmployeeId(businessId: UUID, employeeId: UUID): List<SalesRecord> = transaction {
        Sales.selectAll().where {
            (Sales.businessId eq businessId) and (Sales.employeeId eq employeeId)
        }
            .orderBy(Sales.recordedAt, SortOrder.DESC)
            .map { it.toSalesRecord() }
    }

    /**
     * Find sales records by shift ID within a specific business
     */
    fun findByShiftId(businessId: UUID, shiftId: UUID): List<SalesRecord> = transaction {
        Sales.selectAll().where {
            (Sales.businessId eq businessId) and (Sales.shiftId eq shiftId)
        }
            .orderBy(Sales.recordedAt, SortOrder.DESC)
            .map { it.toSalesRecord() }
    }

    /**
     * Find sales records by schedule ID within a specific business
     */
    fun findByScheduleId(businessId: UUID, scheduleId: UUID): List<SalesRecord> = transaction {
        Sales.selectAll().where {
            (Sales.businessId eq businessId) and (Sales.scheduleId eq scheduleId)
        }
            .orderBy(Sales.recordedAt, SortOrder.DESC)
            .map { it.toSalesRecord() }
    }

    /**
     * Find sales records within a date range for a specific business
     */
    fun findByDateRange(businessId: UUID, startDate: Instant, endDate: Instant): List<SalesRecord> = transaction {
        Sales.selectAll().where {
            (Sales.businessId eq businessId) and
            (Sales.recordedAt greaterEq startDate) and
            (Sales.recordedAt lessEq endDate)
        }
            .orderBy(Sales.recordedAt, SortOrder.DESC)
            .map { it.toSalesRecord() }
    }

    /**
     * Find sales records by employee within a date range for a specific business
     */
    fun findByEmployeeIdAndDateRange(
        businessId: UUID,
        employeeId: UUID,
        startDate: Instant,
        endDate: Instant
    ): List<SalesRecord> = transaction {
        Sales.selectAll().where {
            (Sales.businessId eq businessId) and
            (Sales.employeeId eq employeeId) and
            (Sales.recordedAt greaterEq startDate) and
            (Sales.recordedAt lessEq endDate)
        }
            .orderBy(Sales.recordedAt, SortOrder.DESC)
            .map { it.toSalesRecord() }
    }

    /**
     * Find sales records by category within a specific business
     */
    fun findByCategory(businessId: UUID, category: String): List<SalesRecord> = transaction {
        Sales.selectAll().where {
            (Sales.businessId eq businessId) and (Sales.category.lowerCase() eq category.lowercase())
        }
            .orderBy(Sales.recordedAt, SortOrder.DESC)
            .map { it.toSalesRecord() }
    }

    /**
     * Get total sales for an employee in a date range within a specific business
     */
    fun getTotalSales(
        businessId: UUID,
        employeeId: UUID,
        startDate: Instant,
        endDate: Instant
    ): Double = transaction {
        findByEmployeeIdAndDateRange(businessId, employeeId, startDate, endDate)
            .sumOf { it.amount }
    }

    /**
     * Get total sales for all employees in a date range within a specific business
     */
    fun getTotalSalesAllEmployees(businessId: UUID, startDate: Instant, endDate: Instant): Double = transaction {
        findByDateRange(businessId, startDate, endDate)
            .sumOf { it.amount }
    }

    /**
     * Get sales count for an employee in a date range within a specific business
     */
    fun getSalesCount(
        businessId: UUID,
        employeeId: UUID,
        startDate: Instant,
        endDate: Instant
    ): Int = transaction {
        findByEmployeeIdAndDateRange(businessId, employeeId, startDate, endDate).size
    }

    /**
     * Update a sales record with business verification
     */
    fun update(businessId: UUID, record: SalesRecord): SalesRecord? = transaction {
        val existing = Sales.selectAll().where {
            (Sales.id eq record.id) and (Sales.businessId eq businessId)
        }.singleOrNull() ?: return@transaction null

        Sales.update({ Sales.id eq record.id }) {
            it[Sales.businessId] = record.businessId
            it[employeeId] = record.employeeId
            it[scheduleId] = record.scheduleId
            it[shiftId] = record.shiftId
            it[amount] = record.amount
            it[category] = record.category
            it[description] = record.description
            it[recordedAt] = record.recordedAt
            it[createdBy] = record.createdBy
        }

        record
    }

    /**
     * Delete a sales record with business verification
     */
    fun delete(businessId: UUID, id: UUID): Boolean = transaction {
        val deletedCount = Sales.deleteWhere {
            (Sales.id eq id) and (Sales.businessId eq businessId)
        }
        deletedCount > 0
    }

    /**
     * Delete all sales records for a business (for testing)
     */
    fun deleteAllForBusiness(businessId: UUID) = transaction {
        Sales.deleteWhere { Sales.businessId eq businessId }
    }

    /**
     * Extension function to convert ResultRow to SalesRecord domain model
     */
    private fun ResultRow.toSalesRecord(): SalesRecord {
        return SalesRecord(
            id = this[Sales.id],
            businessId = this[Sales.businessId],
            employeeId = this[Sales.employeeId],
            scheduleId = this[Sales.scheduleId],
            shiftId = this[Sales.shiftId],
            amount = this[Sales.amount],
            category = this[Sales.category],
            description = this[Sales.description],
            recordedAt = this[Sales.recordedAt],
            createdBy = this[Sales.createdBy]
        )
    }
}
