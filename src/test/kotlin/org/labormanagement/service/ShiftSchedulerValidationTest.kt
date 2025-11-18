package org.labormanagement.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.labormanagement.model.*
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.SalesForecastRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Self-validating pipeline test for ShiftScheduler optimization objectives.
 *
 * This test validates that:
 * 1. MAXIMIZE_SALES objective produces the highest estimated total sales among all objectives
 * 2. MINIMIZE_LABOR_COST objective produces the lowest total labor cost among all objectives
 *
 * The test uses the same input data (employees, sales forecast, budget) for all objectives
 * to ensure a fair comparison.
 */
class ShiftSchedulerValidationTest {
    private lateinit var employeeRepository: EmployeeRepository
    private lateinit var salesForecastRepository: SalesForecastRepository
    private lateinit var scheduler: ShiftScheduler

    @BeforeEach
    fun setup() {
        employeeRepository = EmployeeRepository()
        salesForecastRepository = SalesForecastRepository()
        scheduler = ShiftScheduler(
            employeeRepository = employeeRepository,
            salesForecastRepository = salesForecastRepository
        )
    }

    /**
     * Creates 10 diverse test employees with varying productivity, pay rates, and availability.
     * This diversity ensures the optimizer has meaningful choices to make.
     */
    private fun createTestEmployees(): List<Employee> {
        val employees = mutableListOf<Employee>()

        // Employee 1: High productivity, high cost (productivity: 500, rate: 40)
        employees.add(createEmployee(
            firstName = "HighProdHighCost",
            productivity = 500.0,
            payRate = 40.0,
            contractedHours = 40.0,
            maxHours = 50.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.THURSDAY, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.FRIDAY, LocalTime.of(8, 0), LocalTime.of(20, 0))
            )
        ))

        // Employee 2: High productivity, medium cost (productivity: 450, rate: 30)
        employees.add(createEmployee(
            firstName = "HighProdMedCost",
            productivity = 450.0,
            payRate = 30.0,
            contractedHours = 40.0,
            maxHours = 50.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(DayOfWeek.THURSDAY, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(DayOfWeek.FRIDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        ))

        // Employee 3: Medium productivity, low cost (productivity: 250, rate: 15) - Best ratio
        employees.add(createEmployee(
            firstName = "MedProdLowCost",
            productivity = 250.0,
            payRate = 15.0,
            contractedHours = 40.0,
            maxHours = 50.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(DayOfWeek.THURSDAY, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(DayOfWeek.FRIDAY, LocalTime.of(8, 0), LocalTime.of(22, 0))
            )
        ))

        // Employee 4: Low productivity, very low cost (productivity: 150, rate: 10)
        employees.add(createEmployee(
            firstName = "LowProdVeryLowCost",
            productivity = 150.0,
            payRate = 10.0,
            contractedHours = 40.0,
            maxHours = 50.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.THURSDAY, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.FRIDAY, LocalTime.of(8, 0), LocalTime.of(20, 0))
            )
        ))

        // Employee 5: Medium productivity, medium cost (productivity: 300, rate: 25)
        employees.add(createEmployee(
            firstName = "MedProdMedCost",
            productivity = 300.0,
            payRate = 25.0,
            contractedHours = 40.0,
            maxHours = 50.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(10, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.WEDNESDAY, LocalTime.of(10, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.THURSDAY, LocalTime.of(10, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.FRIDAY, LocalTime.of(10, 0), LocalTime.of(20, 0))
            )
        ))

        // Employee 6: Very high productivity, very high cost (productivity: 600, rate: 50)
        employees.add(createEmployee(
            firstName = "VeryHighProdVeryHighCost",
            productivity = 600.0,
            payRate = 50.0,
            contractedHours = 35.0,
            maxHours = 45.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0)),
                Availability(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(18, 0)),
                Availability(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0), LocalTime.of(18, 0)),
                Availability(DayOfWeek.THURSDAY, LocalTime.of(9, 0), LocalTime.of(18, 0)),
                Availability(DayOfWeek.FRIDAY, LocalTime.of(9, 0), LocalTime.of(18, 0))
            )
        ))

        // Employee 7: Low productivity, low cost (productivity: 180, rate: 12)
        employees.add(createEmployee(
            firstName = "LowProdLowCost",
            productivity = 180.0,
            payRate = 12.0,
            contractedHours = 40.0,
            maxHours = 50.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(19, 0)),
                Availability(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(19, 0)),
                Availability(DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(19, 0)),
                Availability(DayOfWeek.THURSDAY, LocalTime.of(8, 0), LocalTime.of(19, 0)),
                Availability(DayOfWeek.FRIDAY, LocalTime.of(8, 0), LocalTime.of(19, 0))
            )
        ))

        // Employee 8: High productivity, low cost (productivity: 400, rate: 20) - Excellent ratio
        employees.add(createEmployee(
            firstName = "HighProdLowCost",
            productivity = 400.0,
            payRate = 20.0,
            contractedHours = 40.0,
            maxHours = 50.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(21, 0)),
                Availability(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(21, 0)),
                Availability(DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(21, 0)),
                Availability(DayOfWeek.THURSDAY, LocalTime.of(8, 0), LocalTime.of(21, 0)),
                Availability(DayOfWeek.FRIDAY, LocalTime.of(8, 0), LocalTime.of(21, 0))
            )
        ))

        // Employee 9: Medium-low productivity, medium cost (productivity: 200, rate: 18)
        employees.add(createEmployee(
            firstName = "MedLowProdMedCost",
            productivity = 200.0,
            payRate = 18.0,
            contractedHours = 35.0,
            maxHours = 45.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(10, 0), LocalTime.of(19, 0)),
                Availability(DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(19, 0)),
                Availability(DayOfWeek.WEDNESDAY, LocalTime.of(10, 0), LocalTime.of(19, 0)),
                Availability(DayOfWeek.THURSDAY, LocalTime.of(10, 0), LocalTime.of(19, 0)),
                Availability(DayOfWeek.FRIDAY, LocalTime.of(10, 0), LocalTime.of(19, 0))
            )
        ))

        // Employee 10: Very low productivity, very low cost (productivity: 100, rate: 8)
        employees.add(createEmployee(
            firstName = "VeryLowProdVeryLowCost",
            productivity = 100.0,
            payRate = 8.0,
            contractedHours = 40.0,
            maxHours = 50.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(12, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.TUESDAY, LocalTime.of(12, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.WEDNESDAY, LocalTime.of(12, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.THURSDAY, LocalTime.of(12, 0), LocalTime.of(20, 0)),
                Availability(DayOfWeek.FRIDAY, LocalTime.of(12, 0), LocalTime.of(20, 0))
            )
        ))

        return employees
    }

    private fun createEmployee(
        firstName: String,
        productivity: Double,
        payRate: Double,
        contractedHours: Double,
        maxHours: Double,
        availability: List<Availability>
    ): Employee {
        val employee = Employee(
            firstName = firstName,
            lastName = "Test",
            dateOfBirth = LocalDate.of(1990, 1, 1),
            normalPayRate = payRate,
            overtimePayRate = payRate * 1.5,
            productivity = productivity,
            contract = Contract(
                contractedHoursPerWeek = contractedHours,
                maxHoursPerWeek = maxHours,
                maxHoursPerDay = 10.0,
                overtimeThreshold = contractedHours,
                requiresBreak = true,
                breakDurationMinutes = 30,
                shiftLengthThresholdHours = 6
            ),
            availability = availability
        )
        employeeRepository.create(employee)
        return employee
    }

    /**
     * Creates a realistic weekly sales projection with varying demand throughout the week
     * and throughout each day (lower in morning/evening, higher during lunch/afternoon).
     */
    private fun createWeeklySalesForecast(): Map<DayOfWeek, Map<LocalTime, Double>> {
        val forecast = mutableMapOf<DayOfWeek, Map<LocalTime, Double>>()

        // Define hourly sales pattern (morning to evening)
        // Pattern: slow start, lunch rush, afternoon peak, evening decline
        val hourlyPattern = mapOf(
            8 to 200.0,   // Morning opening
            9 to 400.0,   // Morning pickup
            10 to 600.0,  // Mid-morning
            11 to 900.0,  // Pre-lunch
            12 to 1500.0, // Lunch peak
            13 to 1400.0, // Post-lunch
            14 to 1100.0, // Afternoon
            15 to 1000.0, // Mid-afternoon
            16 to 800.0,  // Late afternoon
            17 to 700.0,  // Early evening
            18 to 500.0,  // Evening
            19 to 300.0,  // Late evening
            20 to 200.0,  // Closing
            21 to 100.0   // Final hour
        )

        // Apply different multipliers for different days
        val dayMultipliers = mapOf(
            DayOfWeek.MONDAY to 0.8,      // Slower Monday
            DayOfWeek.TUESDAY to 0.9,     // Building up
            DayOfWeek.WEDNESDAY to 1.0,   // Mid-week normal
            DayOfWeek.THURSDAY to 1.1,    // Picking up
            DayOfWeek.FRIDAY to 1.3       // Busiest day
        )

        listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
               DayOfWeek.THURSDAY, DayOfWeek.FRIDAY).forEach { day ->
            val dayMultiplier = dayMultipliers[day] ?: 1.0
            val dayForecast = hourlyPattern.mapKeys { (hour, _) ->
                LocalTime.of(hour, 0)
            }.mapValues { (_, sales) ->
                sales * dayMultiplier
            }
            forecast[day] = dayForecast
        }

        return forecast
    }

    @Test
    fun `validation pipeline - all optimization objectives should meet their optimization criteria`() {
        // Step 1: Create test data
        println("\n========================================")
        println("SHIFT SCHEDULER VALIDATION PIPELINE")
        println("========================================\n")

        println("Step 1: Creating test employees...")
        val employees = createTestEmployees()
        println("Created ${employees.size} employees with diverse productivity and cost profiles:")
        employees.forEach { emp ->
            val ratio = emp.productivity / emp.normalPayRate
            println("  - ${emp.firstName}: productivity=${"%.0f".format(emp.productivity)}, " +
                    "rate=${"%.0f".format(emp.normalPayRate)}, " +
                    "ratio=${"%.2f".format(ratio)}")
        }

        println("\nStep 2: Creating weekly sales forecast...")
        val salesForecast = createWeeklySalesForecast()
        salesForecastRepository.update(salesForecast)
        val totalExpectedSales = salesForecast.values.flatMap { it.values }.sum()
        println("Created forecast for 5 days with total expected sales: ${"%.2f".format(totalExpectedSales)}")

        // Step 3: Define common input parameters
        println("\nStep 3: Defining common scheduling parameters...")
        val laborCostBudget = 15000.0  // Generous budget to allow meaningful optimization
        val schedulePeriod = SchedulePeriod(
            daysToSchedule = listOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY
            ),
            operatingHours = mapOf(
                DayOfWeek.MONDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(22, 0)),
                DayOfWeek.TUESDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(22, 0)),
                DayOfWeek.WEDNESDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(22, 0)),
                DayOfWeek.THURSDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(22, 0)),
                DayOfWeek.FRIDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(22, 0))
            )
        )
        println("Budget: $${"%.2f".format(laborCostBudget)}")
        println("Operating hours: 8:00 - 22:00, Monday through Friday")

        // Step 4: Generate schedules for each optimization objective
        println("\nStep 4: Generating schedules for each optimization objective...")
        val objectives = listOf(
            OptimizationObjective.MAXIMIZE_SALES,
            OptimizationObjective.MINIMIZE_LABOR_COST,
            OptimizationObjective.BALANCED,
            OptimizationObjective.MAXIMIZE_FAIRNESS
        )

        val schedules = mutableMapOf<OptimizationObjective, Schedule>()

        objectives.forEach { objective ->
            println("\n  Generating schedule for $objective...")
            val input = ScheduleInput(
                employeeIds = employees.map { it.id },
                laborCostBudget = laborCostBudget,
                schedulePeriod = schedulePeriod,
                optimizationObjective = objective
            )

            val schedule = scheduler.generateSchedule(input, name = "$objective Schedule")
            schedules[objective] = schedule

            println("    Total labor cost: ${"%.2f".format(schedule.metrics.totalLaborCost)}")
            println("    Estimated sales: ${"%.2f".format(schedule.metrics.estimatedTotalSales)}")
            println("    Total shifts: ${schedule.shifts.size}")
            println("    Constraint violations: ${schedule.violations.size}")
        }

        // Step 5: Validate optimization criteria
        println("\n========================================")
        println("VALIDATION RESULTS")
        println("========================================\n")

        val maxSalesSchedule = schedules[OptimizationObjective.MAXIMIZE_SALES]!!
        val minCostSchedule = schedules[OptimizationObjective.MINIMIZE_LABOR_COST]!!
        val balancedSchedule = schedules[OptimizationObjective.BALANCED]!!
        val fairnessSchedule = schedules[OptimizationObjective.MAXIMIZE_FAIRNESS]!!

        // Print detailed metrics table
        println("Detailed Metrics Comparison:")
        println("┌────────────────────────┬─────────────────┬─────────────────┬──────────┐")
        println("│ Objective              │ Labor Cost ($)  │ Est. Sales ($)  │ Shifts   │")
        println("├────────────────────────┼─────────────────┼─────────────────┼──────────┤")
        schedules.forEach { (objective, schedule) ->
            val objName = objective.toString().padEnd(22)
            val cost = "%,15.2f".format(schedule.metrics.totalLaborCost)
            val sales = "%,15.2f".format(schedule.metrics.estimatedTotalSales)
            val shifts = "%8d".format(schedule.shifts.size)
            println("│ $objName │ $cost │ $sales │ $shifts │")
        }
        println("└────────────────────────┴─────────────────┴─────────────────┴──────────┘")

        // Validation 1: MAXIMIZE_SALES should have highest estimated sales
        println("\nValidation 1: MAXIMIZE_SALES optimization")
        println("------------------------------------------")
        val allSales = schedules.map { it.value.metrics.estimatedTotalSales }
        val maxSales = allSales.max()

        println("  MAXIMIZE_SALES estimated sales: ${"%.2f".format(maxSalesSchedule.metrics.estimatedTotalSales)}")
        println("  Highest sales among all objectives: ${"%.2f".format(maxSales)}")

        val salesOptimizationPassed = maxSalesSchedule.metrics.estimatedTotalSales >= maxSales - 0.01
        if (salesOptimizationPassed) {
            println("  ✓ PASS: MAXIMIZE_SALES produced the highest estimated sales")
        } else {
            println("  ✗ FAIL: MAXIMIZE_SALES did NOT produce the highest estimated sales")
            println("  Expected: >= ${"%.2f".format(maxSales)}")
            println("  Actual: ${"%.2f".format(maxSalesSchedule.metrics.estimatedTotalSales)}")
        }

        // Validation 2: MINIMIZE_LABOR_COST should have lowest labor cost
        println("\nValidation 2: MINIMIZE_LABOR_COST optimization")
        println("----------------------------------------------")
        val allCosts = schedules.map { it.value.metrics.totalLaborCost }
        val minCost = allCosts.min()

        println("  MINIMIZE_LABOR_COST labor cost: ${"%.2f".format(minCostSchedule.metrics.totalLaborCost)}")
        println("  Lowest cost among all objectives: ${"%.2f".format(minCost)}")

        val costOptimizationPassed = minCostSchedule.metrics.totalLaborCost <= minCost + 0.01
        if (costOptimizationPassed) {
            println("  ✓ PASS: MINIMIZE_LABOR_COST produced the lowest labor cost")
        } else {
            println("  ✗ FAIL: MINIMIZE_LABOR_COST did NOT produce the lowest labor cost")
            println("  Expected: <= ${"%.2f".format(minCost)}")
            println("  Actual: ${"%.2f".format(minCostSchedule.metrics.totalLaborCost)}")
        }

        // Additional analysis: Show employee distribution for each objective
        println("\nAdditional Analysis: Employee Utilization")
        println("------------------------------------------")
        schedules.forEach { (objective, schedule) ->
            println("\n$objective:")
            val employeeHours = employees.map { emp ->
                val hours = schedule.shifts.filter { it.employeeId == emp.id }.sumOf { it.durationHours }
                emp.firstName to hours
            }.filter { it.second > 0 }.sortedByDescending { it.second }

            employeeHours.take(5).forEach { (name, hours) ->
                println("  ${name.padEnd(30)} ${"%.1f".format(hours)} hours")
            }
            if (employeeHours.size > 5) {
                println("  ... and ${employeeHours.size - 5} more employees")
            }
        }

        // Final assertions
        println("\n========================================")
        println("FINAL RESULT")
        println("========================================\n")

        val allTestsPassed = salesOptimizationPassed && costOptimizationPassed
        if (allTestsPassed) {
            println("✓ ALL VALIDATIONS PASSED")
            println("\nThe ShiftScheduler correctly optimizes for:")
            println("  1. MAXIMIZE_SALES produces highest estimated sales")
            println("  2. MINIMIZE_LABOR_COST produces lowest labor cost")
        } else {
            println("✗ SOME VALIDATIONS FAILED")
            println("\nThe ShiftScheduler needs improvements:")
            if (!salesOptimizationPassed) {
                println("  - MAXIMIZE_SALES is not producing the highest sales")
            }
            if (!costOptimizationPassed) {
                println("  - MINIMIZE_LABOR_COST is not producing the lowest cost")
            }
        }
        println()

        // Assert final results
        assertTrue(
            salesOptimizationPassed,
            "MAXIMIZE_SALES should produce the highest estimated sales among all objectives. " +
                    "Expected >= ${"%.2f".format(maxSales)}, " +
                    "but got ${"%.2f".format(maxSalesSchedule.metrics.estimatedTotalSales)}"
        )

        assertTrue(
            costOptimizationPassed,
            "MINIMIZE_LABOR_COST should produce the lowest labor cost among all objectives. " +
                    "Expected <= ${"%.2f".format(minCost)}, " +
                    "but got ${"%.2f".format(minCostSchedule.metrics.totalLaborCost)}"
        )
    }
}
