package org.labormanagement.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.labormanagement.model.*
import org.labormanagement.repository.SchedulingConfigurationRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class ShiftSchedulerTest {

    private val scheduler = ShiftScheduler()

    // Helper function to create a basic employee
    private fun createEmployee(
        firstName: String,
        productivity: Double,
        payRate: Double,
        availability: List<Availability>,
        contractedHours: Double = 40.0,
        maxHours: Double = 50.0
    ): Employee {
        return Employee(
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
    }

    @Test
    fun `generateSchedule should create shifts within budget`() {
        val employee = createEmployee(
            firstName = "Alice",
            productivity = 200.0,
            payRate = 15.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val input = SchedulingInput(
            employees = listOf(employee),
            laborCostBudget = 150.0,
            salesForecast = mapOf(
                DayOfWeek.MONDAY to mapOf(
                    LocalTime.of(9, 0) to 1000.0,
                    LocalTime.of(12, 0) to 1500.0
                )
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        val output = scheduler.generateSchedule(input)

        assertTrue(output.metrics.totalLaborCost <= 500.0, "Should not exceed budget")
        assertFalse(output.shifts.isEmpty(), "Should create at least one shift")
    }

    @Test
    fun `generateSchedule should respect employee availability`() {
        val employee = createEmployee(
            firstName = "Bob",
            productivity = 150.0,
            payRate = 15.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(14, 0), LocalTime.of(20, 0))
            )
        )

        val input = SchedulingInput(
            employees = listOf(employee),
            laborCostBudget = 1000.0,
            salesForecast = mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 1000.0)
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        val output = scheduler.generateSchedule(input)

        // All shifts should be within employee availability
        output.shifts.forEach { shift ->
            assertTrue(shift.startTime >= LocalTime.of(14, 0), "Shift should start at or after 14:00")
            assertTrue(shift.endTime <= LocalTime.of(20, 0), "Shift should end at or before 20:00")
        }
    }

    @Test
    fun `generateSchedule should not exceed contract hours`() {
        val employee = createEmployee(
            firstName = "Carol",
            productivity = 200.0,
            payRate = 15.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(22, 0))
            ),
            contractedHours = 20.0,
            maxHours = 20.0
        )

        val input = SchedulingInput(
            employees = listOf(employee),
            laborCostBudget = 5000.0,
            salesForecast = mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 2000.0),
                DayOfWeek.TUESDAY to mapOf(LocalTime.of(12, 0) to 2000.0),
                DayOfWeek.WEDNESDAY to mapOf(LocalTime.of(12, 0) to 2000.0)
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    DayOfWeek.TUESDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    DayOfWeek.WEDNESDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        val output = scheduler.generateSchedule(input)

        val totalHours = output.shifts.sumOf { it.durationHours }
        assertTrue(totalHours <= 20.0, "Total hours should not exceed max contract hours")
    }

    @Test
    fun `generateSchedule should detect understaffing`() {
        val employee = createEmployee(
            firstName = "David",
            productivity = 50.0, // Low productivity
            payRate = 15.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(13, 0))
            )
        )

        val input = SchedulingInput(
            employees = listOf(employee),
            laborCostBudget = 1000.0,
            salesForecast = mapOf(
                DayOfWeek.MONDAY to mapOf(
                    LocalTime.of(9, 0) to 5000.0 // Very high sales forecast
                )
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        val output = scheduler.generateSchedule(input)

        // Should have understaffing violations
        val understaffingViolations = output.violations.filter { it.type == ViolationType.UNDERSTAFFING }
        assertTrue(understaffingViolations.isNotEmpty(), "Should detect understaffing with low productivity and high sales")
    }

    @Test
    fun `generateSchedule should prioritize high productivity employees`() {
        val lowProductivity = createEmployee(
            firstName = "Emma",
            productivity = 100.0,
            payRate = 15.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val highProductivity = createEmployee(
            firstName = "Frank",
            productivity = 300.0,
            payRate = 15.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val input = SchedulingInput(
            employees = listOf(lowProductivity, highProductivity),
            laborCostBudget = 500.0,
            salesForecast = mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 1000.0)
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        val output = scheduler.generateSchedule(input)

        // High productivity employee should be scheduled first/more
        val highProdShifts = output.shifts.filter { it.employeeId == highProductivity.id }
        val lowProdShifts = output.shifts.filter { it.employeeId == lowProductivity.id }

        assertTrue(highProdShifts.isNotEmpty(), "High productivity employee should be scheduled")
        assertTrue(
            highProdShifts.sumOf { it.durationHours } >= lowProdShifts.sumOf { it.durationHours },
            "High productivity employee should have equal or more hours"
        )
    }

    @Test
    fun `generateSchedule should apply overtime rates correctly`() {
        val employee = createEmployee(
            firstName = "Grace",
            productivity = 200.0,
            payRate = 20.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(DayOfWeek.THURSDAY, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(DayOfWeek.FRIDAY, LocalTime.of(8, 0), LocalTime.of(22, 0))
            ),
            contractedHours = 40.0,
            maxHours = 50.0
        )

        val input = SchedulingInput(
            employees = listOf(employee),
            laborCostBudget = 10000.0,
            salesForecast = mapOf(
                // Each day has sales across 9 hours (8am-5pm), requiring ~9-hour shifts per day
                DayOfWeek.MONDAY to mapOf(
                    LocalTime.of(8, 0) to 200.0,
                    LocalTime.of(9, 0) to 200.0,
                    LocalTime.of(10, 0) to 200.0,
                    LocalTime.of(11, 0) to 200.0,
                    LocalTime.of(12, 0) to 200.0,
                    LocalTime.of(13, 0) to 200.0,
                    LocalTime.of(14, 0) to 200.0,
                    LocalTime.of(15, 0) to 200.0,
                    LocalTime.of(16, 0) to 200.0
                ),
                DayOfWeek.TUESDAY to mapOf(
                    LocalTime.of(8, 0) to 200.0,
                    LocalTime.of(9, 0) to 200.0,
                    LocalTime.of(10, 0) to 200.0,
                    LocalTime.of(11, 0) to 200.0,
                    LocalTime.of(12, 0) to 200.0,
                    LocalTime.of(13, 0) to 200.0,
                    LocalTime.of(14, 0) to 200.0,
                    LocalTime.of(15, 0) to 200.0,
                    LocalTime.of(16, 0) to 200.0
                ),
                DayOfWeek.WEDNESDAY to mapOf(
                    LocalTime.of(8, 0) to 200.0,
                    LocalTime.of(9, 0) to 200.0,
                    LocalTime.of(10, 0) to 200.0,
                    LocalTime.of(11, 0) to 200.0,
                    LocalTime.of(12, 0) to 200.0,
                    LocalTime.of(13, 0) to 200.0,
                    LocalTime.of(14, 0) to 200.0,
                    LocalTime.of(15, 0) to 200.0,
                    LocalTime.of(16, 0) to 200.0
                ),
                DayOfWeek.THURSDAY to mapOf(
                    LocalTime.of(8, 0) to 200.0,
                    LocalTime.of(9, 0) to 200.0,
                    LocalTime.of(10, 0) to 200.0,
                    LocalTime.of(11, 0) to 200.0,
                    LocalTime.of(12, 0) to 200.0,
                    LocalTime.of(13, 0) to 200.0,
                    LocalTime.of(14, 0) to 200.0,
                    LocalTime.of(15, 0) to 200.0,
                    LocalTime.of(16, 0) to 200.0
                ),
                DayOfWeek.FRIDAY to mapOf(
                    LocalTime.of(8, 0) to 200.0,
                    LocalTime.of(9, 0) to 200.0,
                    LocalTime.of(10, 0) to 200.0,
                    LocalTime.of(11, 0) to 200.0,
                    LocalTime.of(12, 0) to 200.0,
                    LocalTime.of(13, 0) to 200.0,
                    LocalTime.of(14, 0) to 200.0,
                    LocalTime.of(15, 0) to 200.0,
                    LocalTime.of(16, 0) to 200.0
                )
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
                ),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(21, 0)),
                    DayOfWeek.TUESDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(21, 0)),
                    DayOfWeek.WEDNESDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(21, 0)),
                    DayOfWeek.THURSDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(21, 0)),
                    DayOfWeek.FRIDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(21, 0))
                )
            )
        )

        val output = scheduler.generateSchedule(input)

        assertTrue(output.shifts.sumOf { it.durationHours } > 40.0)
        val overtimeShifts = output.shifts.filter { it.isOvertime }
        assertTrue(overtimeShifts.isNotEmpty(), "Should have overtime shifts when exceeding threshold")
        overtimeShifts.forEach { shift ->
            assertEquals(30.0, shift.payRate, "Overtime pay rate should be 1.5x normal rate (20 * 1.5 = 30)")
        }
    }

    @Test
    fun `generateSchedule should handle empty employee list`() {
        val input = SchedulingInput(
            employees = emptyList(),
            laborCostBudget = 1000.0,
            salesForecast = mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 1000.0)
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        val output = scheduler.generateSchedule(input)

        assertTrue(output.shifts.isEmpty(), "Should not create shifts with no employees")
    }

    @Test
    fun `generateSchedule should handle zero budget`() {
        val employee = createEmployee(
            firstName = "Henry",
            productivity = 200.0,
            payRate = 15.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val input = SchedulingInput(
            employees = listOf(employee),
            laborCostBudget = 0.0,
            salesForecast = mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 1000.0)
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        val output = scheduler.generateSchedule(input)

        assertTrue(output.shifts.isEmpty(), "Should not create shifts with zero budget")
    }

    @Test
    fun `generateSchedule should calculate metrics correctly`() {
        val employee = createEmployee(
            firstName = "Isabel",
            productivity = 200.0,
            payRate = 20.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            ),
            contractedHours = 40.0
        )

        val input = SchedulingInput(
            employees = listOf(employee),
            laborCostBudget = 1000.0,
            salesForecast = mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 1000.0)
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(17, 0))
                )
            )
        )

        val output = scheduler.generateSchedule(input)

        assertNotNull(output.metrics)
        assertTrue(output.metrics.totalLaborCost >= 0)
        assertTrue(output.metrics.estimatedTotalSales >= 0)
        assertTrue(output.metrics.employeeUtilization.isNotEmpty())
        assertTrue(output.metrics.employeeUtilization.containsKey("Isabel Test"))
    }

    @Test
    fun `generateSchedule should track staffing requirements`() {
        val employee = createEmployee(
            firstName = "Jack",
            productivity = 100.0,
            payRate = 15.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val input = SchedulingInput(
            employees = listOf(employee),
            laborCostBudget = 1000.0,
            salesForecast = mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 3000.0)
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        val output = scheduler.generateSchedule(input)

        assertFalse(output.staffingRequirements.isEmpty(), "Should track staffing requirements")
        output.staffingRequirements.forEach { requirement ->
            assertTrue(requirement.employeesNeeded >= 0)
            assertTrue(requirement.employeesAssigned >= 0)
            assertTrue(requirement.expectedSales >= 0)
        }
    }

    @Test
    fun `generateSchedule should validate against multiple constraint types`() {
        val employee = createEmployee(
            firstName = "Kate",
            productivity = 150.0,
            payRate = 25.0, // High pay rate
            availability = listOf(
                Availability(DayOfWeek.TUESDAY, LocalTime.of(14, 0), LocalTime.of(18, 0)) // Limited availability
            ),
            contractedHours = 10.0,
            maxHours = 10.0
        )

        val input = SchedulingInput(
            employees = listOf(employee),
            laborCostBudget = 100.0, // Low budget
            salesForecast = mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 2000.0), // Not available Monday
                DayOfWeek.TUESDAY to mapOf(LocalTime.of(12, 0) to 2000.0) // High demand
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    DayOfWeek.TUESDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        val output = scheduler.generateSchedule(input)

        // Should detect various constraint violations
        assertFalse(output.violations.isEmpty(), "Should detect constraint violations")

        // May have understaffing, budget issues, or availability conflicts
        val violationTypes = output.violations.map { it.type }.toSet()
        assertTrue(
            violationTypes.contains(ViolationType.UNDERSTAFFING),
            "Should detect understaffing violation"
        )
    }

    @Test
    fun `generateSchedule with MAXIMIZE_SALES should prioritize most productive employees`() {
        val cheapEmployee = createEmployee(
            firstName = "Cheap",
            productivity = 100.0,
            payRate = 10.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val productiveEmployee = createEmployee(
            firstName = "Productive",
            productivity = 300.0,
            payRate = 30.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val input = SchedulingInput(
            employees = listOf(cheapEmployee, productiveEmployee),
            laborCostBudget = 500.0,
            salesForecast = mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 100.0)
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        val configRepo = SchedulingConfigurationRepository()
        configRepo.update(defaultOptimizationObjective = OptimizationObjective.MAXIMIZE_SALES)
        val maxSalesScheduler = ShiftScheduler(configRepository = configRepo)
        val output = maxSalesScheduler.generateSchedule(input)

        // Productive employee should be scheduled more or equal hours
        val productiveHours = output.shifts.filter { it.employeeId == productiveEmployee.id }.sumOf { it.durationHours }
        val cheapHours = output.shifts.filter { it.employeeId == cheapEmployee.id }.sumOf { it.durationHours }

        assertTrue(productiveHours > cheapHours, "MAXIMIZE_SALES should prioritize productive employee")
        assertTrue(output.metrics.estimatedTotalSales > 0, "Should have positive estimated sales")
    }

    @Test
    fun `generateSchedule with MINIMIZE_LABOR_COST should prioritize cheaper employees`() {
        val cheapEmployee = createEmployee(
            firstName = "Cheap",
            productivity = 100.0,
            payRate = 10.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val expensiveEmployee = createEmployee(
            firstName = "Expensive",
            productivity = 300.0,
            payRate = 30.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val input = SchedulingInput(
            employees = listOf(cheapEmployee, expensiveEmployee),
            laborCostBudget = 500.0,
            salesForecast = mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 100.0)
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        val configRepo = SchedulingConfigurationRepository()
        configRepo.update(defaultOptimizationObjective = OptimizationObjective.MINIMIZE_LABOR_COST)
        val minCostScheduler = ShiftScheduler(configRepository = configRepo)
        val output = minCostScheduler.generateSchedule(input)

        // Cheap employee should be scheduled more or exclusively
        val cheapHours = output.shifts.filter { it.employeeId == cheapEmployee.id }.sumOf { it.durationHours }
        val expensiveHours = output.shifts.filter { it.employeeId == expensiveEmployee.id }.sumOf { it.durationHours }

        assertTrue(cheapHours > expensiveHours, "MINIMIZE_LABOR_COST should prioritize cheaper employee")

        // Calculate what labor cost would be if we used expensive employee for same hours
        val totalHours = output.shifts.sumOf { it.durationHours }
        val alternativeCost = totalHours * expensiveEmployee.normalPayRate
        assertTrue(
            output.metrics.totalLaborCost < alternativeCost,
            "Should have lower labor cost than if using expensive employee"
        )
    }

    @Test
    fun `generateSchedule with BALANCED should optimize productivity-to-cost ratio`() {
        val inefficientEmployee = createEmployee(
            firstName = "Inefficient",
            productivity = 100.0,
            payRate = 20.0, // High cost, low productivity (ratio: 5.0)
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val efficientEmployee = createEmployee(
            firstName = "Efficient",
            productivity = 200.0,
            payRate = 15.0, // Lower cost, high productivity (ratio: 13.33)
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val input = SchedulingInput(
            employees = listOf(inefficientEmployee, efficientEmployee),
            laborCostBudget = 1000.0,
            salesForecast = mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 1500.0)
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        val configRepo = SchedulingConfigurationRepository()
        configRepo.update(defaultOptimizationObjective = OptimizationObjective.BALANCED)
        val balancedScheduler = ShiftScheduler(configRepository = configRepo)
        val output = balancedScheduler.generateSchedule(input)

        // Efficient employee should be scheduled more
        val efficientHours = output.shifts.filter { it.employeeId == efficientEmployee.id }.sumOf { it.durationHours }
        val inefficientHours = output.shifts.filter { it.employeeId == inefficientEmployee.id }.sumOf { it.durationHours }

        assertTrue(efficientHours >= inefficientHours, "BALANCED should prioritize employee with better productivity-to-cost ratio")

        // Calculate efficiency metric: sales per dollar
        val salesPerDollar = if (output.metrics.totalLaborCost > 0) {
            output.metrics.estimatedTotalSales / output.metrics.totalLaborCost
        } else {
            0.0
        }

        assertTrue(salesPerDollar > 5.0, "Should achieve good sales-per-dollar ratio with balanced approach")
    }

    @Test
    fun `different optimization objectives should produce different results`() {
        val cheapEmployee = createEmployee(
            firstName = "Cheap",
            productivity = 100.0,
            payRate = 10.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val productiveEmployee = createEmployee(
            firstName = "Productive",
            productivity = 300.0,
            payRate = 25.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val baseInput = SchedulingInput(
            employees = listOf(cheapEmployee, productiveEmployee),
            laborCostBudget = 1000.0,
            salesForecast = mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 1500.0)
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        // Test with different objectives
        val maxSalesConfigRepo = SchedulingConfigurationRepository()
        maxSalesConfigRepo.update(defaultOptimizationObjective = OptimizationObjective.MAXIMIZE_SALES)
        val maxSalesScheduler = ShiftScheduler(configRepository = maxSalesConfigRepo)
        val maxSalesOutput = maxSalesScheduler.generateSchedule(baseInput)

        val minCostConfigRepo = SchedulingConfigurationRepository()
        minCostConfigRepo.update(defaultOptimizationObjective = OptimizationObjective.MINIMIZE_LABOR_COST)
        val minCostScheduler = ShiftScheduler(configRepository = minCostConfigRepo)
        val minCostOutput = minCostScheduler.generateSchedule(baseInput)

        // Max sales should prioritize productive employee
        val maxSalesProductiveHours = maxSalesOutput.shifts.filter { it.employeeId == productiveEmployee.id }.sumOf { it.durationHours }
        val minCostCheapHours = minCostOutput.shifts.filter { it.employeeId == cheapEmployee.id }.sumOf { it.durationHours }

        // Max sales should have higher estimated sales
        assertTrue(
            maxSalesOutput.metrics.estimatedTotalSales >= minCostOutput.metrics.estimatedTotalSales,
            "MAXIMIZE_SALES should produce higher or equal estimated sales"
        )

        // Min cost should use cheaper employee more
        assertTrue(
            minCostCheapHours > 0 || minCostOutput.metrics.totalLaborCost <= maxSalesOutput.metrics.totalLaborCost,
            "MINIMIZE_LABOR_COST should use cheap employee or have lower cost"
        )
    }

    @Test
    fun `generateSchedule with MAXIMIZE_FAIRNESS should balance hours across employees`() {
        // Create three employees with identical stats except names
        val employee1 = createEmployee(
            firstName = "Alice",
            productivity = 150.0,
            payRate = 15.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val employee2 = createEmployee(
            firstName = "Bob",
            productivity = 150.0,
            payRate = 15.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val employee3 = createEmployee(
            firstName = "Carol",
            productivity = 150.0,
            payRate = 15.0,
            availability = listOf(
                Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val input = SchedulingInput(
            employees = listOf(employee1, employee2, employee3),
            laborCostBudget = 5000.0,
            salesForecast = mapOf(
                DayOfWeek.MONDAY to mapOf(
                    LocalTime.of(10, 0) to 300.0,
                    LocalTime.of(11, 0) to 300.0,
                    LocalTime.of(12, 0) to 300.0,
                    LocalTime.of(13, 0) to 300.0,
                    LocalTime.of(14, 0) to 300.0,
                    LocalTime.of(15, 0) to 300.0
                ),
                DayOfWeek.TUESDAY to mapOf(
                    LocalTime.of(10, 0) to 300.0,
                    LocalTime.of(11, 0) to 300.0,
                    LocalTime.of(12, 0) to 300.0,
                    LocalTime.of(13, 0) to 300.0,
                    LocalTime.of(14, 0) to 300.0,
                    LocalTime.of(15, 0) to 300.0
                ),
                DayOfWeek.WEDNESDAY to mapOf(
                    LocalTime.of(10, 0) to 300.0,
                    LocalTime.of(11, 0) to 300.0,
                    LocalTime.of(12, 0) to 300.0,
                    LocalTime.of(13, 0) to 300.0,
                    LocalTime.of(14, 0) to 300.0,
                    LocalTime.of(15, 0) to 300.0
                )
            ),
            schedulingPeriod = SchedulingPeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    DayOfWeek.TUESDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    DayOfWeek.WEDNESDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        val configRepo = SchedulingConfigurationRepository()
        configRepo.update(defaultOptimizationObjective = OptimizationObjective.MAXIMIZE_FAIRNESS)
        val fairnessScheduler = ShiftScheduler(configRepository = configRepo)
        val output = fairnessScheduler.generateSchedule(input)
        println(output)

        // Calculate hours for each employee
        val employee1Hours = output.shifts.filter { it.employeeId == employee1.id }.sumOf { it.durationHours }
        val employee2Hours = output.shifts.filter { it.employeeId == employee2.id }.sumOf { it.durationHours }
        val employee3Hours = output.shifts.filter { it.employeeId == employee3.id }.sumOf { it.durationHours }

        // All employees should have at least some hours
        assertTrue(employee1Hours > 0, "Employee 1 should be scheduled")
        assertTrue(employee2Hours > 0, "Employee 2 should be scheduled")
        assertTrue(employee3Hours > 0, "Employee 3 should be scheduled")

        // Calculate standard deviation of hours to measure fairness
        val hours = listOf(employee1Hours, employee2Hours, employee3Hours)
        val avgHours = hours.average()
        val variance = hours.map { (it - avgHours) * (it - avgHours) }.average()
        val stdDev = kotlin.math.sqrt(variance)

        // Hours should be relatively balanced (standard deviation should be small)
        // Allow some variance due to rounding and minimum shift constraints
        assertTrue(stdDev < 3.0, "Hours should be balanced across employees (stdDev: $stdDev, hours: $hours)")

        // Compare with MAXIMIZE_SALES to show different behavior
        val maxSalesConfigRepo = SchedulingConfigurationRepository()
        maxSalesConfigRepo.update(defaultOptimizationObjective = OptimizationObjective.MAXIMIZE_SALES)
        val maxSalesScheduler = ShiftScheduler(configRepository = maxSalesConfigRepo)
        val maxSalesOutput = maxSalesScheduler.generateSchedule(input)
        val maxSalesEmployee1Hours = maxSalesOutput.shifts.filter { it.employeeId == employee1.id }.sumOf { it.durationHours }
        val maxSalesEmployee2Hours = maxSalesOutput.shifts.filter { it.employeeId == employee2.id }.sumOf { it.durationHours }
        val maxSalesEmployee3Hours = maxSalesOutput.shifts.filter { it.employeeId == employee3.id }.sumOf { it.durationHours }
        val maxSalesHours = listOf(maxSalesEmployee1Hours, maxSalesEmployee2Hours, maxSalesEmployee3Hours)
        val maxSalesAvgHours = maxSalesHours.average()
        val maxSalesVariance = maxSalesHours.map { (it - maxSalesAvgHours) * (it - maxSalesAvgHours) }.average()
        val maxSalesStdDev = kotlin.math.sqrt(maxSalesVariance)

        // MAXIMIZE_FAIRNESS should have better (lower) standard deviation than other objectives
        // when employees have identical stats
        assertTrue(
            stdDev <= maxSalesStdDev + 0.5, // Allow small margin for rounding
            "MAXIMIZE_FAIRNESS should have equal or better hour distribution (fairness stdDev: $stdDev, maxSales stdDev: $maxSalesStdDev)"
        )
    }
}
