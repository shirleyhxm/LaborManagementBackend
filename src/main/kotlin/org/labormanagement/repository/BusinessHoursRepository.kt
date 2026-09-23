package org.labormanagement.repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.database.BusinessHourOverrides
import org.labormanagement.database.BusinessHours as BusinessHoursTable
import org.labormanagement.database.Businesses
import org.labormanagement.model.BusinessDayHours
import org.labormanagement.model.BusinessHourOverride
import org.labormanagement.model.BusinessHours
import org.labormanagement.model.OperatingHours
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * PostgreSQL-backed store for a business's opening hours and its date-specific exceptions.
 */
class BusinessHoursRepository {
    private val logger = LoggerFactory.getLogger(BusinessHoursRepository::class.java)

    /**
     * A business's full hours, including the legacy settings pair as the fallback for a
     * business that has no weekly rows yet.
     *
     * Reading the fallback here (rather than defaulting to a literal) is what keeps an
     * existing database generating what it did before this feature: those businesses have
     * no business_hours rows at all, and their configured default_open_time is still the
     * right answer for every day.
     */
    fun findByBusinessId(businessId: UUID): BusinessHours = transaction {
        val fallback = Businesses
            .select { Businesses.id eq businessId }
            .singleOrNull()
            ?.let {
                OperatingHours(
                    openTime = it[Businesses.defaultOpenTime],
                    closeTime = it[Businesses.defaultCloseTime]
                )
            }
            ?: OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))

        BusinessHours(
            businessId = businessId,
            week = BusinessHoursTable
                .select { BusinessHoursTable.businessId eq businessId }
                .map { it.toDayHours() },
            overrides = BusinessHourOverrides
                .select { BusinessHourOverrides.businessId eq businessId }
                .orderBy(BusinessHourOverrides.date to SortOrder.ASC)
                .map { it.toOverride() },
            fallback = fallback
        )
    }

    /**
     * Replace the weekly pattern wholesale.
     *
     * The week is saved as a unit rather than a day at a time: the editor submits all
     * seven, and a partial write would leave a business half on its old hours with no way
     * to tell which days had been reached.
     */
    fun saveWeek(businessId: UUID, week: List<BusinessDayHours>): List<BusinessDayHours> = transaction {
        val now = Instant.now()
        BusinessHoursTable.deleteWhere { BusinessHoursTable.businessId eq businessId }
        week.forEach { day ->
            BusinessHoursTable.insert {
                it[BusinessHoursTable.businessId] = businessId
                it[dayOfWeek] = day.dayOfWeek.name
                it[openTime] = day.openTime
                it[closeTime] = day.closeTime
                it[isClosed] = day.isClosed
                it[updatedAt] = now
            }
        }
        logger.info("Saved ${week.size} days of business hours for business $businessId")
        week.map { it.copy(updatedAt = now) }
    }

    /**
     * Add or replace the override for a date.
     *
     * Upsert rather than insert, because one date can only carry one override: saving
     * "closed for Christmas" twice is a correction, not a second holiday.
     */
    fun saveOverride(override: BusinessHourOverride): BusinessHourOverride = transaction {
        BusinessHourOverrides.deleteWhere {
            (BusinessHourOverrides.businessId eq override.businessId) and
                (BusinessHourOverrides.date eq override.date)
        }
        BusinessHourOverrides.insert {
            it[id] = override.id
            it[businessId] = override.businessId
            it[date] = override.date
            it[openTime] = override.openTime
            it[closeTime] = override.closeTime
            it[isClosed] = override.isClosed
            it[label] = override.label
            it[createdAt] = override.createdAt
        }
        override
    }

    fun deleteOverride(businessId: UUID, overrideId: UUID): Boolean = transaction {
        BusinessHourOverrides.deleteWhere {
            (BusinessHourOverrides.businessId eq businessId) and (BusinessHourOverrides.id eq overrideId)
        } > 0
    }

    /**
     * Overrides falling within a date range, for callers that only care about the week
     * on screen rather than every holiday the business has ever declared.
     */
    fun findOverridesBetween(
        businessId: UUID,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<BusinessHourOverride> = transaction {
        BusinessHourOverrides
            .select {
                (BusinessHourOverrides.businessId eq businessId) and
                    (BusinessHourOverrides.date greaterEq startDate) and
                    (BusinessHourOverrides.date lessEq endDate)
            }
            .orderBy(BusinessHourOverrides.date to SortOrder.ASC)
            .map { it.toOverride() }
    }

    private fun ResultRow.toDayHours() = BusinessDayHours(
        dayOfWeek = DayOfWeek.valueOf(this[BusinessHoursTable.dayOfWeek]),
        openTime = this[BusinessHoursTable.openTime],
        closeTime = this[BusinessHoursTable.closeTime],
        isClosed = this[BusinessHoursTable.isClosed],
        updatedAt = this[BusinessHoursTable.updatedAt]
    )

    private fun ResultRow.toOverride() = BusinessHourOverride(
        id = this[BusinessHourOverrides.id],
        businessId = this[BusinessHourOverrides.businessId],
        date = this[BusinessHourOverrides.date],
        openTime = this[BusinessHourOverrides.openTime],
        closeTime = this[BusinessHourOverrides.closeTime],
        isClosed = this[BusinessHourOverrides.isClosed],
        label = this[BusinessHourOverrides.label],
        createdAt = this[BusinessHourOverrides.createdAt]
    )
}
