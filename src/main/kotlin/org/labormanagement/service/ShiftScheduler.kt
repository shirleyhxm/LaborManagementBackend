package org.labormanagement.service

import org.labormanagement.model.Employee
import org.labormanagement.model.OperatingHours
import org.labormanagement.model.OptimizationObjective
import org.labormanagement.model.SchedulingInput
import org.labormanagement.model.SchedulingMetrics
import org.labormanagement.model.SchedulingOutput
import org.labormanagement.model.Shift
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID

class ShiftScheduler(
    private val validator: ConstraintValidator = ConstraintValidator()
) {

    fun generateSchedule(input: SchedulingInput): SchedulingOutput {
        val shifts = mutableListOf<Shift>()
        val staffingRequirements = mutableListOf<org.labormanagement.model.StaffingRequirement>()

        // Track weekly hours across all days for overtime calculation and contract limits
        val weeklyHours = mutableMapOf<UUID, Double>()

        // Generate candidate shifts for each day
        input.schedulingPeriod.daysToSchedule.forEach { day ->
            val operatingHours = input.schedulingPeriod.operatingHours[day] ?: return@forEach
            val (dayShifts, dayRequirements) = generateShiftsForDay(
                day,
                operatingHours,
                input.employees,
                input.salesForecast[day] ?: emptyMap(),
                input.laborCostBudget,
                weeklyHours, // Pass the shared weekly hours tracker
                input.shiftDurationHours, // Pass the shift duration from input
                input.optimizationObjective // Pass the optimization objective
            )
            shifts.addAll(dayShifts)
            staffingRequirements.addAll(dayRequirements)
        }

        // Validate constraints
        val violations = validator.validate(shifts, input.employees, input.laborCostBudget, staffingRequirements)

        // Calculate metrics
        val metrics = calculateMetrics(shifts, input.employees, input.salesForecast)

        return SchedulingOutput(
            shifts = shifts,
            metrics = metrics,
            violations = violations,
            staffingRequirements = staffingRequirements
        )
    }

    private fun generateShiftsForDay(
        day: DayOfWeek,
        operatingHours: OperatingHours,
        employees: List<Employee>,
        salesForecast: Map<LocalTime, Double>,
        remainingBudget: Double,
        weeklyHours: MutableMap<UUID, Double>, // Shared across all days
        shiftDurationHours: Double, // Shift duration in hours
        optimizationObjective: OptimizationObjective // Optimization strategy
    ): Pair<List<Shift>, List<org.labormanagement.model.StaffingRequirement>> {
        val shifts = mutableListOf<Shift>()
        val staffingRequirements = mutableListOf<org.labormanagement.model.StaffingRequirement>()

        // Sort employees according to the optimization objective
        val sortedEmployees = sortEmployeesByObjective(employees, optimizationObjective, weeklyHours)

        var currentBudget = remainingBudget

        // Pre-calculate average productivity for the day (optimization)
        val avgProductivity = if (sortedEmployees.isNotEmpty()) {
            sortedEmployees.map { it.productivity }.average()
        } else {
            0.0
        }

        // For each time slot, assign the most productive available employees
        val timeSlots = generateTimeSlots(operatingHours.openTime, operatingHours.closeTime, shiftDurationHours)

        timeSlots.forEach { (startTime, endTime) ->
            // Calculate expected sales for this time slot
            val expectedSales = calculateAverageSales(salesForecast, startTime, endTime)
            val shiftHours = ChronoUnit.MINUTES.between(startTime, endTime) / 60.0

            // Filter available employees for this specific time slot
            val availableEmployees = sortedEmployees.filter { employee ->
                employee.availability.any { avail ->
                    avail.dayOfWeek == day &&
                    avail.startTime <= startTime &&
                    avail.endTime >= endTime
                }
            }

            // Determine staffing needs using pre-calculated productivity or slot-specific average
            val slotAvgProductivity = if (availableEmployees.isNotEmpty()) {
                availableEmployees.map { it.productivity }.average()
            } else {
                avgProductivity
            }

            val employeesNeeded = determineEmployeesNeeded(
                expectedSales = expectedSales,
                avgProductivity = slotAvgProductivity,
                shiftDurationHours = shiftHours,
                availableCount = availableEmployees.size
            )

            // Assign employees to this shift based on productivity-to-cost ratio
            var assigned = 0
            var remainingSalesTarget = expectedSales

            for (employee in sortedEmployees) {
                if (assigned >= employeesNeeded && remainingSalesTarget <= 0) break

                // Check if employee is available
                val isAvailable = employee.availability.any { avail ->
                    avail.dayOfWeek == day &&
                    avail.startTime <= startTime &&
                    avail.endTime >= endTime
                }

                if (!isAvailable) continue

                // Calculate weekly hours
                val currentWeeklyHours = weeklyHours.getOrDefault(employee.id, 0.0)
                val totalWeeklyHours = currentWeeklyHours + shiftHours

                // Check contract limits
                if (totalWeeklyHours > employee.contract.maxHoursPerWeek) continue
                if (shiftHours > employee.contract.maxHoursPerDay) continue

                // Determine pay rate (overtime vs normal)
                val isOvertime = currentWeeklyHours >= employee.contract.overtimeThreshold
                val payRate = if (isOvertime) employee.overtimePayRate else employee.normalPayRate
                val shiftCost = shiftHours * payRate

                // Check budget
                if (shiftCost > currentBudget) continue

                // Calculate expected contribution
                val expectedContribution = employee.productivity * shiftHours

                // Create shift
                val shift = Shift(
                    employeeId = employee.id,
                    dayOfWeek = day,
                    startTime = startTime,
                    endTime = endTime,
                    payRate = payRate,
                    isOvertime = isOvertime
                )

                shifts.add(shift)
                weeklyHours[employee.id] = totalWeeklyHours
                currentBudget -= shiftCost
                remainingSalesTarget -= expectedContribution
                assigned++
            }

            // Track staffing requirement for this time slot
            staffingRequirements.add(
                org.labormanagement.model.StaffingRequirement(
                    dayOfWeek = day,
                    startTime = startTime,
                    endTime = endTime,
                    employeesNeeded = employeesNeeded,
                    employeesAssigned = assigned,
                    expectedSales = expectedSales
                )
            )
        }

        return Pair(shifts, staffingRequirements)
    }

    private fun generateTimeSlots(
        openTime: LocalTime,
        closeTime: LocalTime,
        shiftDurationHours: Double
    ): List<Pair<LocalTime, LocalTime>> {
        val slots = mutableListOf<Pair<LocalTime, LocalTime>>()

        // Validate shift duration
        if (shiftDurationHours <= 0) {
            return emptyList()
        }

        // Handle overnight shifts (e.g., 22:00 to 02:00)
        val isOvernightShift = closeTime <= openTime

        // Calculate total operating minutes
        val totalMinutes = if (isOvernightShift) {
            // Minutes from openTime to midnight + minutes from midnight to closeTime
            ChronoUnit.MINUTES.between(openTime, LocalTime.MAX) +
            ChronoUnit.MINUTES.between(LocalTime.MIN, closeTime) + 1
        } else {
            ChronoUnit.MINUTES.between(openTime, closeTime)
        }

        // If there's no operating time, return empty
        if (totalMinutes <= 0) {
            return emptyList()
        }

        val shiftDurationMinutes = (shiftDurationHours * 60).toLong()
        var elapsedMinutes = 0L

        // Loop until we've covered all operating hours
        // Condition: elapsedMinutes must be strictly less than totalMinutes
        while (elapsedMinutes < totalMinutes) {
            val startTime = openTime.plusMinutes(elapsedMinutes)

            // Calculate remaining minutes in this operating period
            val remainingMinutes = totalMinutes - elapsedMinutes

            // Determine the actual shift duration for this slot
            val actualShiftDurationMinutes = minOf(shiftDurationMinutes, remainingMinutes)

            val endTime = startTime.plusMinutes(actualShiftDurationMinutes)

            // Add the slot
            slots.add(Pair(startTime, endTime))

            // Move to next time slot
            // Always advance by at least the shift duration to ensure progress
            elapsedMinutes += actualShiftDurationMinutes
        }

        return slots
    }

    private fun calculateAverageSales(
        salesForecast: Map<LocalTime, Double>,
        startTime: LocalTime,
        endTime: LocalTime
    ): Double {
        if (salesForecast.isEmpty()) return 0.0

        val relevantForecasts = salesForecast.filter { (time, _) ->
            !time.isBefore(startTime) && time.isBefore(endTime)
        }

        return if (relevantForecasts.isEmpty()) {
            salesForecast.values.average()
        } else {
            relevantForecasts.values.average()
        }
    }

    private fun determineEmployeesNeeded(
        expectedSales: Double,
        avgProductivity: Double,
        shiftDurationHours: Double,
        availableCount: Int
    ): Int {
        if (expectedSales <= 0.0 || availableCount == 0 || avgProductivity <= 0.0) return 1

        // Expected sales per employee for this shift duration
        val salesPerEmployee = avgProductivity * shiftDurationHours

        // Calculate minimum employees needed to meet sales target
        val employeesNeeded = kotlin.math.ceil(expectedSales / salesPerEmployee).toInt()

        // Cap at available employees count and ensure at least 1
        return maxOf(1, minOf(employeesNeeded, availableCount))
    }

    private fun sortEmployeesByObjective(
        employees: List<Employee>,
        objective: OptimizationObjective,
        currentWeeklyHours: Map<UUID, Double>
    ): List<Employee> {
        return when (objective) {
            OptimizationObjective.MAXIMIZE_SALES -> {
                // Highest productivity first (current default behavior)
                employees.sortedByDescending { it.productivity }
            }

            OptimizationObjective.MINIMIZE_LABOR_COST -> {
                // Lowest cost per hour first (considering current overtime status)
                employees.sortedBy { employee ->
                    val weeklyHrs = currentWeeklyHours.getOrDefault(employee.id, 0.0)
                    if (weeklyHrs >= employee.contract.overtimeThreshold) {
                        employee.overtimePayRate
                    } else {
                        employee.normalPayRate
                    }
                }
            }

            OptimizationObjective.BALANCED -> {
                // Best productivity-to-cost ratio (sales per dollar spent)
                employees.sortedByDescending { employee ->
                    val weeklyHrs = currentWeeklyHours.getOrDefault(employee.id, 0.0)
                    val payRate = if (weeklyHrs >= employee.contract.overtimeThreshold) {
                        employee.overtimePayRate
                    } else {
                        employee.normalPayRate
                    }
                    // Higher ratio = more sales per dollar
                    employee.productivity / payRate
                }
            }
        }
    }

    private fun calculateMetrics(
        shifts: List<Shift>,
        employees: List<Employee>,
        salesForecast: Map<DayOfWeek, Map<LocalTime, Double>>
    ): SchedulingMetrics {
        val totalLaborCost = shifts.sumOf { it.laborCost }

        // Calculate estimated sales
        val employeeMap = employees.associateBy { it.id }
        val estimatedSales = shifts.sumOf { shift ->
            val employee = employeeMap[shift.employeeId] ?: return@sumOf 0.0
            shift.durationHours * employee.productivity
        }

        val laborCostPercentage = if (estimatedSales > 0) {
            (totalLaborCost / estimatedSales) * 100
        } else {
            0.0
        }

        // Calculate employee utilization
        val utilization = mutableMapOf<String, Double>()
        employees.forEach { employee ->
            val employeeShifts = shifts.filter { it.employeeId == employee.id }
            val scheduledHours = employeeShifts.sumOf { it.durationHours }
            val utilizationRate = (scheduledHours / employee.contract.contractedHoursPerWeek) * 100
            utilization[employee.fullName] = utilizationRate
        }

        return SchedulingMetrics(
            totalLaborCost = totalLaborCost,
            estimatedTotalSales = estimatedSales,
            laborCostPercentage = laborCostPercentage,
            employeeUtilization = utilization
        )
    }
}
