package org.labormanagement.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.config.GsonConfig.createGson
import org.labormanagement.database.Schedules
import org.labormanagement.database.Shifts as ShiftsTable
import org.labormanagement.database.SpecialEventRequirements
import org.labormanagement.database.SpecialEvents
import org.labormanagement.model.EventPayOverride
import org.labormanagement.model.EventRuleOverrides
import org.labormanagement.model.EventStaffingRequirement
import org.labormanagement.model.OptimizationObjective
import org.labormanagement.model.SpecialEvent
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * PostgreSQL-backed store for special event definitions.
 *
 * An event's staffing requirements live in their own table rather than as JSON on the event
 * row, because they are queried and validated per group. Everything else that varies in
 * shape - the employee pool, the hourly revenue map, the rule overrides - is JSON, following
 * the same approach as [SalesForecastRepository].
 */
class SpecialEventRepository(
    private val gson: Gson = createGson()
) {
    private val logger = LoggerFactory.getLogger(SpecialEventRepository::class.java)

    private val uuidListType = object : TypeToken<List<UUID>>() {}.type
    private val revenueType = object : TypeToken<Map<LocalTime, Double>>() {}.type

    fun findById(businessId: UUID, id: UUID): SpecialEvent? = transaction {
        SpecialEvents.selectAll()
            .where { (SpecialEvents.id eq id) and (SpecialEvents.businessId eq businessId) }
            .singleOrNull()
            ?.toSpecialEvent()
    }

    /** Every event for a business, soonest first. */
    fun findByBusiness(businessId: UUID): List<SpecialEvent> = transaction {
        SpecialEvents.selectAll()
            .where { SpecialEvents.businessId eq businessId }
            .orderBy(SpecialEvents.date, SortOrder.ASC)
            .map { it.toSpecialEvent() }
    }

    /**
     * Events falling within a date range, soonest first.
     *
     * Matched on the event's own date rather than on an overlap: an event belongs to the
     * night it opens, so one running past midnight is still that day's event and should not
     * surface when the following day is asked for.
     */
    fun findByBusinessAndDateRange(
        businessId: UUID,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<SpecialEvent> = transaction {
        SpecialEvents.selectAll()
            .where {
                (SpecialEvents.businessId eq businessId) and
                    (SpecialEvents.date greaterEq startDate) and
                    (SpecialEvents.date lessEq endDate)
            }
            .orderBy(SpecialEvents.date, SortOrder.ASC)
            .map { it.toSpecialEvent() }
    }

    fun create(event: SpecialEvent): SpecialEvent = transaction {
        SpecialEvents.insert { it.applyEvent(event) }
        replaceRequirements(event.id, event.requirements)
        event
    }

    /**
     * Replace an event definition wholesale.
     *
     * Requirements are rewritten rather than merged: the edit form submits the full set, so
     * a group the manager removed has to disappear rather than linger because nothing
     * mentioned it.
     */
    fun update(businessId: UUID, id: UUID, event: SpecialEvent): SpecialEvent? = transaction {
        val updated = SpecialEvents.update({
            (SpecialEvents.id eq id) and (SpecialEvents.businessId eq businessId)
        }) { it.applyEvent(event.copy(id = id)) }

        if (updated == 0) return@transaction null

        replaceRequirements(id, event.requirements)
        event.copy(id = id)
    }

    /**
     * Point an event at the schedule generated from it.
     *
     * Separate from [update] so generation does not have to rewrite the whole definition -
     * and cannot accidentally revert an edit made while it was running.
     */
    fun linkSchedule(businessId: UUID, id: UUID, scheduleId: UUID?): Boolean = transaction {
        SpecialEvents.update({
            (SpecialEvents.id eq id) and (SpecialEvents.businessId eq businessId)
        }) {
            it[SpecialEvents.scheduleId] = scheduleId
        } > 0
    }

    /**
     * Delete an event and the schedule generated from it.
     *
     * The schedule goes too because it only exists to serve this event - it is not a roster
     * anyone would keep. Left behind it would be unreachable, since the switcher finds event
     * schedules through their definitions, while its shifts carried on counting against
     * everyone's weekly hours: staff would be unavailable for a party that no longer exists.
     */
    fun delete(businessId: UUID, id: UUID): Boolean = transaction {
        val scheduleId = SpecialEvents.selectAll()
            .where { (SpecialEvents.id eq id) and (SpecialEvents.businessId eq businessId) }
            .singleOrNull()
            ?.get(SpecialEvents.scheduleId)

        // Requirements first: they reference the event.
        SpecialEventRequirements.deleteWhere { eventId eq id }
        val deleted = SpecialEvents.deleteWhere {
            (SpecialEvents.id eq id) and (SpecialEvents.businessId eq businessId)
        } > 0

        // After the event, so nothing still points at the schedule when it goes. Published
        // or not - an event that is not happening should not hold anyone's hours.
        if (deleted && scheduleId != null) {
            ShiftsTable.deleteWhere { ShiftsTable.scheduleId eq scheduleId }
            Schedules.deleteWhere { Schedules.id eq scheduleId }
        }

        deleted
    }

    /**
     * Clear the link from any event pointing at this schedule.
     *
     * Called when a schedule is deleted, so the event survives with its definition intact
     * and simply reads as not yet generated. Without this the row would keep a foreign key
     * to a schedule that no longer exists.
     */
    fun unlinkSchedule(scheduleId: UUID): Int = transaction {
        SpecialEvents.update({ SpecialEvents.scheduleId eq scheduleId }) {
            it[SpecialEvents.scheduleId] = null
        }
    }

    private fun replaceRequirements(eventId: UUID, requirements: List<EventStaffingRequirement>) {
        SpecialEventRequirements.deleteWhere { SpecialEventRequirements.eventId eq eventId }
        requirements.forEach { requirement ->
            SpecialEventRequirements.insert {
                it[SpecialEventRequirements.eventId] = eventId
                it[groupName] = requirement.groupName
                it[count] = requirement.count
                it[payRate] = (requirement.payOverride as? EventPayOverride.AbsoluteRate)?.rate
                it[payUplift] = (requirement.payOverride as? EventPayOverride.Uplift)?.amountPerHour
            }
        }
    }

    private fun <T> UpdateBuilder<T>.applyEvent(event: SpecialEvent) {
        this[SpecialEvents.id] = event.id
        this[SpecialEvents.businessId] = event.businessId
        this[SpecialEvents.name] = event.name
        this[SpecialEvents.date] = event.date
        this[SpecialEvents.startTime] = event.startTime
        this[SpecialEvents.endTime] = event.endTime
        this[SpecialEvents.notes] = event.notes
        this[SpecialEvents.employeeIds] = gson.toJson(event.employeeIds)
        this[SpecialEvents.expectedRevenue] = event.expectedRevenue?.let { gson.toJson(it) }
        this[SpecialEvents.objective] = event.objective.name
        this[SpecialEvents.ruleOverrides] = event.ruleOverrides?.let { gson.toJson(it) }
        this[SpecialEvents.scheduleId] = event.scheduleId
        this[SpecialEvents.createdAt] = event.createdAt
        this[SpecialEvents.createdBy] = event.createdBy
    }

    private fun ResultRow.toSpecialEvent(): SpecialEvent {
        val eventId = this[SpecialEvents.id]

        val requirements = SpecialEventRequirements.selectAll()
            .where { SpecialEventRequirements.eventId eq eventId }
            .orderBy(SpecialEventRequirements.groupName, SortOrder.ASC)
            .map { row ->
                val rate = row[SpecialEventRequirements.payRate]
                val uplift = row[SpecialEventRequirements.payUplift]
                EventStaffingRequirement(
                    groupName = row[SpecialEventRequirements.groupName],
                    count = row[SpecialEventRequirements.count],
                    // At most one of the two columns is ever populated, so the first match
                    // wins; both null means the group is paid its usual rate.
                    payOverride = when {
                        rate != null -> EventPayOverride.AbsoluteRate(rate)
                        uplift != null -> EventPayOverride.Uplift(uplift)
                        else -> null
                    }
                )
            }

        return SpecialEvent(
            id = eventId,
            businessId = this[SpecialEvents.businessId],
            name = this[SpecialEvents.name],
            date = this[SpecialEvents.date],
            startTime = this[SpecialEvents.startTime],
            endTime = this[SpecialEvents.endTime],
            notes = this[SpecialEvents.notes],
            employeeIds = gson.fromJson(this[SpecialEvents.employeeIds], uuidListType) ?: emptyList(),
            expectedRevenue = this[SpecialEvents.expectedRevenue]?.let { gson.fromJson(it, revenueType) },
            objective = OptimizationObjective.valueOf(this[SpecialEvents.objective]),
            requirements = requirements,
            ruleOverrides = this[SpecialEvents.ruleOverrides]?.let {
                gson.fromJson(it, EventRuleOverrides::class.java)
            },
            scheduleId = this[SpecialEvents.scheduleId],
            createdAt = this[SpecialEvents.createdAt],
            createdBy = this[SpecialEvents.createdBy]
        )
    }
}
