package org.labormanagement.optimization

import org.labormanagement.model.Employee
import org.labormanagement.model.OptimizationObjective
import org.labormanagement.model.OvertimeSplitter
import org.labormanagement.model.SalesForecast
import org.labormanagement.model.Shift
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Converts between domain models (Employee, SalesForecast, etc.) and optimization model inputs/outputs.
 */
object OptimizationConverter {

    /**
     * Builds OptimizationInput from domain models.
     *
     * @param employees List of employees to schedule
     * @param salesForecast Sales forecast data for the week
     * @param scheduleDates Dates to include in the schedule
     * @param operatingHoursMap Operating hours for each date
     * @param coverageFraction What fraction of projected sales should be covered (default: 0.8)
     * @param objective Optimization objective (default: MINIMIZE_LABOR_COST)
     * @param constraintsService Optional ConstraintsService to fetch scheduling constraints
     * @param timeoffExclusions Per-employee dates excluded due to an APPROVED timeoff
     *   request - treated the same as having no availability that day
     * @return OptimizationInput ready for the solver
     */
    fun buildOptimizationInput(
        employees: List<Employee>,
        salesForecast: SalesForecast,
        scheduleDates: List<LocalDate>,
        operatingHoursMap: Map<LocalDate, Pair<LocalTime, LocalTime>>,
        coverageFraction: Double = 0.8,
        objective: OptimizationObjective = OptimizationObjective.MINIMIZE_LABOR_COST,
        maxSolveTimeSeconds: Double = 5.0,
        constraintsService: org.labormanagement.service.ConstraintsService? = null,
        businessId: java.util.UUID,
        timeoffExclusions: Map<UUID, Set<LocalDate>> = emptyMap(),
        shiftsElsewhere: List<org.labormanagement.model.Shift> = emptyList()
    ): OptimizationInput {
        // Fetch constraints from ConstraintsService if provided
        val budgetConstraints = constraintsService?.getBudgetConstraints(businessId)
        val workingHoursRules = constraintsService?.getWorkingHoursRules(businessId)
        val complianceRules = constraintsService?.getComplianceRules(businessId)
        val fairnessSettings = constraintsService?.getFairnessSettings(businessId)

        // An employee can have multiple effective-dated contracted-hours rows
        // (e.g. a past rule and a current/future one). Pick the row actually in
        // effect for this schedule's start date, not just "whichever one sorts
        // last" - grouping by employeeId first, then filtering each employee's
        // rows by date, avoids picking a future or expired row.
        val scheduleStartDate = scheduleDates.minOrNull()
        val contractedHoursMap = if (scheduleStartDate != null) {
            constraintsService?.getContractedHours(businessId, null)
                ?.groupBy { it.employeeId }
                ?.mapNotNull { (employeeId, rows) ->
                    val active = rows.firstOrNull { row ->
                        !scheduleStartDate.isBefore(row.effectiveFrom) &&
                            (row.effectiveTo == null || !scheduleStartDate.isAfter(row.effectiveTo))
                    }
                    active?.let { employeeId to it }
                }
                ?.toMap() ?: emptyMap()
        } else {
            emptyMap()
        }

        // The solver's hard budget cap comes from the business's saved wage
        // budgets, pro-rated to the schedule's actual length, rather than a
        // value the caller passes in ad hoc. Only enforced when
        // hardBudgetLimit is on - otherwise the budgets are reporting figures
        // only and generation isn't capped by them.
        val laborBudget = resolveLaborCostBudget(budgetConstraints, scheduleDates.size)
            .let { if (it == Double.MAX_VALUE) Long.MAX_VALUE else it.toLong() }

        // Generate time slots for all scheduled dates
        val timeSlots = generateTimeSlots(scheduleDates, operatingHoursMap)

        // Build availability matrix [employee][timeSlot]
        val availability = buildAvailabilityMatrix(employees, timeSlots, timeoffExclusions, shiftsElsewhere)

        // Hours each employee has already committed at other locations in this
        // window. The weekly cap is one budget for the person, not one per
        // location, so the solver only gets to allocate what is left of it.
        val hoursCommittedElsewhere = shiftsElsewhere
            .groupBy { it.employeeId }
            .mapValues { (_, shifts) ->
                shifts.sumOf { shift ->
                    java.time.Duration.between(shift.startTime, shift.endTime).toMinutes() / 60.0
                }
            }

        // Build productivity matrix [employee][timeSlot]
        val productivity = buildProductivityMatrix(employees, timeSlots)

        // Extract projected sales for each time slot
        val projectedSales = extractProjectedSales(salesForecast, timeSlots)

        return OptimizationInput(
            employees = employees,
            timeSlots = timeSlots,
            projectedSales = projectedSales,
            availability = availability,
            productivity = productivity,
            coverageFraction = coverageFraction,
            laborBudget = laborBudget,
            objective = objective,
            maxSolveTimeSeconds = maxSolveTimeSeconds,
            budgetConstraints = budgetConstraints,
            workingHoursRules = workingHoursRules,
            complianceRules = complianceRules,
            fairnessSettings = fairnessSettings,
            contractedHours = contractedHoursMap,
            hoursCommittedElsewhere = hoursCommittedElsewhere
        )
    }

