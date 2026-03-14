package org.labormanagement.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.config.GsonConfig.createGson
import org.labormanagement.database.Schedules
import org.labormanagement.database.Shifts as ShiftsTable
import org.labormanagement.model.*
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.UUID

/**
 * PostgreSQL-backed schedule repository using Exposed ORM.
 * Handles schedules with lifecycle support and shift management.
 */
class ScheduleRepository(
    private val gson: Gson = createGson()
) {
    private val logger = LoggerFactory.getLogger(ScheduleRepository::class.java)

    /**
     * Save a schedule. If a schedule with the same date range already exists for this business,
     * it will be removed and replaced with the new schedule.
     */
    fun save(schedule: Schedule): Schedule = transaction {
        // Check if there's an existing schedule for this date range in this business
        val existing = Schedules.selectAll().where {
            (Schedules.businessId eq schedule.businessId) and
            (Schedules.startDate eq schedule.schedulePeriod.startDate) and
            (Schedules.endDate eq schedule.schedulePeriod.endDate)
        }.singleOrNull()

        if (existing != null && existing[Schedules.id] != schedule.id) {
            val existingScheduleId = existing[Schedules.id]
            // Delete shifts first
            ShiftsTable.deleteWhere { ShiftsTable.scheduleId eq existingScheduleId }
            // Delete schedule
            Schedules.deleteWhere { Schedules.id eq existingScheduleId }
        }

        // Insert or update schedule using PostgreSQL's upsert
        Schedules.upsert(
            keys = arrayOf(Schedules.id)
        ) {
            it[id] = schedule.id
            it[businessId] = schedule.businessId
            it[name] = schedule.name
            it[status] = schedule.status.name
            it[startDate] = schedule.schedulePeriod.startDate
            it[endDate] = schedule.schedulePeriod.endDate
            it[employeeIds] = gson.toJson(schedule.employeeIds)
            it[laborCostBudget] = schedule.laborCostBudget
            it[optimizationObjective] = schedule.optimizationObjective.name
            it[totalLaborCost] = schedule.metrics.totalLaborCost
            it[estimatedTotalSales] = schedule.metrics.estimatedTotalSales
            it[laborCostPercentage] = schedule.metrics.laborCostPercentage
            it[employeeUtilization] = gson.toJson(schedule.metrics.employeeUtilization)
            it[violations] = gson.toJson(schedule.violations)
            it[staffingRequirements] = gson.toJson(schedule.staffingRequirements)
            it[version] = schedule.version
            it[createdAt] = schedule.createdAt
            it[createdBy] = schedule.createdBy
            it[publishedAt] = schedule.publishedAt
            it[publishedBy] = schedule.publishedBy
            it[lastModifiedAt] = schedule.lastModifiedAt
            it[lastModifiedBy] = schedule.lastModifiedBy
            it[notes] = schedule.notes
        }

        // Delete old shifts and insert new ones
        ShiftsTable.deleteWhere { ShiftsTable.scheduleId eq schedule.id }
        schedule.shifts.forEach { shift ->
            ShiftsTable.insert {
                it[id] = shift.id
                it[scheduleId] = schedule.id
                it[employeeId] = shift.employeeId
                it[date] = shift.date
                it[startTime] = shift.startTime
                it[endTime] = shift.endTime
                it[payRate] = shift.payRate
                it[isOvertime] = shift.isOvertime
            }
        }

        schedule
    }

    /**
     * Find schedule by business ID and schedule ID
     */
    fun findById(businessId: UUID, id: UUID): Schedule? = transaction {
        Schedules.selectAll().where { (Schedules.id eq id) and (Schedules.businessId eq businessId) }
            .singleOrNull()
            ?.toSchedule()
    }

    /**
     * Find all schedules for a business, sorted by creation date (newest first)
     */
    fun findAllByBusiness(businessId: UUID): List<Schedule> = transaction {
        Schedules.selectAll().where { Schedules.businessId eq businessId }
            .orderBy(Schedules.createdAt, SortOrder.DESC)
            .map { it.toSchedule() }
    }

    /**
     * Find schedules by business and status
     */
    fun findByBusinessAndStatus(businessId: UUID, status: ScheduleStatus): List<Schedule> = transaction {
        Schedules.selectAll().where {
            (Schedules.businessId eq businessId) and (Schedules.status eq status.name)
        }
            .orderBy(Schedules.createdAt, SortOrder.DESC)
            .map { it.toSchedule() }
    }

    /**
     * Find schedule by business ID and date range (startDate and endDate)
     */
    fun findByBusinessAndDateRange(businessId: UUID, startDate: LocalDate, endDate: LocalDate): Schedule? = transaction {
        Schedules.selectAll().where {
            (Schedules.businessId eq businessId) and
            (Schedules.startDate eq startDate) and
            (Schedules.endDate eq endDate)
        }.singleOrNull()?.toSchedule()
    }

    /**
     * Update an existing schedule
     */
    fun update(id: UUID, schedule: Schedule): Schedule = transaction {
        val oldSchedule = Schedules.selectAll().where { Schedules.id eq id }.singleOrNull()
            ?: throw IllegalArgumentException("Schedule not found: $id")

        // Update schedule
        Schedules.update({ Schedules.id eq id }) {
            it[businessId] = schedule.businessId
            it[name] = schedule.name
            it[status] = schedule.status.name
            it[startDate] = schedule.schedulePeriod.startDate
            it[endDate] = schedule.schedulePeriod.endDate
            it[employeeIds] = gson.toJson(schedule.employeeIds)
            it[laborCostBudget] = schedule.laborCostBudget
            it[optimizationObjective] = schedule.optimizationObjective.name
            it[totalLaborCost] = schedule.metrics.totalLaborCost
            it[estimatedTotalSales] = schedule.metrics.estimatedTotalSales
            it[laborCostPercentage] = schedule.metrics.laborCostPercentage
            it[employeeUtilization] = gson.toJson(schedule.metrics.employeeUtilization)
            it[violations] = gson.toJson(schedule.violations)
            it[staffingRequirements] = gson.toJson(schedule.staffingRequirements)
            it[version] = schedule.version
            it[lastModifiedAt] = schedule.lastModifiedAt
            it[lastModifiedBy] = schedule.lastModifiedBy
            it[publishedAt] = schedule.publishedAt
            it[publishedBy] = schedule.publishedBy
            it[notes] = schedule.notes
        }

        // Delete old shifts and insert new ones
        ShiftsTable.deleteWhere { ShiftsTable.scheduleId eq id }
        schedule.shifts.forEach { shift ->
            ShiftsTable.insert {
                it[ShiftsTable.id] = shift.id
                it[scheduleId] = schedule.id
                it[employeeId] = shift.employeeId
                it[date] = shift.date
                it[startTime] = shift.startTime
                it[endTime] = shift.endTime
                it[payRate] = shift.payRate
                it[isOvertime] = shift.isOvertime
            }
        }

        schedule
    }

    /**
     * Delete a schedule (only drafts can be deleted)
     */
    fun delete(id: UUID): Boolean = transaction {
        val schedule = Schedules.selectAll().where { Schedules.id eq id }.singleOrNull()?.toSchedule()
            ?: throw IllegalArgumentException("Schedule not found: $id")

        if (schedule.status != ScheduleStatus.DRAFT) {
            throw IllegalStateException("Cannot delete published or archived schedule. Only drafts can be deleted.")
        }

        // Delete shifts first
        ShiftsTable.deleteWhere { ShiftsTable.scheduleId eq id }

        // Delete schedule
        Schedules.deleteWhere { Schedules.id eq id } > 0
    }

    /**
     * Force delete a schedule regardless of status.
     */
    fun forceDelete(id: UUID): Boolean = transaction {
        // Delete shifts first
        ShiftsTable.deleteWhere { ShiftsTable.scheduleId eq id }

        // Delete schedule
        Schedules.deleteWhere { Schedules.id eq id } > 0
    }

    /**
     * Extension function to convert ResultRow to Schedule domain model
     */
    private fun ResultRow.toSchedule(): Schedule {
        val scheduleId = this[Schedules.id]

        // Load shifts for this schedule
        val shifts = ShiftsTable.selectAll().where { ShiftsTable.scheduleId eq scheduleId }
            .map { row ->
                Shift(
                    id = row[ShiftsTable.id],
                    employeeId = row[ShiftsTable.employeeId],
                    date = row[ShiftsTable.date],
                    startTime = row[ShiftsTable.startTime],
                    endTime = row[ShiftsTable.endTime],
                    payRate = row[ShiftsTable.payRate],
                    isOvertime = row[ShiftsTable.isOvertime]
                )
            }

        val uuidListType = object : TypeToken<List<UUID>>() {}.type
        val stringDoubleMapType = object : TypeToken<Map<String, Double>>() {}.type
        val violationsType = object : TypeToken<List<ConstraintViolation>>() {}.type
        val staffingType = object : TypeToken<List<StaffingRequirement>>() {}.type

        return Schedule(
            id = scheduleId,
            businessId = this[Schedules.businessId],
            name = this[Schedules.name],
            status = ScheduleStatus.valueOf(this[Schedules.status]),
            schedulePeriod = SchedulePeriod(
                startDate = this[Schedules.startDate],
                endDate = this[Schedules.endDate],
                operatingHours = emptyMap() // Operating hours not stored in DB, reconstructed if needed
            ),
            shifts = shifts,
            metrics = SchedulingMetrics(
                totalLaborCost = this[Schedules.totalLaborCost],
                estimatedTotalSales = this[Schedules.estimatedTotalSales],
                laborCostPercentage = this[Schedules.laborCostPercentage],
                employeeUtilization = gson.fromJson(this[Schedules.employeeUtilization], stringDoubleMapType)
            ),
            violations = gson.fromJson(this[Schedules.violations], violationsType),
            staffingRequirements = gson.fromJson(this[Schedules.staffingRequirements], staffingType),
            employeeIds = gson.fromJson(this[Schedules.employeeIds], uuidListType),
            laborCostBudget = this[Schedules.laborCostBudget],
            optimizationObjective = OptimizationObjective.valueOf(this[Schedules.optimizationObjective]),
            version = this[Schedules.version],
            createdAt = this[Schedules.createdAt],
            createdBy = this[Schedules.createdBy],
            publishedAt = this[Schedules.publishedAt],
            publishedBy = this[Schedules.publishedBy],
            lastModifiedAt = this[Schedules.lastModifiedAt],
            lastModifiedBy = this[Schedules.lastModifiedBy],
            notes = this[Schedules.notes]
        )
    }
}
