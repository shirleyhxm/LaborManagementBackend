package org.labormanagement.optimization

import org.labormanagement.model.Employee
import org.labormanagement.model.OptimizationObjective
import org.labormanagement.model.SalesForecast
import org.labormanagement.model.Shift
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Converts between domain models (Employee, SalesForecast, etc.) and optimization model inputs/outputs.
 */
object OptimizationConverter {

    /**
     * Builds OptimizationInput from domain models.
     *
     * @param employees List of employees to schedule
     * @param salesForecast Sales forecast data for the week
     * @param scheduleDays Days to include in the schedule
     * @param slotDurationHours Duration of each time slot in hours (default: 1.0)
     * @param coverageFraction What fraction of projected sales should be covered (default: 0.8)
     * @param laborBudget Maximum labor budget (default: Long.MAX_VALUE)
     * @param objective Optimization objective (default: MINIMIZE_LABOR_COST)
     * @return OptimizationInput ready for the solver
     */
    fun buildOptimizationInput(
        employees: List<Employee>,
        salesForecast: SalesForecast,
        scheduleDays: List<DayOfWeek>,
        operatingHoursMap: Map<DayOfWeek, Pair<LocalTime, LocalTime>>,
        slotDurationHours: Double = 1.0,
        coverageFraction: Double = 0.8,
        laborBudget: Long = Long.MAX_VALUE,
        objective: OptimizationObjective = OptimizationObjective.MINIMIZE_LABOR_COST,
        maxSolveTimeSeconds: Double = 5.0
    ): OptimizationInput {
        // Generate time slots for all scheduled days
        val timeSlots = generateTimeSlots(scheduleDays, operatingHoursMap, slotDurationHours)

        // Build availability matrix [employee][timeSlot]
        val availability = buildAvailabilityMatrix(employees, timeSlots)

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
            maxSolveTimeSeconds = maxSolveTimeSeconds
        )
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
            val currentHours = employeeHours.getOrDefault(assignment.employeeIndex, 0.0)

            // Group consecutive time slots into continuous shifts
            val consecutiveSlotGroups = groupConsecutiveSlots(assignment.timeSlotIndices, input.timeSlots)

            for (slotGroup in consecutiveSlotGroups) {
                val startSlot = input.timeSlots[slotGroup.first()]
                val endSlot = input.timeSlots[slotGroup.last()]

                // Calculate total hours for this shift group
                val totalHours = slotGroup.sumOf { input.timeSlots[it].durationHours }

                // Determine if this shift is overtime based on hours BEFORE this shift starts
                // This ensures the first shifts are normal rate, and only later shifts become overtime
                val hoursBeforeThisShift = currentHours
                val isOvertime = hoursBeforeThisShift >= employee.contract.overtimeThreshold
                val payRate = if (isOvertime) employee.overtimePayRate else employee.normalPayRate

                shifts.add(
                    Shift(
                        employeeId = employee.id,
                        dayOfWeek = startSlot.day,
                        startTime = startSlot.startTime,
                        endTime = endSlot.endTime,
                        payRate = payRate,
                        isOvertime = isOvertime
                    )
                )

                // Update cumulative hours for this employee
                employeeHours[assignment.employeeIndex] = currentHours + totalHours
            }
        }

        return shifts
    }

    /**
     * Generates time slots for the given days and operating hours.
     */
    private fun generateTimeSlots(
        days: List<DayOfWeek>,
        operatingHoursMap: Map<DayOfWeek, Pair<LocalTime, LocalTime>>,
        slotDurationHours: Double
    ): List<TimeSlot> {
        val timeSlots = mutableListOf<TimeSlot>()

        for (day in days) {
            val (openTime, closeTime) = operatingHoursMap[day] ?: continue

            var currentTime = openTime
            val slotDurationMinutes = (slotDurationHours * 60).toLong()

            while (currentTime < closeTime) {
                val slotEnd = currentTime.plusMinutes(slotDurationMinutes)
                val actualEnd = if (slotEnd > closeTime) closeTime else slotEnd

                val actualDuration = ChronoUnit.MINUTES.between(currentTime, actualEnd) / 60.0

                timeSlots.add(
                    TimeSlot(
                        day = day,
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
     */
    private fun buildAvailabilityMatrix(
        employees: List<Employee>,
        timeSlots: List<TimeSlot>
    ): List<List<Boolean>> {
        return employees.map { employee ->
            timeSlots.map { slot ->
                employee.availability.any { avail ->
                    avail.dayOfWeek == slot.day &&
                    avail.startTime <= slot.startTime &&
                    avail.endTime >= slot.endTime
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
            val dayForecast = salesForecast.weeklyForecast[slot.day] ?: emptyMap()

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
     * Groups consecutive time slot indices into continuous ranges within the same day.
     * For example: [0, 1, 2, 5, 6, 8] -> [[0, 1, 2], [5, 6], [8]]
     * Ensures that slots from different days are never grouped together.
     */
    private fun groupConsecutiveSlots(indices: List<Int>, timeSlots: List<TimeSlot>): List<List<Int>> {
        if (indices.isEmpty()) return emptyList()

        val sorted = indices.sorted()
        val groups = mutableListOf<MutableList<Int>>()
        var currentGroup = mutableListOf(sorted[0])

        for (i in 1 until sorted.size) {
            val prevSlot = timeSlots[sorted[i - 1]]
            val currSlot = timeSlots[sorted[i]]

            // Check if consecutive AND on the same day AND times align
            val isConsecutive = sorted[i] == sorted[i - 1] + 1
            val isSameDay = prevSlot.day == currSlot.day
            val timesAlign = prevSlot.endTime == currSlot.startTime

            if (isConsecutive && isSameDay && timesAlign) {
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
