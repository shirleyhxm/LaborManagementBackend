package org.labormanagement.dto

import org.labormanagement.model.EventPayOverride
import org.labormanagement.model.EventRuleOverrides
import org.labormanagement.model.EventStaffingRequirement
import org.labormanagement.model.OptimizationObjective
import org.labormanagement.model.SpecialEvent
import org.labormanagement.util.parseFlexibleTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private val WIRE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * What a client sends to create or replace an event.
 *
 * Times and dates cross the wire as strings so the client is never asked to guess at a
 * serialization format, and so "24:00" - the natural way to write a midnight close - is
 * accepted rather than rejected by LocalTime.
 */
data class SpecialEventRequest(
    val name: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val notes: String? = null,
    val employeeIds: List<String> = emptyList(),
    val expectedRevenue: Map<String, Double>? = null,
    val objective: String = OptimizationObjective.BALANCED.name,
    val requirements: List<EventStaffingRequirementDto> = emptyList(),
    val ruleOverrides: EventRuleOverridesDto? = null
)

/**
 * A staffing requirement on the wire.
 *
 * The two pay forms are separate nullable fields because JSON has no sealed types. At most
 * one may be set; the controller rejects a request carrying both rather than silently
 * picking one, since which the manager meant is unknowable and the wrong guess is payroll.
 */
data class EventStaffingRequirementDto(
    val groupName: String,
    val count: Int,
    val payRate: Double? = null,
    val payUplift: Double? = null
)

data class EventRuleOverridesDto(
    val minShiftLength: Double? = null,
    val maxShiftLength: Double? = null,
    val coverageFraction: Double? = null,
    val laborCostBudget: Double? = null
)

data class SpecialEventResponse(
    val id: String,
    val businessId: String,
    val name: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    /** The date the event finishes - the next day when it runs past midnight. */
    val endDate: String,
    val crossesMidnight: Boolean,
    val notes: String?,
    val employeeIds: List<String>,
    val expectedRevenue: Map<String, Double>?,
    val objective: String,
    val requirements: List<EventStaffingRequirementDto>,
    val ruleOverrides: EventRuleOverridesDto?,
    val scheduleId: String?,
    val createdAt: String,
    val createdBy: String
)

fun SpecialEvent.toResponse(): SpecialEventResponse = SpecialEventResponse(
    id = id.toString(),
    businessId = businessId.toString(),
    name = name,
    date = date.toString(),
    startTime = startTime.format(WIRE_TIME),
    endTime = endTime.format(WIRE_TIME),
    endDate = endDate.toString(),
    crossesMidnight = crossesMidnight,
    notes = notes,
    employeeIds = employeeIds.map { it.toString() },
    expectedRevenue = expectedRevenue?.mapKeys { (time, _) -> time.format(WIRE_TIME) },
    objective = objective.name,
    requirements = requirements.map { requirement ->
        EventStaffingRequirementDto(
            groupName = requirement.groupName,
            count = requirement.count,
            payRate = (requirement.payOverride as? EventPayOverride.AbsoluteRate)?.rate,
            payUplift = (requirement.payOverride as? EventPayOverride.Uplift)?.amountPerHour
        )
    },
    ruleOverrides = ruleOverrides?.let {
        EventRuleOverridesDto(it.minShiftLength, it.maxShiftLength, it.coverageFraction, it.laborCostBudget)
    },
    scheduleId = scheduleId?.toString(),
    createdAt = createdAt.toString(),
    createdBy = createdBy
)

/**
 * Build a domain event from a request.
 *
 * Throws [IllegalArgumentException] for anything malformed, which the controller turns into
 * a 400. Group names are *not* checked here - that needs the business's group list, so it
 * happens in the controller where the repository is available.
 */
fun SpecialEventRequest.toModel(
    businessId: UUID,
    id: UUID = UUID.randomUUID(),
    createdBy: String = "system",
    createdAt: Instant = Instant.now(),
    scheduleId: UUID? = null
): SpecialEvent {
    require(name.isNotBlank()) { "Event name is required" }

    val parsedDate = try {
        LocalDate.parse(date)
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid date '$date', expected YYYY-MM-DD")
    }

    val parsedStart = parseTimeOrFail(startTime, "startTime")
    val parsedEnd = parseTimeOrFail(endTime, "endTime")

    // An end at or before the start means the event runs past midnight, which is ordinary
    // for a late event and explicitly supported. The one thing it cannot mean is a
    // zero-length event, so an end identical to the start is rejected rather than read as
    // a full 24 hours - nobody schedules a party that way by intention.
    require(!(parsedStart == parsedEnd)) {
        "startTime and endTime are the same, so the event has no duration"
    }

    requirements.forEach { requirement ->
        require(requirement.groupName.isNotBlank()) { "Requirement group name is required" }
        require(requirement.count > 0) {
            "Requirement for '${requirement.groupName}' must be for at least one person"
        }
        require(!(requirement.payRate != null && requirement.payUplift != null)) {
            "Requirement for '${requirement.groupName}' sets both a pay rate and an uplift; " +
                "only one can apply"
        }
        require(requirement.payRate == null || requirement.payRate >= 0.0) {
            "Pay rate for '${requirement.groupName}' cannot be negative"
        }
    }

    val duplicateGroups = requirements.groupingBy { it.groupName.lowercase() }.eachCount()
        .filterValues { it > 1 }.keys
    require(duplicateGroups.isEmpty()) {
        "Duplicate requirements for group(s): ${duplicateGroups.joinToString()}"
    }

    ruleOverrides?.coverageFraction?.let {
        require(it > 0.0 && it <= 1.0) { "coverageFraction must be between 0 and 1" }
    }

    return SpecialEvent(
        id = id,
        businessId = businessId,
        name = name.trim(),
        date = parsedDate,
        startTime = parsedStart,
        endTime = parsedEnd,
        notes = notes,
        employeeIds = employeeIds.map { raw ->
            try {
                UUID.fromString(raw)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid employee id '$raw'")
            }
        },
        expectedRevenue = expectedRevenue?.mapKeys { (time, _) -> parseTimeOrFail(time, "expectedRevenue key") },
        objective = try {
            OptimizationObjective.valueOf(objective.uppercase())
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Invalid objective '$objective'. Valid values: ${OptimizationObjective.entries.joinToString()}"
            )
        },
        requirements = requirements.map { requirement ->
            EventStaffingRequirement(
                groupName = requirement.groupName.trim(),
                count = requirement.count,
                payOverride = when {
                    requirement.payRate != null -> EventPayOverride.AbsoluteRate(requirement.payRate)
                    requirement.payUplift != null -> EventPayOverride.Uplift(requirement.payUplift)
                    else -> null
                }
            )
        },
        ruleOverrides = ruleOverrides?.let {
            EventRuleOverrides(it.minShiftLength, it.maxShiftLength, it.coverageFraction, it.laborCostBudget)
        },
        scheduleId = scheduleId,
        createdAt = createdAt,
        createdBy = createdBy
    )
}

private fun parseTimeOrFail(value: String, field: String): LocalTime = try {
    parseFlexibleTime(value)
} catch (e: Exception) {
    throw IllegalArgumentException("Invalid $field '$value', expected HH:mm")
}