    /**
     * Resolves the hard wage-cost cap for a schedule of [scheduleDayCount]
     * days from the business's saved budget constraints.
     *
     * Both the weekly and the monthly budget are pro-rated to the schedule's
     * actual length (schedules aren't always exactly 7 or 30 days) and the
     * lower - i.e. the binding - of the two is used. Taking the minimum means
     * a schedule can't satisfy a generous weekly budget while blowing through
     * a tighter monthly one, which is the whole reason a manager would set
     * both.
     *
     * A budget of zero is ambiguous: it's both how an unset budget is stored
     * and how a manager would express "spend nothing". It's read as unset
     * only when the *other* budget carries a real value, so that a business
     * which has configured just one of the two isn't pinned to no spend at
     * all by the empty one. When every budget is zero under a hard limit
     * there's nothing else to defer to, so it's taken at face value as a
     * genuine zero cap.
     *
     * Returns Double.MAX_VALUE (uncapped) when there are no budget
     * constraints, when hardBudgetLimit is off, or when the schedule has no
     * days.
     */
    fun resolveLaborCostBudget(
        budgetConstraints: org.labormanagement.model.BudgetConstraints?,
        scheduleDayCount: Int
    ): Double {
        if (budgetConstraints == null || !budgetConstraints.hardBudgetLimit) return Double.MAX_VALUE
        if (scheduleDayCount <= 0) return Double.MAX_VALUE

        val weeklyCap = budgetConstraints.weeklyBudget / 7.0 * scheduleDayCount
        val monthlyCap = budgetConstraints.monthlyBudget / 30.0 * scheduleDayCount

        val configuredCaps = listOfNotNull(
            weeklyCap.takeIf { budgetConstraints.weeklyBudget > 0 },
            monthlyCap.takeIf { budgetConstraints.monthlyBudget > 0 }
        )

        // No budget carries a real value - an explicit "spend nothing".
        return configuredCaps.minOrNull() ?: 0.0
    }

    /**
     * Converts optimization result into Shift objects.
     */
    fun convertToShifts(
        result: OptimizationResult,
        input: OptimizationInput
    ): List<Shift> {
        val shifts = mutableListOf<Shift>()

        // Track cumulative hours per employee to determine overtime correctly.
        //
        // Seeded with hours already worked at other locations that week, not
        // zero: the overtime threshold is reached across every location someone
        // works at, so a second location's first hour can already be overtime.
        // Starting from zero would bill those hours at the regular rate and
        // quietly under-pay them.
        val employeeHours = input.employees.indices.associateWith { index ->
            input.hoursCommittedElsewhere[input.employees[index].id] ?: 0.0
        }.toMutableMap()

        for (assignment in result.assignments) {
            val employee = input.employees[assignment.employeeIndex]

            // Group consecutive time slots into continuous shifts
            val consecutiveSlotGroups = groupConsecutiveSlots(assignment.timeSlotIndices, input.timeSlots)

            for (slotGroup in consecutiveSlotGroups) {
                val startSlot = input.timeSlots[slotGroup.first()]
                val endSlot = input.timeSlots[slotGroup.last()]

                // Calculate total hours for this shift group
                val totalHours = slotGroup.sumOf { input.timeSlots[it].durationHours }

                // Get current cumulative hours for this employee
                val currentHours = employeeHours.getOrDefault(assignment.employeeIndex, 0.0)

                shifts.addAll(
                    OvertimeSplitter.split(
                        employee = employee,
                        date = startSlot.date,
                        startTime = startSlot.startTime,
                        endTime = endSlot.endTime,
                        hoursBefore = currentHours,
                        blockDurationHours = totalHours
                    )
                )

                // Update cumulative hours for this employee AFTER creating the shift(s)
                employeeHours[assignment.employeeIndex] = currentHours + totalHours
            }
        }

        return shifts
    }

