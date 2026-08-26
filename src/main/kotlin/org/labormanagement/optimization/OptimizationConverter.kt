package org.labormanagement.optimization

import org.labormanagement.model.Employee
import org.labormanagement.model.OptimizationObjective
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
        timeoffExclusions: Map<UUID, Set<LocalDate>> = emptyMap()
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
        val availability = buildAvailabilityMatrix(employees, timeSlots, timeoffExclusions)

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
            contractedHours = contractedHoursMap
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

        // Track cumulative hours per employee to determine overtime correctly
        val employeeHours = mutableMapOf<Int, Double>()

        for (assignment in result.assignments) {
            val employee = input.employees[assignment.employeeIndex]
            val overtimeThreshold = employee.contract.overtimeThreshold

            // Group consecutive time slots into continuous shifts
            val consecutiveSlotGroups = groupConsecutiveSlots(assignment.timeSlotIndices, input.timeSlots)

            for (slotGroup in consecutiveSlotGroups) {
                val startSlot = input.timeSlots[slotGroup.first()]
                val endSlot = input.timeSlots[slotGroup.last()]

                // Calculate total hours for this shift group
                val totalHours = slotGroup.sumOf { input.timeSlots[it].durationHours }

                // Get current cumulative hours for this employee
                val currentHours = employeeHours.getOrDefault(assignment.employeeIndex, 0.0)

                // Check if this shift crosses the overtime threshold
                val hoursAfterShift = currentHours + totalHours

                if (currentHours < overtimeThreshold && hoursAfterShift > overtimeThreshold) {
                    // Shift crosses the overtime threshold - split it into two shifts
                    val regularHours = overtimeThreshold - currentHours

                    // Calculate the time when overtime starts
                    val overtimeStartMinutes = (regularHours * 60).toLong()
                    val overtimeStartTime = startSlot.startTime.plusMinutes(overtimeStartMinutes)

                    // Create regular pay shift (before threshold)
                    shifts.add(
                        Shift(
                            employeeId = employee.id,
                            date = startSlot.date,
                            startTime = startSlot.startTime,
                            endTime = overtimeStartTime,
                            payRate = employee.normalPayRate,
                            isOvertime = false
                        )
                    )

                    // Create overtime pay shift (after threshold)
                    shifts.add(
                        Shift(
                            employeeId = employee.id,
                            date = endSlot.date,
                            startTime = overtimeStartTime,
                            endTime = endSlot.endTime,
                            payRate = employee.overtimePayRate,
                            isOvertime = true
                        )
                    )
                } else {
                    // Shift does not cross threshold - create single shift
                    val isOvertime = currentHours >= overtimeThreshold
                    val payRate = if (isOvertime) employee.overtimePayRate else employee.normalPayRate

                    shifts.add(
                        Shift(
                            employeeId = employee.id,
                            date = startSlot.date,
                            startTime = startSlot.startTime,
                            endTime = endSlot.endTime,
                            payRate = payRate,
                            isOvertime = isOvertime
                        )
                    )
                }

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

        for (date in dates) {
            val (openTime, closeTime) = operatingHoursMap[date] ?: continue

            var currentTime = openTime
            val slotDurationMinutes = (slotDurationHours * 60).toLong()

            while (currentTime < closeTime) {
                val slotEnd = currentTime.plusMinutes(slotDurationMinutes)
                val actualEnd = if (slotEnd > closeTime) closeTime else slotEnd

                val actualDuration = ChronoUnit.MINUTES.between(currentTime, actualEnd) / 60.0

                timeSlots.add(
                    TimeSlot(
                        date = date,
                        startTime = currentTime,
                        endTime = actualEnd,
                        durationHours = actualDuration
                    )
                )

                currentTime = actualEnd
            }
        }

        return timeSlots
    }

    /**
     * Builds availability matrix indicating which employees are available for which time slots.
     * A slot is unavailable if the employee's recurring availability doesn't cover it, OR if
     * they have an APPROVED timeoff request covering that date.
     */
    private fun buildAvailabilityMatrix(
        employees: List<Employee>,
        timeSlots: List<TimeSlot>,
        timeoffExclusions: Map<UUID, Set<LocalDate>> = emptyMap()
    ): List<List<Boolean>> {
        return employees.map { employee ->
            val excludedDates = timeoffExclusions[employee.id] ?: emptySet()
            timeSlots.map { slot ->
                slot.date !in excludedDates && employee.availability.any { avail ->
                    avail.isAvailableOn(slot.date, slot.startTime, slot.endTime)
                }
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

            // Find forecasts that fall within this time slot
            val relevantForecasts = dayForecast.filter { (time, _) ->
                !time.isBefore(slot.startTime) && time.isBefore(slot.endTime)
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

            // Check if consecutive AND on the same date AND times align
            val isConsecutive = sorted[i] == sorted[i - 1] + 1
            val isSameDate = prevSlot.date == currSlot.date
            val timesAlign = prevSlot.endTime == currSlot.startTime

            if (isConsecutive && isSameDate && timesAlign) {
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
