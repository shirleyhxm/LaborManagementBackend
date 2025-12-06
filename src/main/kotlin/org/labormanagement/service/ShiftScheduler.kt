package org.labormanagement.service

import org.labormanagement.model.Employee
import org.labormanagement.model.OperatingHours
import org.labormanagement.model.OptimizationObjective
import org.labormanagement.model.Schedule
import org.labormanagement.model.ScheduleInput
import org.labormanagement.model.SchedulingMetrics
import org.labormanagement.model.Shift
import org.labormanagement.model.StaffingRequirement
import org.labormanagement.optimization.OptimizationConverter
import org.labormanagement.optimization.ScheduleOptimizer
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.SalesForecastRepository
import org.labormanagement.repository.ScheduleRepository
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Scheduling approach to use when generating schedules.
 */
enum class SchedulingApproach {
    /**
     * Fast greedy heuristic approach - hour-by-hour scheduling.
     * Good for quick results but may not find optimal solution.
     */
    GREEDY,

    /**
     * Mathematical optimization using CP-SAT solver.
     * Finds optimal or near-optimal solution but may take longer.
     */
    OPTIMIZER
}

class ShiftScheduler(
    private val validator: ConstraintValidator = ConstraintValidator(),
    private val employeeRepository: EmployeeRepository = EmployeeRepository(),
    private val scheduleRepository: ScheduleRepository = ScheduleRepository(),
    private val salesForecastRepository: SalesForecastRepository = SalesForecastRepository(),
    private val schedulingApproach: SchedulingApproach = SchedulingApproach.OPTIMIZER
) {
    /**
     * Generate a new DRAFT schedule from ScheduleInput.
     * Returns the Schedule object with lifecycle management enabled.
     */
    @Profile
    fun generateSchedule(
        input: ScheduleInput,
        name: String = "Generated Schedule",
        generatedBy: String = "system"
    ): Schedule = profile {
        // Choose scheduling approach
        val schedule = when (schedulingApproach) {
            SchedulingApproach.GREEDY -> generateScheduleGreedy(input, name, generatedBy)
            SchedulingApproach.OPTIMIZER -> generateScheduleOptimizer(input, name, generatedBy)
        }

        scheduleRepository.save(schedule)

        return@profile schedule
    }

    /**
     * Generate schedule using the greedy heuristic approach (original implementation).
     */
    private fun generateScheduleGreedy(
        input: ScheduleInput,
        name: String,
        generatedBy: String
    ): Schedule {
        val shifts = mutableListOf<Shift>()
        val staffingRequirements = mutableListOf<StaffingRequirement>()

        // Get configuration from input
        val minShiftDurationHours = input.minShiftDurationHours
        val optimizationObjective = input.optimizationObjective

        // Get sales forecast from repository
        val salesForecast = salesForecastRepository.get().weeklyForecast

        // Track weekly hours across all days for overtime calculation and contract limits
        val weeklyHours = mutableMapOf<UUID, Double>()

        val employees = input.employeeIds.mapNotNull { id ->
            employeeRepository.findById(id)
        }

        // Generate candidate shifts for each day
        profile("generateSchedule.allDays") {
            input.schedulePeriod.daysToSchedule.forEach { day ->
                val operatingHours = input.schedulePeriod.operatingHours[day] ?: return@forEach
                val (dayShifts, dayRequirements) = profile("generateSchedule.singleDay") {
                    generateShiftsForDay(
                        day,
                        operatingHours,
                        employees,
                        salesForecast[day] ?: emptyMap(),
                        input.laborCostBudget,
                        weeklyHours,
                        minShiftDurationHours,
                        optimizationObjective
                    )
                }
                shifts.addAll(dayShifts)
                staffingRequirements.addAll(dayRequirements)
            }
        }

        // Merge consecutive intervals with identical variables
        val mergedShifts = profile("generateSchedule.mergeShifts") {
            mergeConsecutiveShifts(shifts)
        }
        val mergedRequirements = profile("generateSchedule.mergeRequirements") {
            mergeConsecutiveStaffingRequirements(staffingRequirements)
        }

        // Validate constraints
        val violations = profile("generateSchedule.validate") {
            validator.validate(mergedShifts, employees, input.laborCostBudget, mergedRequirements)
        }

        // Calculate metrics
        val metrics = profile("generateSchedule.calculateMetrics") {
            calculateMetrics(mergedShifts, employees, salesForecast)
        }

        // Create and return Schedule with all data (starts as DRAFT)
        return Schedule(
            name = name,
            schedulePeriod = input.schedulePeriod,
            shifts = mergedShifts,
            metrics = metrics,
            violations = violations,
            staffingRequirements = mergedRequirements,
            employeeIds = input.employeeIds,
            laborCostBudget = input.laborCostBudget,
            minShiftDurationHours = minShiftDurationHours,
            optimizationObjective = optimizationObjective,
            createdBy = generatedBy,
            lastModifiedBy = generatedBy
        )
    }

    /**
     * Generate schedule using mathematical optimization (CP-SAT solver).
     */
    private fun generateScheduleOptimizer(
        input: ScheduleInput,
        name: String,
        generatedBy: String
    ): Schedule {
        val employees = input.employeeIds.mapNotNull { id ->
            employeeRepository.findById(id)
        }

        val salesForecast = salesForecastRepository.get()

        // Build operating hours map
        val operatingHoursMap = input.schedulePeriod.daysToSchedule.associateWith { day ->
            input.schedulePeriod.operatingHours[day]?.let { hours ->
                Pair(hours.openTime, hours.closeTime)
            } ?: Pair(LocalTime.of(9, 0), LocalTime.of(17, 0))
        }

        // Convert to optimization input
        val optimizationInput = profile("generateSchedule.buildOptimizationInput") {
            OptimizationConverter.buildOptimizationInput(
                employees = employees,
                salesForecast = salesForecast,
                scheduleDays = input.schedulePeriod.daysToSchedule,
                operatingHoursMap = operatingHoursMap,
                slotDurationHours = if (input.minShiftDurationHours > 0) input.minShiftDurationHours else 1.0,
                coverageFraction = 0.8,
                laborBudget = input.laborCostBudget.toLong(),
                objective = input.optimizationObjective,
                maxSolveTimeSeconds = 30.0
            )
        }

        // Run optimizer
        val optimizer = ScheduleOptimizer()
        val result = profile("generateSchedule.optimize") {
            optimizer.optimize(optimizationInput)
        }

        // Handle no solution case - fallback to greedy approach
        if (result == null) {
            println("Optimizer returned null - no feasible solution found. Falling back to GREEDY approach...")
            return generateScheduleGreedy(input, name, generatedBy)
        } else {
            println("Optimizer found a feasible solution.")
        }

        // Convert optimizer result to shifts
        val shifts = profile("generateSchedule.convertToShifts") {
            OptimizationConverter.convertToShifts(result, optimizationInput)
        }

        // Generate staffing requirements (reuse existing logic)
        val staffingRequirements = profile("generateSchedule.generateStaffingRequirements") {
            generateStaffingRequirementsFromShifts(
                shifts,
                employees,
                input.schedulePeriod,
                salesForecast.weeklyForecast
            )
        }

        // Validate constraints
        val violations = profile("generateSchedule.validate") {
            validator.validate(shifts, employees, input.laborCostBudget, staffingRequirements)
        }

        // Calculate metrics
        val metrics = profile("generateSchedule.calculateMetrics") {
            calculateMetrics(shifts, employees, salesForecast.weeklyForecast)
        }

        return Schedule(
            name = name,
            schedulePeriod = input.schedulePeriod,
            shifts = shifts,
            metrics = metrics,
            violations = violations,
            staffingRequirements = staffingRequirements,
            employeeIds = input.employeeIds,
            laborCostBudget = input.laborCostBudget,
            minShiftDurationHours = input.minShiftDurationHours,
            optimizationObjective = input.optimizationObjective,
            createdBy = generatedBy,
            lastModifiedBy = generatedBy
        )
    }

    /**
     * Merges consecutive shifts with identical properties (same employee, day, pay rate, overtime status).
     * This consolidates hour-by-hour shifts into longer continuous shifts.
     */
    private fun mergeConsecutiveShifts(shifts: List<Shift>): List<Shift> {
        if (shifts.isEmpty()) return emptyList()

        // Group shifts by employee and day
        val groupedShifts = shifts.groupBy { shift ->
            Pair(shift.employeeId, shift.dayOfWeek)
        }

        val mergedShifts = mutableListOf<Shift>()

        groupedShifts.forEach { (_, shiftsForEmployeeDay) ->
            // Sort by start time
            val sortedShifts = shiftsForEmployeeDay.sortedBy { it.startTime }

            var currentMergedShift: Shift? = null

            for (shift in sortedShifts) {
                if (currentMergedShift == null) {
                    // Start a new merged shift
                    currentMergedShift = shift
                } else {
                    // Check if this shift can be merged with the current merged shift
                    val canMerge = currentMergedShift.endTime == shift.startTime &&
                                   currentMergedShift.payRate == shift.payRate &&
                                   currentMergedShift.isOvertime == shift.isOvertime

                    if (canMerge) {
                        // Merge: extend the end time of the current merged shift
                        currentMergedShift = Shift(
                            employeeId = currentMergedShift.employeeId,
                            dayOfWeek = currentMergedShift.dayOfWeek,
                            startTime = currentMergedShift.startTime,
                            endTime = shift.endTime,
                            payRate = currentMergedShift.payRate,
                            isOvertime = currentMergedShift.isOvertime
                        )
                    } else {
                        // Cannot merge, save the current merged shift and start a new one
                        mergedShifts.add(currentMergedShift)
                        currentMergedShift = shift
                    }
                }
            }

            // Add the last merged shift
            if (currentMergedShift != null) {
                mergedShifts.add(currentMergedShift)
            }
        }

        return mergedShifts
    }

    /**
     * Merges consecutive staffing requirements with identical properties.
     * This consolidates hour-by-hour requirements into longer time blocks.
     */
    private fun mergeConsecutiveStaffingRequirements(
        requirements: List<StaffingRequirement>
    ): List<StaffingRequirement> {
        if (requirements.isEmpty()) return emptyList()

        // Group requirements by day
        val groupedRequirements = requirements.groupBy { it.dayOfWeek }

        val mergedRequirements = mutableListOf<StaffingRequirement>()

        groupedRequirements.forEach { (_, requirementsForDay) ->
            // Sort by start time
            val sortedRequirements = requirementsForDay.sortedBy { it.startTime }

            var currentMergedRequirement: StaffingRequirement? = null

            for (requirement in sortedRequirements) {
                if (currentMergedRequirement == null) {
                    // Start a new merged requirement
                    currentMergedRequirement = requirement
                } else {
                    // Check if this requirement can be merged with the current merged requirement
                    val canMerge = currentMergedRequirement.endTime == requirement.startTime &&
                                   currentMergedRequirement.employeesNeeded == requirement.employeesNeeded &&
                                   currentMergedRequirement.employeesAssigned == requirement.employeesAssigned

                    if (canMerge) {
                        // Merge: extend the end time and sum the expected sales
                        currentMergedRequirement = StaffingRequirement(
                            dayOfWeek = currentMergedRequirement.dayOfWeek,
                            startTime = currentMergedRequirement.startTime,
                            endTime = requirement.endTime,
                            employeesNeeded = currentMergedRequirement.employeesNeeded,
                            employeesAssigned = currentMergedRequirement.employeesAssigned,
                            expectedSales = currentMergedRequirement.expectedSales + requirement.expectedSales
                        )
                    } else {
                        // Cannot merge, save the current merged requirement and start a new one
                        mergedRequirements.add(currentMergedRequirement)
                        currentMergedRequirement = requirement
                    }
                }
            }

            // Add the last merged requirement
            if (currentMergedRequirement != null) {
                mergedRequirements.add(currentMergedRequirement)
            }
        }

        return mergedRequirements
    }

    private fun generateShiftsForDay(
        day: DayOfWeek,
        operatingHours: OperatingHours,
        employees: List<Employee>,
        salesForecast: Map<LocalTime, Double>,
        remainingBudget: Double,
        weeklyHours: MutableMap<UUID, Double>, // Shared across all days
        minShiftDurationHours: Double, // Minimum shift duration in hours
        optimizationObjective: OptimizationObjective // Optimization strategy
    ): Pair<List<Shift>, List<StaffingRequirement>> {
        val shifts = mutableListOf<Shift>()
        val staffingRequirements = mutableListOf<StaffingRequirement>()

        // Sort employees according to the optimization objective
        val sortedEmployees = profile("generateShiftsForDay.sortEmployees") {
            sortEmployeesByObjective(employees, optimizationObjective, weeklyHours)
        }

        var currentBudget = remainingBudget

        // Pre-calculate average productivity for the day
        val avgProductivity = if (sortedEmployees.isNotEmpty()) {
            sortedEmployees.map { it.productivity }.average()
        } else {
            0.0
        }

        // Generate hourly evaluation intervals for hour-by-hour analysis
        val evaluationSlots = profile("generateShiftsForDay.generateIntervals") {
            generateEvaluationIntervals(operatingHours.openTime, operatingHours.closeTime, minShiftDurationHours)
        }

        // Track sales coverage hour-by-hour: for each hour interval, track remaining uncovered sales
        val hourlyCoverage = mutableMapOf<Pair<LocalTime, LocalTime>, Double>()
        profile("generateShiftsForDay.initCoverage") {
            evaluationSlots.forEach { interval ->
                val expectedSales = calculateAverageSales(salesForecast, interval.first, interval.second)
                if (expectedSales > 0) {
                    hourlyCoverage[interval] = expectedSales  // Initially all sales are uncovered
                }
            }
        }

        // Schedule hour-by-hour: go through each hour with demand and assign employees
        profile("generateShiftsForDay.hourByHourScheduling") {
            for (interval in evaluationSlots) {
                val uncoveredSales = hourlyCoverage[interval] ?: 0.0
                if (uncoveredSales <= 0 || currentBudget <= 0) continue  // Skip hours with no uncovered demand

                // Sort employees for this specific hour, prioritizing those who worked the previous consecutive hour
                val previousInterval = evaluationSlots.findPreviousInterval(interval)
                val employeesForThisHour = sortEmployeesForInterval(
                    sortedEmployees,
                    interval,
                    previousInterval,
                    shifts,
                    day,
                    weeklyHours
                )

                // Try to assign employees to cover this specific hour
                for (employee in employeesForThisHour) {
                    // Check if this hour still has uncovered demand
                    val currentUncovered = hourlyCoverage[interval] ?: 0.0
                    if (currentUncovered <= 0) break  // Hour is covered, move to next hour

                    // Check if employee is available during this hour
                    val availabilityForHour = employee.availability.find { avail ->
                        avail.dayOfWeek == day &&
                                avail.startTime <= interval.first &&
                                avail.endTime >= interval.second
                    }
                    if (availabilityForHour == null) continue

                    // Check contract limits
                    val currentWeeklyHours = weeklyHours.getOrDefault(employee.id, 0.0)
                    val remainingContractHours = employee.contract.maxHoursPerWeek - currentWeeklyHours
                    if (remainingContractHours < minShiftDurationHours) continue

                    // Check if we already have a shift for this employee that covers this hour
                    val existingShift = shifts.find { shift ->
                        shift.employeeId == employee.id &&
                                shift.dayOfWeek == day &&
                                shift.startTime <= interval.first &&
                                shift.endTime >= interval.second
                    }

                    if (existingShift != null) {
                        // Employee already has a shift covering this hour, count their contribution
                        val intervalHours = ChronoUnit.MINUTES.between(interval.first, interval.second) / 60.0
                        val contribution = employee.productivity * intervalHours
                        hourlyCoverage[interval] = maxOf(0.0, currentUncovered - contribution)
                        continue
                    }

                    // Create a shift for this specific hour interval
                    val shiftStart = interval.first
                    val shiftEnd = interval.second
                    val shiftHours = ChronoUnit.MINUTES.between(shiftStart, shiftEnd) / 60.0

                    // Check minimum shift duration (always require at least some duration)
                    if (shiftHours <= 0.0 || shiftHours < minShiftDurationHours) continue

                    // Check contract daily and weekly limits
                    val maxShiftHours = minOf(
                        shiftHours,
                        employee.contract.maxHoursPerDay,
                        remainingContractHours
                    )

                    if (maxShiftHours < minShiftDurationHours) continue

                    val finalShiftEnd = shiftStart.plusMinutes((maxShiftHours * 60).toLong())
                    val finalShiftHours = ChronoUnit.MINUTES.between(shiftStart, finalShiftEnd) / 60.0

                    // Determine pay rate
                    // A shift is considered overtime if the employee has already met or exceeded their overtime threshold
                    // before this shift begins. This is a simplified model - it doesn't split individual shifts into
                    // normal and overtime portions.
                    val isOvertime = currentWeeklyHours >= employee.contract.overtimeThreshold
                    val payRate = if (isOvertime) employee.overtimePayRate else employee.normalPayRate

                    // Check budget - allow partial shifts if budget is limited
                    val maxAffordableHours = if (payRate > 0) currentBudget / payRate else Double.MAX_VALUE

                    if (maxAffordableHours < minShiftDurationHours) continue  // Can't afford minimum shift

                    val budgetConstrainedHours = minOf(finalShiftHours, maxAffordableHours)
                    val budgetConstrainedEnd = shiftStart.plusMinutes((budgetConstrainedHours * 60).toLong())
                    val actualShiftHours = ChronoUnit.MINUTES.between(shiftStart, budgetConstrainedEnd) / 60.0

                    // Ensure shift has positive duration and meets minimum requirements
                    if (actualShiftHours <= 0.0 || actualShiftHours < minShiftDurationHours) continue

                    val shiftCost = actualShiftHours * payRate
                    if (shiftCost > currentBudget) continue

                    // Create shift
                    val shift = Shift(
                        employeeId = employee.id,
                        dayOfWeek = day,
                        startTime = shiftStart,
                        endTime = budgetConstrainedEnd,
                        payRate = payRate,
                        isOvertime = isOvertime
                    )

                    shifts.add(shift)
                    weeklyHours[employee.id] = currentWeeklyHours + actualShiftHours
                    currentBudget -= shiftCost

                    // Update coverage for all hours this shift covers
                    evaluationSlots.forEach { coveredInterval ->
                        val shiftCovers =
                            shiftStart <= coveredInterval.first && budgetConstrainedEnd >= coveredInterval.second
                        if (shiftCovers) {
                            val intervalHours =
                                ChronoUnit.MINUTES.between(coveredInterval.first, coveredInterval.second) / 60.0
                            val contribution = employee.productivity * intervalHours
                            val current = hourlyCoverage[coveredInterval] ?: 0.0
                            hourlyCoverage[coveredInterval] = maxOf(0.0, current - contribution)
                        }
                    }
                }
            }
        }

        // Generate staffing requirements based on evaluation slots
        profile("generateShiftsForDay.generateRequirements") {
            evaluationSlots.forEach { (startTime, endTime) ->
            val expectedSales = calculateAverageSales(salesForecast, startTime, endTime)
            val slotHours = ChronoUnit.MINUTES.between(startTime, endTime) / 60.0

            // Count employees covering this time slot
            val assignedCount = shifts.count { shift ->
                shift.startTime <= startTime && shift.endTime >= endTime
            }

            // Determine how many employees are needed
            val availableEmployees = sortedEmployees.filter { employee ->
                employee.availability.any { avail ->
                    avail.dayOfWeek == day &&
                    avail.startTime <= startTime &&
                    avail.endTime >= endTime
                }
            }

            val slotAvgProductivity = if (availableEmployees.isNotEmpty()) {
                availableEmployees.map { it.productivity }.average()
            } else {
                avgProductivity
            }

            val employeesNeeded = determineEmployeesNeeded(
                expectedSales = expectedSales,
                avgProductivity = slotAvgProductivity,
                shiftDurationHours = slotHours,
                availableCount = availableEmployees.size
            )

            staffingRequirements.add(
                StaffingRequirement(
                    dayOfWeek = day,
                    startTime = startTime,
                    endTime = endTime,
                    employeesNeeded = employeesNeeded,
                    employeesAssigned = assignedCount,
                    expectedSales = expectedSales
                )
            )
        }
        }

        return Pair(shifts, staffingRequirements)
    }

    /**
     * Generates hourly time intervals for evaluating staffing requirements hour-by-hour.
     * This ensures we schedule employees for the specific hours when they're needed.
     * If there's less than an hour remaining at the end, creates a partial interval.
     */
    private fun generateEvaluationIntervals(
        openTime: LocalTime,
        closeTime: LocalTime,
        intervalDurationHours: Double
    ): List<Pair<LocalTime, LocalTime>> {
        val intervals = mutableListOf<Pair<LocalTime, LocalTime>>()

        // Use 1-hour intervals for hour-by-hour evaluation
        val hourlyIntervalMinutes = 60L

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

        var elapsedMinutes = 0L

        // Loop until we've covered all operating hours
        while (elapsedMinutes < totalMinutes) {
            val startTime = openTime.plusMinutes(elapsedMinutes)

            // Calculate remaining minutes in this operating period
            val remainingMinutes = totalMinutes - elapsedMinutes

            // Use hourly intervals, but if less than an hour remains, use the remaining duration
            val actualIntervalDurationMinutes = minOf(hourlyIntervalMinutes, remainingMinutes)

            val endTime = startTime.plusMinutes(actualIntervalDurationMinutes)

            // Add the interval
            intervals.add(Pair(startTime, endTime))

            // Move to next interval
            elapsedMinutes += actualIntervalDurationMinutes
        }

        return intervals
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
            0.0  // No forecast for this hour means no expected sales
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

    /**
     * Finds the previous consecutive interval in the list.
     * Returns null if the given interval is the first one.
     */
    private fun List<Pair<LocalTime, LocalTime>>.findPreviousInterval(
        currentInterval: Pair<LocalTime, LocalTime>
    ): Pair<LocalTime, LocalTime>? {
        val currentIndex = this.indexOf(currentInterval)
        return if (currentIndex > 0) this[currentIndex - 1] else null
    }

    /**
     * Sorts employees for a specific hour interval, prioritizing employees who worked
     * the previous consecutive hour when all other stats (pay rate and productivity) are equal.
     * This naturally creates longer continuous shifts without explicit extension logic.
     */
    private fun sortEmployeesForInterval(
        baseOrderedEmployees: List<Employee>,
        currentInterval: Pair<LocalTime, LocalTime>,
        previousInterval: Pair<LocalTime, LocalTime>?,
        existingShifts: List<Shift>,
        day: DayOfWeek,
        weeklyHours: Map<UUID, Double>
    ): List<Employee> {
        // If there's no previous interval, return the base ordering
        if (previousInterval == null) {
            return baseOrderedEmployees
        }

        // Find employees who worked the previous consecutive hour
        val employeesWhoWorkedPreviousHour = existingShifts
            .filter { shift ->
                shift.dayOfWeek == day &&
                shift.startTime <= previousInterval.first &&
                shift.endTime >= previousInterval.second
            }
            .map { it.employeeId }
            .toSet()

        // Sort employees: prioritize those who worked the previous hour when stats are equal
        return baseOrderedEmployees.sortedWith(compareBy(
            // Primary sort: base optimization objective (already sorted)
            { baseOrderedEmployees.indexOf(it) },
            // Secondary sort: deprioritize those who worked previous hour (0 = worked, 1 = didn't work)
            // Using negative to invert (we want workers first)
            { employee -> if (employee.id in employeesWhoWorkedPreviousHour) 0 else 1 }
        ))
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

            OptimizationObjective.MAXIMIZE_FAIRNESS -> {
                // Prioritize employees with fewest scheduled hours to balance workload
                employees.sortedBy { employee ->
                    currentWeeklyHours.getOrDefault(employee.id, 0.0)
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

    /**
     * Generate staffing requirements from existing shifts.
     * Used by the optimizer path to compute staffing requirements after optimization.
     */
    private fun generateStaffingRequirementsFromShifts(
        shifts: List<Shift>,
        employees: List<Employee>,
        schedulePeriod: org.labormanagement.model.SchedulePeriod,
        salesForecast: Map<DayOfWeek, Map<LocalTime, Double>>
    ): List<StaffingRequirement> {
        val requirements = mutableListOf<StaffingRequirement>()
        val employeeMap = employees.associateBy { it.id }

        schedulePeriod.daysToSchedule.forEach { day ->
            val operatingHours = schedulePeriod.operatingHours[day] ?: return@forEach
            val dayForecast = salesForecast[day] ?: emptyMap()

            // Generate hourly intervals
            val intervals = generateEvaluationIntervals(operatingHours.openTime, operatingHours.closeTime, 1.0)

            intervals.forEach { (startTime, endTime) ->
                val expectedSales = calculateAverageSales(dayForecast, startTime, endTime)
                val slotHours = ChronoUnit.MINUTES.between(startTime, endTime) / 60.0

                // Count employees covering this time slot
                val assignedCount = shifts.count { shift ->
                    shift.dayOfWeek == day &&
                    shift.startTime <= startTime &&
                    shift.endTime >= endTime
                }

                // Calculate employees needed based on productivity
                val availableEmployees = employees.filter { employee ->
                    employee.availability.any { avail ->
                        avail.dayOfWeek == day &&
                        avail.startTime <= startTime &&
                        avail.endTime >= endTime
                    }
                }

                val avgProductivity = if (availableEmployees.isNotEmpty()) {
                    availableEmployees.map { it.productivity }.average()
                } else {
                    employees.map { it.productivity }.average()
                }

                val employeesNeeded = determineEmployeesNeeded(
                    expectedSales = expectedSales,
                    avgProductivity = avgProductivity,
                    shiftDurationHours = slotHours,
                    availableCount = availableEmployees.size
                )

                requirements.add(
                    StaffingRequirement(
                        dayOfWeek = day,
                        startTime = startTime,
                        endTime = endTime,
                        employeesNeeded = employeesNeeded,
                        employeesAssigned = assignedCount,
                        expectedSales = expectedSales
                    )
                )
            }
        }

        // Merge consecutive requirements with same values
        return mergeConsecutiveStaffingRequirements(requirements)
    }
}