    /**
     * Generates time slots for the given dates and operating hours.
     */
    private fun generateTimeSlots(
        dates: List<LocalDate>,
        operatingHoursMap: Map<LocalDate, Pair<LocalTime, LocalTime>>,
    ): List<TimeSlot> {
        val timeSlots = mutableListOf<TimeSlot>()

        // Use 1-hour time slots for granular scheduling
        // The minimum shift length will be enforced as a constraint in the optimizer
        val slotDurationHours = 1.0

        val slotDurationMinutes = (slotDurationHours * 60).toLong()

        for (date in dates) {
            val (openTime, closeTime) = operatingHoursMap[date] ?: continue

            // Walk elapsed minutes from opening rather than comparing times of day. A
            // business open 21:00-02:00 closes at a LocalTime *smaller* than the one it
            // opened at, so `while (current < close)` ends before it starts and yields no
            // slots at all - the solver is then handed nothing to assign and returns an
            // empty schedule with no error to explain it.
            val minutesOpen = minutesBetweenAllowingWrap(openTime, closeTime)
            if (minutesOpen <= 0) continue

            var elapsed = 0L
            while (elapsed < minutesOpen) {
                val slotMinutes = minOf(slotDurationMinutes, minutesOpen - elapsed)

                // Offsetting the opening instant keeps this correct across midnight:
                // LocalTime wraps on its own, and the date advances with it.
                val start = openTime.plusMinutes(elapsed)
                val end = openTime.plusMinutes(elapsed + slotMinutes)
                val startsNextDay = (openTime.toSecondOfDay() / 60L + elapsed) >= MINUTES_PER_DAY

                timeSlots.add(
                    TimeSlot(
                        date = if (startsNextDay) date.plusDays(1) else date,
                        startTime = start,
                        endTime = end,
                        durationHours = slotMinutes / 60.0,
                        businessDate = date
                    )
                )

                elapsed += slotMinutes
            }
        }

        return timeSlots
    }

    private const val MINUTES_PER_DAY = 24 * 60L

    /**
     * Minutes from [open] to [close], reading a close that is not after the open as
     * belonging to the following day.
     *
     * A close equal to the open is the ambiguous case: it means a full 24 hours, not a
     * zero-length day, since a business that opens and closes at the same moment is one
     * that never shuts.
     */
    private fun minutesBetweenAllowingWrap(open: LocalTime, close: LocalTime): Long {
        val openMinute = open.toSecondOfDay() / 60L
        val closeMinute = close.toSecondOfDay() / 60L
        return if (closeMinute > openMinute) {
            closeMinute - openMinute
        } else {
            MINUTES_PER_DAY - openMinute + closeMinute
        }
    }

