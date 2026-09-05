package org.labormanagement.service

import org.labormanagement.model.EventContext
import org.labormanagement.model.OperatingHours
import org.labormanagement.model.Schedule
import org.labormanagement.model.ScheduleInput
import org.labormanagement.model.ScheduleKind
import org.labormanagement.model.SchedulePeriod
import org.labormanagement.model.SpecialEvent
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.ScheduleRepository
import org.labormanagement.repository.SpecialEventRepository
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Turns a special event definition into a schedule.
 *
 * Deliberately thin. An event schedule is an ordinary [Schedule] that happens to span a few
 * hours, so this translates the definition into a [ScheduleInput] and hands it to the same
 * [ShiftScheduler] that builds every other schedule. Everything downstream - the solver, the
 * lifecycle, editing, publishing, undo - is reused rather than reimplemented, which is the
 * whole reason events were modelled as schedules in the first place.
 */
class EventScheduler(
    private val shiftScheduler: ShiftScheduler = ShiftScheduler(),
    private val specialEventRepository: SpecialEventRepository = SpecialEventRepository(),
    private val employeeRepository: EmployeeRepository = EmployeeRepository(),
    private val scheduleRepository: ScheduleRepository = ScheduleRepository()
) {
    private val log = LoggerFactory.getLogger(EventScheduler::class.java)

    /**
     * Generate (or re-generate) the schedule for an event, and link it back to the definition.
     *
     * Re-generating discards the previous schedule. That is the point: the definition is the
     * lasting thing and the schedule is derived from it, so a manager who edits an event and
     * generates again expects to see the new arrangement rather than to accumulate drafts.
     *
     * Returns null when no such event exists for the business.
     */
    fun generateForEvent(businessId: UUID, eventId: UUID, generatedBy: String = "system"): Schedule? {
        val event = specialEventRepository.findById(businessId, eventId) ?: return null

        // The previous schedule is removed first. Left in place it would keep its own shifts
        // counting against everyone's weekly hours, so the replacement would be generated as
        // though the staff were already committed to the event it is replacing.
        event.scheduleId?.let { existing ->
            runCatching { scheduleRepository.forceDelete(existing) }
                .onFailure { log.warn("Could not remove the previous schedule for event $eventId", it) }
        }

        val schedule = shiftScheduler.generateSchedule(
            input = toScheduleInput(businessId, event),
            name = event.name,
            generatedBy = generatedBy,
            businessId = businessId,
            kind = ScheduleKind.EVENT,
            eventContext = EventContext(
                expectedRevenue = event.expectedRevenue,
                requirements = event.requirements,
                ruleOverrides = event.ruleOverrides
            )
        )

        specialEventRepository.linkSchedule(businessId, eventId, schedule.id)
        return schedule
    }

    private fun toScheduleInput(businessId: UUID, event: SpecialEvent): ScheduleInput {
        // An empty pool means everyone is a candidate, which is what a manager who has not
        // narrowed it down intends - not that nobody may work.
        val employeeIds = event.employeeIds.ifEmpty {
            employeeRepository.findAllByBusiness(businessId).map { it.id }
        }

        return ScheduleInput(
            businessId = businessId,
            employeeIds = employeeIds,
            // Overwritten by ShiftScheduler, which prefers the event's own budget over the
            // business one pro-rated down to a few hours.
            laborCostBudget = Double.MAX_VALUE,
            schedulePeriod = eventPeriod(event),
            optimizationObjective = event.objective
        )
    }

    /**
     * The period an event occupies.
     *
     * A single date even when the event runs past midnight: operating hours carry a close
     * earlier than their open to mean exactly that, and slot generation reads it correctly.
     * Spanning two dates instead would open the following day from midnight and invite the
     * solver to staff a morning nobody is holding an event in.
     */
    private fun eventPeriod(event: SpecialEvent) = SchedulePeriod(
        startDate = event.date,
        endDate = event.date,
        operatingHours = mapOf(event.date to OperatingHours(event.startTime, event.endTime))
    )
}
