package org.labormanagement.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * When the business is open on one day of an ordinary week.
 *
 * [closeTime] may be earlier than [openTime], meaning the day runs past midnight - the
 * same convention SpecialEvent uses for an event that ends in the small hours.
 *
 * A closed day keeps its times rather than nulling them: reopening Sunday should restore
 * the hours it last had, not make the owner retype them.
 */
data class BusinessDayHours(
    val dayOfWeek: DayOfWeek,
    val openTime: LocalTime = LocalTime.of(9, 0),
    val closeTime: LocalTime = LocalTime.of(21, 0),
    val isClosed: Boolean = false,
    val updatedAt: Instant = Instant.now()
) {
    /** The open window, or null on a day the business is shut. */
    fun toOperatingHours(): OperatingHours? =
        if (isClosed) null else OperatingHours(openTime, closeTime)
}

/**
 * A date whose hours differ from the weekly pattern - a bank holiday closure, or a day
 * opening late for stocktake.
 *
 * [label] exists so the UI can say *why* a day is shut: "Christmas Day" reads differently
 * from the silent fact that the business never opens on Sundays, and a manager looking at
 * an empty day deserves to know which one they are looking at.
 */
data class BusinessHourOverride(
    val id: UUID = UUID.randomUUID(),
    val businessId: UUID,
    val date: LocalDate,
    val openTime: LocalTime = LocalTime.of(9, 0),
    val closeTime: LocalTime = LocalTime.of(21, 0),
    val isClosed: Boolean = false,
    val label: String? = null,
    val createdAt: Instant = Instant.now()
) {
    fun toOperatingHours(): OperatingHours? =
        if (isClosed) null else OperatingHours(openTime, closeTime)
}

/**
 * A business's complete opening hours: the ordinary week, plus the dates that depart
 * from it.
 *
 * Resolution order is date override, then weekly day, then [fallback] - the legacy
 * BusinessSettings.defaultOpenTime/defaultCloseTime pair, which is all a business has
 * until someone saves hours for the first time. Keeping the fallback here rather than at
 * the call site means every caller resolves a date the same way; the scheduler and the
 * API cannot drift into disagreeing about when a business is open.
 */
data class BusinessHours(
    val businessId: UUID,
    val week: List<BusinessDayHours> = emptyList(),
    val overrides: List<BusinessHourOverride> = emptyList(),
    val fallback: OperatingHours = OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
) {
    private val byDay: Map<DayOfWeek, BusinessDayHours> = week.associateBy { it.dayOfWeek }
    private val byDate: Map<LocalDate, BusinessHourOverride> = overrides.associateBy { it.date }

    /**
     * When the business is open on [date], or null if it is shut that day.
     *
     * Null is a real answer, not a missing one: a closed day produces no shifts, which is
     * different from a day whose hours nobody has configured (that falls back).
     */
    fun resolve(date: LocalDate): OperatingHours? {
        byDate[date]?.let { return it.toOperatingHours() }
        byDay[date.dayOfWeek]?.let { return it.toOperatingHours() }
        return fallback
    }

    /** Whether the business is shut on [date]. */
    fun isClosedOn(date: LocalDate): Boolean = resolve(date) == null

    companion object {
        /**
         * A full week at [hours], used to seed a business that has never configured
         * anything so it starts from the hours it was already generating against.
         */
        fun defaultWeek(hours: OperatingHours): List<BusinessDayHours> =
            DayOfWeek.entries.map {
                BusinessDayHours(
                    dayOfWeek = it,
                    openTime = hours.openTime,
                    closeTime = hours.closeTime,
                    isClosed = false
                )
            }
    }
}
