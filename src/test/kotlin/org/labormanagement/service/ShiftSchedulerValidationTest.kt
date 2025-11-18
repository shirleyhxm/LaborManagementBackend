package org.labormanagement.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.RepetitionInfo
import org.labormanagement.model.*
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.SalesForecastRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/**
 * Self-validating pipeline test for ShiftScheduler optimization objectives.
 *
 * This test validates that:
 * 1. MAXIMIZE_SALES objective produces the highest estimated total sales among all objectives
 * 2. MINIMIZE_LABOR_COST objective produces the lowest total labor cost among all objectives
 *
 * The test uses randomized employee and sales data and runs 5 iterations using @RepeatedTest
 * to ensure the optimization logic is robust across different scenarios.
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
     * Creates 10 diverse test employees with randomized productivity, pay rates, and availability.
     * This diversity ensures the optimizer has meaningful choices to make.
     */
    private fun createRandomizedTestEmployees(random: Random): List<Employee> {
        val employees = mutableListOf<Employee>()
        val employeeProfiles = listOf(
            "HighProdHighCost", "HighProdMedCost", "MedProdLowCost", "LowProdVeryLowCost",
            "MedProdMedCost", "VeryHighProdVeryHighCost", "LowProdLowCost", "HighProdLowCost",
            "MedLowProdMedCost", "VeryLowProdVeryLowCost"
        )

        employeeProfiles.forEachIndexed { index, name ->
            // Randomize productivity: 80-650 with variation
            val baseProductivity = 100.0 + (index * 50.0)
            val productivity = baseProductivity + random.nextDouble(-30.0, 80.0)

            // Randomize pay rate: $8-$55 with correlation to productivity
            val basePayRate = 8.0 + (index * 4.0)
            val payRate = basePayRate + random.nextDouble(-3.0, 8.0)

            // Randomize contract hours
            val contractedHours = if (random.nextBoolean()) 40.0 else 35.0
            val maxHours = contractedHours + random.nextInt(5, 16).toDouble()

            // Randomize availability - some employees have limited availability
            val availability = if (random.nextDouble() < 0.7) {
                // 70% have full week availability
                createFullWeekAvailability(random)
            } else {
                // 30% have partial week availability
                createPartialWeekAvailability(random)
            }

            employees.add(createEmployee(
                firstName = name,
                productivity = productivity.coerceIn(80.0, 650.0),
                payRate = payRate.coerceIn(8.0, 55.0),
                contractedHours = contractedHours,
                maxHours = maxHours,
                availability = availability
            ))
        }

        return employees
    }

    private fun createFullWeekAvailability(random: Random): List<Availability> {
        return listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                      DayOfWeek.THURSDAY, DayOfWeek.FRIDAY).map { day ->
            val startHour = random.nextInt(7, 11)  // Start between 7am-10am
            val endHour = random.nextInt(18, 23)   // End between 6pm-10pm
            Availability(day, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0))
        }
    }

    private fun createPartialWeekAvailability(random: Random): List<Availability> {
        val allDays = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val availableDays = allDays.shuffled(random).take(random.nextInt(2, 5))

        return availableDays.map { day ->
            val startHour = random.nextInt(8, 13)  // Start between 8am-12pm
            val endHour = random.nextInt(16, 21)   // End between 4pm-8pm
            Availability(day, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0))
        }
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
     * Creates a randomized weekly sales projection with varying demand throughout the week
     * and throughout each day.
     */
    private fun createRandomizedWeeklySalesForecast(random: Random): Map<DayOfWeek, Map<LocalTime, Double>> {
        val forecast = mutableMapOf<DayOfWeek, Map<LocalTime, Double>>()

        // Base hourly pattern with randomization
        val baseHourlyPattern = mapOf(
            8 to 200.0, 9 to 400.0, 10 to 600.0, 11 to 900.0,
            12 to 1500.0, 13 to 1400.0, 14 to 1100.0, 15 to 1000.0,
            16 to 800.0, 17 to 700.0, 18 to 500.0, 19 to 300.0,
            20 to 200.0, 21 to 100.0
        )

        // Randomize day multipliers
        val dayMultipliers = mapOf(
            DayOfWeek.MONDAY to (0.7 + random.nextDouble(0.0, 0.3)),
            DayOfWeek.TUESDAY to (0.8 + random.nextDouble(0.0, 0.3)),
            DayOfWeek.WEDNESDAY to (0.9 + random.nextDouble(0.0, 0.3)),
            DayOfWeek.THURSDAY to (1.0 + random.nextDouble(0.0, 0.3)),
            DayOfWeek.FRIDAY to (1.1 + random.nextDouble(0.0, 0.4))
        )

        listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
               DayOfWeek.THURSDAY, DayOfWeek.FRIDAY).forEach { day ->
            val dayMultiplier = dayMultipliers[day] ?: 1.0

            // Apply randomization to each hour
            val dayForecast = baseHourlyPattern.mapKeys { (hour, _) ->
                LocalTime.of(hour, 0)
            }.mapValues { (_, baseSales) ->
                val randomMultiplier = 0.8 + random.nextDouble(0.0, 0.4)  // ±20% variation
                (baseSales * dayMultiplier * randomMultiplier).coerceAtLeast(50.0)
            }
            forecast[day] = dayForecast
        }

        return forecast
    }

    @RepeatedTest(5, name = "Validation iteration {currentRepetition} of {totalRepetitions}")
    fun `optimization objectives should meet their optimization criteria with randomized data`(repetitionInfo: RepetitionInfo) {
        val iteration = repetitionInfo.currentRepetition
        val totalIterations = repetitionInfo.totalRepetitions

        println("\n╔════════════════════════════════════════╗")
        println("║    ITERATION $iteration OF $totalIterations                  ║")
        println("╚════════════════════════════════════════╝\n")

        // Use different seed for each iteration
        val random = Random(12345 + iteration)

        println("Step 1: Creating randomized test employees...")
        val employees = createRandomizedTestEmployees(random)
        println("Created ${employees.size} employees with randomized stats:")
        println("  Productivity range: ${"%.0f".format(employees.minOf { it.productivity })}-${"%.0f".format(employees.maxOf { it.productivity })}")
        println("  Pay rate range: $${"%.2f".format(employees.minOf { it.normalPayRate })}-$${"%.2f".format(employees.maxOf { it.normalPayRate })}")

        println("\nStep 2: Creating randomized weekly sales forecast...")
        val salesForecast = createRandomizedWeeklySalesForecast(random)
        salesForecastRepository.update(salesForecast)
        val totalExpectedSales = salesForecast.values.flatMap { it.values }.sum()
        println("Total expected sales across week: $${"%.2f".format(totalExpectedSales)}")

        // Define common input parameters
        println("\nStep 3: Defining scheduling parameters...")
        val laborCostBudget = 12000.0 + random.nextDouble(-2000.0, 5000.0)  // $10k-$17k
        println("Budget: $${"%.2f".format(laborCostBudget)}")

        val schedulePeriod = SchedulePeriod(
            daysToSchedule = listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
            ),
            operatingHours = mapOf(
                DayOfWeek.MONDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(22, 0)),
                DayOfWeek.TUESDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(22, 0)),
                DayOfWeek.WEDNESDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(22, 0)),
                DayOfWeek.THURSDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(22, 0)),
                DayOfWeek.FRIDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(22, 0))
            )
        )

        // Generate schedules for each optimization objective
        println("\nStep 4: Generating schedules for each optimization objective...")
        val objectives = listOf(
            OptimizationObjective.MAXIMIZE_SALES,
            OptimizationObjective.MINIMIZE_LABOR_COST,
            OptimizationObjective.BALANCED,
            OptimizationObjective.MAXIMIZE_FAIRNESS
        )

        val schedules = mutableMapOf<OptimizationObjective, Schedule>()

        objectives.forEach { objective ->
            val input = ScheduleInput(
                employeeIds = employees.map { it.id },
                laborCostBudget = laborCostBudget,
                schedulePeriod = schedulePeriod,
                optimizationObjective = objective
            )

            val schedule = scheduler.generateSchedule(input, name = "$objective Schedule")
            schedules[objective] = schedule
        }

        // Print metrics table
        println("\n  Metrics Comparison:")
        println("  ┌────────────────────────┬─────────────────┬─────────────────┐")
        println("  │ Objective              │ Labor Cost ($)  │ Est. Sales ($)  │")
        println("  ├────────────────────────┼─────────────────┼─────────────────┤")
        schedules.forEach { (objective, schedule) ->
            val objName = objective.toString().padEnd(22)
            val cost = "%,15.2f".format(schedule.metrics.totalLaborCost)
            val sales = "%,15.2f".format(schedule.metrics.estimatedTotalSales)
            println("  │ $objName │ $cost │ $sales │")
        }
        println("  └────────────────────────┴─────────────────┴─────────────────┘")

        // Validate optimization criteria
        val maxSalesSchedule = schedules[OptimizationObjective.MAXIMIZE_SALES]!!
        val minCostSchedule = schedules[OptimizationObjective.MINIMIZE_LABOR_COST]!!

        val allSales = schedules.map { it.value.metrics.estimatedTotalSales }
        val allCosts = schedules.map { it.value.metrics.totalLaborCost }

        val maxSales = allSales.max()
        val minCost = allCosts.min()

        println("\n  Validation Results:")
        println("  ├─ MAXIMIZE_SALES:")
        println("  │  Produced: $${"%.2f".format(maxSalesSchedule.metrics.estimatedTotalSales)}")
        println("  │  Expected: >= $${"%.2f".format(maxSales)} (highest among all)")

        println("  │")
        println("  ├─ MINIMIZE_LABOR_COST:")
        println("  │  Produced: $${"%.2f".format(minCostSchedule.metrics.totalLaborCost)}")
        println("  │  Expected: <= $${"%.2f".format(minCost)} (lowest among all)")

        // Assert validation criteria
        assertTrue(
            maxSalesSchedule.metrics.estimatedTotalSales >= maxSales - 0.01,
            "MAXIMIZE_SALES should produce the highest estimated sales among all objectives. " +
                    "Expected >= ${"%.2f".format(maxSales)}, " +
                    "but got ${"%.2f".format(maxSalesSchedule.metrics.estimatedTotalSales)}"
        )

        assertTrue(
            minCostSchedule.metrics.totalLaborCost <= minCost + 0.01,
            "MINIMIZE_LABOR_COST should produce the lowest labor cost among all objectives. " +
                    "Expected <= ${"%.2f".format(minCost)}, " +
                    "but got ${"%.2f".format(minCostSchedule.metrics.totalLaborCost)}"
        )

        println("\n  ✓ Iteration $iteration PASSED")
        println()
    }
}