    /**
     * Builds availability matrix indicating which employees are available for which time slots.
     *
     * A slot is unavailable if the employee's recurring availability doesn't cover it,
     * if they have an APPROVED timeoff request covering that date, or if they are already
     * working that time at another location - someone assigned to several locations is
     * still one person who can only be in one place at once.
     */
    private fun buildAvailabilityMatrix(
        employees: List<Employee>,
        timeSlots: List<TimeSlot>,
        timeoffExclusions: Map<UUID, Set<LocalDate>> = emptyMap(),
        shiftsElsewhere: List<org.labormanagement.model.Shift> = emptyList()
    ): List<List<Boolean>> {
        val shiftsByEmployee = shiftsElsewhere.groupBy { it.employeeId }

        return employees.map { employee ->
            val excludedDates = timeoffExclusions[employee.id] ?: emptySet()
            val committed = shiftsByEmployee[employee.id] ?: emptyList()

            timeSlots.map { slot ->
                val availableInPrinciple = slot.date !in excludedDates &&
                    employee.availability.any { avail ->
                        avail.isAvailableOn(slot.date, slot.startTime, slot.endTime)
                    }

                // Half-open overlap: a shift ending exactly when this slot
                // starts does not collide with it.
                val alreadyWorking = committed.any { shift ->
                    shift.date == slot.date &&
                        shift.startTime < slot.endTime &&
                        shift.endTime > slot.startTime
                }

                availableInPrinciple && !alreadyWorking
            }
        }
    }

    /**
     * Builds productivity matrix for each employee at each time slot.
     * Currently uses constant productivity, but could be extended to vary by time of day.
     */
    private fun buildProductivityMatrix(
        employees: List<Employee>,
        timeSlots: List<TimeSlot>
    ): List<List<Double>> {
        return employees.map { employee ->
            timeSlots.map { slot ->
                // Productivity is per hour, multiply by slot duration
                employee.productivity * slot.durationHours
            }
        }
    }

    /**
     * Extracts projected sales for each time slot from the sales forecast.
     */
    private fun extractProjectedSales(
        salesForecast: SalesForecast,
        timeSlots: List<TimeSlot>
    ): List<Double> {
        return timeSlots.map { slot ->
            val dayForecast = salesForecast.getForecastForDate(slot.date)

            // Find forecasts that fall within this time slot.
            //
            // Compared as minutes from the slot's start rather than as times of day: a
            // slot ending at midnight has an endTime of 00:00, and "before 00:00" is
            // false for every hour there is. The 23:00-00:00 slot would draw no demand
            // at all, so the one hour before closing was never staffed - a gap in the
            // middle of the night that looked like the solver simply declining to fill it.
            val slotMinutes = (slot.durationHours * 60).toLong()
            val slotStart = slot.startTime.toSecondOfDay() / 60L
            val relevantForecasts = dayForecast.filter { (time, _) ->
                val offset = (time.toSecondOfDay() / 60L - slotStart + MINUTES_PER_DAY) % MINUTES_PER_DAY
                offset < slotMinutes
            }

            // Sum or average the forecasts for this slot
            if (relevantForecasts.isEmpty()) {
                0.0
            } else {
                relevantForecasts.values.sum()
            }
        }
    }

    /**
     * Groups consecutive time slot indices into continuous ranges within the same date.
     * For example: [0, 1, 2, 5, 6, 8] -> [[0, 1, 2], [5, 6], [8]]
     * Ensures that slots from different dates are never grouped together.
     */
    private fun groupConsecutiveSlots(indices: List<Int>, timeSlots: List<TimeSlot>): List<List<Int>> {
        if (indices.isEmpty()) return emptyList()

        val sorted = indices.sorted()
        val groups = mutableListOf<MutableList<Int>>()
        var currentGroup = mutableListOf(sorted[0])

        for (i in 1 until sorted.size) {
            val prevSlot = timeSlots[sorted[i - 1]]
            val currSlot = timeSlots[sorted[i]]

            // Check if consecutive AND part of the same opening AND times align.
            //
            // Compared on businessDate rather than the calendar date, so a block running
            // past midnight stays one shift. On the calendar date the 23:00-00:00 and
            // 00:00-01:00 slots of one night look like different days, and a continuous
            // stretch of work would be broken into two shifts at midnight.
            val isConsecutive = sorted[i] == sorted[i - 1] + 1
            val isSameOpening = prevSlot.businessDate == currSlot.businessDate
            val timesAlign = prevSlot.endTime == currSlot.startTime

            if (isConsecutive && isSameOpening && timesAlign) {
                // Consecutive - add to current group
                currentGroup.add(sorted[i])
            } else {
                // Not consecutive - start new group
                groups.add(currentGroup)
                currentGroup = mutableListOf(sorted[i])
            }
        }

        // Add the last group
        groups.add(currentGroup)

        return groups
    }
}
