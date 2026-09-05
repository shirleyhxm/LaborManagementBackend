package org.labormanagement.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * A one-off event a business puts on - a private hire, a collaboration night, a ticketed
 * party - that is staffed differently from an ordinary trading day.
 *
 * This is the manager's *definition* of the event, which is deliberately not the same thing
 * as the schedule generated from it. The definition is edited and re-generated from; the
 * schedule it produces is an ordinary [Schedule] carrying [ScheduleKind.EVENT], so it reuses
 * the whole generation, lifecycle and editing pipeline rather than growing a parallel one.
 *
 * Everything an event can differ in is held here rather than read from the business:
 * its own hours, its own staff, its own forecast, its own objective, and a narrow set of
 * rule overrides. Anything left unset falls back to the business's configuration at
 * generation time - stored as null rather than as a copied value, so a later change to the
 * business's rules still reaches events that never overrode them.
 */
data class SpecialEvent(
    val id: UUID = UUID.randomUUID(),
    val businessId: UUID,
    val name: String,
    val date: LocalDate,

    /**
     * The event window. [endTime] may be earlier than [startTime], meaning the event runs
     * past midnight into the following day - a 21:00-02:00 party is the ordinary case here,
     * not an edge case.
     */
    val startTime: LocalTime,
    val endTime: LocalTime,

    val notes: String? = null,

    /** Who may be scheduled. Empty means every schedulable employee is a candidate. */
    val employeeIds: List<UUID> = emptyList(),

    /**
     * Expected revenue by hour, replacing the business's weekly pattern for this window.
     *
     * Null means the business forecast applies. That is rarely what an event wants: a
     * private hire generates little or no till revenue while still needing a full team, and
     * demand derived from the ordinary pattern would staff it as a quiet evening.
     */
    val expectedRevenue: Map<LocalTime, Double>? = null,

    val objective: OptimizationObjective = OptimizationObjective.BALANCED,

    val requirements: List<EventStaffingRequirement> = emptyList(),

    /** Rule overrides for this event alone. Null fields inherit the business rule. */
    val ruleOverrides: EventRuleOverrides? = null,

    /** The schedule generated from this definition, once one exists. */
    val scheduleId: UUID? = null,

    val createdAt: Instant = Instant.now(),
    val createdBy: String = "system"
) {
    /** True when the event runs past midnight into the next day. */
    val crossesMidnight: Boolean get() = endTime <= startTime

    /** The last date the event touches - the next day when it runs past midnight. */
    val endDate: LocalDate get() = if (crossesMidnight) date.plusDays(1) else date
}

/**
 * How many people from one employee group the event needs, and what they are paid for it.
 *
 * Keyed by group name rather than by employee: a manager staffing a party thinks "two
 * bartenders and a doorman", not in terms of named individuals. [groupName] is validated
 * against the business's own groups on write - an unrecognised name would match nobody and
 * generate a silently understaffed event.
 */
data class EventStaffingRequirement(
    val groupName: String,
    val count: Int,
    val payOverride: EventPayOverride? = null
)

/**
 * A pay arrangement that applies only for the duration of an event.
 *
 * Sealed rather than a pair of nullable numbers so that exactly one form is set: "both a
 * rate and an uplift" has no meaning, and a shape that can express it invites a caller to
 * do so and a reader to wonder which wins.
 *
 * **Interaction with overtime**, which is easy to leave implicit and expensive to get wrong:
 * an [Uplift] is added to the base rate *before* the overtime multiplier applies, so
 * overtime is paid on the uplifted rate. An [AbsoluteRate] *replaces* the base rate, with
 * the employee's usual overtime multiplier still applying on top of it.
 */
sealed class EventPayOverride {
    /** Pay this rate for the event instead of the employee's normal rate. */
    data class AbsoluteRate(val rate: Double) : EventPayOverride()

    /** Add this much per hour to the employee's normal rate for the event. */
    data class Uplift(val amountPerHour: Double) : EventPayOverride()
}

/**
 * The rules an event is allowed to bend, and by omission the ones it is not.
 *
 * Statutory limits are deliberately absent: weekly hour caps, rest between shifts,
 * consecutive days, weekly rest and meal breaks are about how much a person may safely work
 * and do not become negotiable because the work is a party. An event that would breach them
 * is generated anyway and flagged, rather than quietly being allowed to.
 *
 * Every field is null by default, meaning "inherit whatever the business has configured".
 */
data class EventRuleOverrides(
    val minShiftLength: Double? = null,
    val maxShiftLength: Double? = null,
    /** What fraction of projected demand to cover. Events often want all of it. */
    val coverageFraction: Double? = null,
    /**
     * A wage cap for this event alone.
     *
     * Worth setting whenever the business runs a hard budget: the business cap is pro-rated
     * from a weekly figure, and pro-rating a week's budget down to a five-hour event yields
     * a cap far below what staffing it actually costs.
     */
    val laborCostBudget: Double? = null
)
