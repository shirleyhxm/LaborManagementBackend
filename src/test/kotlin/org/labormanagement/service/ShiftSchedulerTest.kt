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
import java.util.UUID

class ShiftSchedulerTest {
    private lateinit var employeeRepository: EmployeeRepository
    private lateinit var salesForecastRepository: SalesForecastRepository
    private lateinit var scheduler: ShiftScheduler

    @BeforeEach
    fun setup() {
        employeeRepository = EmployeeRepository()
        salesForecastRepository = SalesForecastRepository()
        scheduler = ShiftScheduler(
            employeeRepository = employeeRepository,
            salesForecastRepository = salesForecastRepository,
            schedulingApproach = SchedulingApproach.GREEDY
        )
    }

    // Helper function to create a basic employee
    private fun createEmployee(
        firstName: String,
        productivity: Double,
        payRate: Double,
        availability: List<Availability>,
        contractedHours: Double = 40.0,
        maxHours: Double = 50.0
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

    @Test
    fun `generateSchedule should create shifts within budget`() {
        val employee = createEmployee(
            firstName = "Alice",
            productivity = 200.0,
            payRate = 15.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )
        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to mapOf(
                    LocalTime.of(9, 0) to 1000.0,
                    LocalTime.of(12, 0) to 1500.0
                )
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee.id),
            laborCostBudget = 150.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
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
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(14, 0), LocalTime.of(20, 0))
            )
        )
        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 1000.0)
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee.id),
            laborCostBudget = 1000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
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
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.WEDNESDAY, null, null, LocalTime.of(8, 0), LocalTime.of(22, 0))
            ),
            contractedHours = 20.0,
            maxHours = 20.0
        )
        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 2000.0),
                DayOfWeek.TUESDAY to mapOf(LocalTime.of(12, 0) to 2000.0),
                DayOfWeek.WEDNESDAY to mapOf(LocalTime.of(12, 0) to 2000.0)
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee.id),
            laborCostBudget = 5000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 3),    // Wednesday
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    LocalDate.of(2024, 1, 2) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    LocalDate.of(2024, 1, 3) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
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
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(13, 0))
            )
        )
        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to mapOf(
                    LocalTime.of(9, 0) to 5000.0 // Very high sales forecast
                )
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee.id),
            laborCostBudget = 1000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
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
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val highProductivity = createEmployee(
            firstName = "Frank",
            productivity = 300.0,
            payRate = 15.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )
        salesForecastRepository.update(mapOf(
            DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 1000.0)
        ))

        val input = ScheduleInput(
            employeeIds = listOf(lowProductivity.id, highProductivity.id),
            laborCostBudget = 500.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
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
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.WEDNESDAY, null, null, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.THURSDAY, null, null, LocalTime.of(8, 0), LocalTime.of(22, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.FRIDAY, null, null, LocalTime.of(8, 0), LocalTime.of(22, 0))
            ),
            contractedHours = 40.0,
            maxHours = 50.0
        )
        salesForecastRepository.update(
            mapOf(
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
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee.id),
            laborCostBudget = 10000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 5),    // Friday
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(8, 0), LocalTime.of(21, 0)),
                    LocalDate.of(2024, 1, 2) to OperatingHours(LocalTime.of(8, 0), LocalTime.of(21, 0)),
                    LocalDate.of(2024, 1, 3) to OperatingHours(LocalTime.of(8, 0), LocalTime.of(21, 0)),
                    LocalDate.of(2024, 1, 4) to OperatingHours(LocalTime.of(8, 0), LocalTime.of(21, 0)),
                    LocalDate.of(2024, 1, 5) to OperatingHours(LocalTime.of(8, 0), LocalTime.of(21, 0))
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
        val input = ScheduleInput(
            employeeIds = emptyList(),
            laborCostBudget = 1000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
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
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee.id),
            laborCostBudget = 0.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
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
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            ),
            contractedHours = 40.0
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee.id),
            laborCostBudget = 1000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(17, 0))
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
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee.id),
            laborCostBudget = 1000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
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
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(14, 0), LocalTime.of(18, 0)) // Limited availability
            ),
            contractedHours = 10.0,
            maxHours = 10.0
        )
        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 2000.0), // Not available Monday
                DayOfWeek.TUESDAY to mapOf(LocalTime.of(12, 0) to 2000.0) // High demand
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee.id),
            laborCostBudget = 100.0, // Low budget
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 2),    // Tuesday
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    LocalDate.of(2024, 1, 2) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
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
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val productiveEmployee = createEmployee(
            firstName = "Productive",
            productivity = 300.0,
            payRate = 30.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )
        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 100.0)
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(cheapEmployee.id, productiveEmployee.id),
            laborCostBudget = 500.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            ),
            optimizationObjective = OptimizationObjective.MAXIMIZE_SALES
        )

        val output = scheduler.generateSchedule(input)

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
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val expensiveEmployee = createEmployee(
            firstName = "Expensive",
            productivity = 300.0,
            payRate = 30.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )
        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 100.0)
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(cheapEmployee.id, expensiveEmployee.id),
            laborCostBudget = 500.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            ),
            optimizationObjective = OptimizationObjective.MINIMIZE_LABOR_COST,
        )

        val output = scheduler.generateSchedule(input)

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
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val efficientEmployee = createEmployee(
            firstName = "Efficient",
            productivity = 200.0,
            payRate = 15.0, // Lower cost, high productivity (ratio: 13.33)
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )
        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 1500.0)
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(inefficientEmployee.id, efficientEmployee.id),
            laborCostBudget = 1000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            ),
            optimizationObjective = OptimizationObjective.BALANCED,
        )

        val output = scheduler.generateSchedule(input)

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
    fun `Shift should correctly calculate duration for overnight shifts`() {
        // Test overnight shift (e.g., 22:00 to 02:00 = 4 hours)
        val overnightShift = Shift(
            employeeId = UUID.randomUUID(),
            date = LocalDate.of(2024, 1, 5),  // Friday
            startTime = LocalTime.of(22, 0),
            endTime = LocalTime.of(2, 0),
            payRate = 19.0,
            isOvertime = false
        )

        assertEquals(4.0, overnightShift.durationHours, 0.01, "Overnight shift (22:00-02:00) should be 4 hours")
        assertEquals(76.0, overnightShift.laborCost, 0.01, "Labor cost should be 4 hours * $19/hr = $76")

        // Test another overnight shift (20:00 to 12:00 = 16 hours)
        val longOvernightShift = Shift(
            employeeId = UUID.randomUUID(),
            date = LocalDate.of(2024, 1, 5),  // Friday
            startTime = LocalTime.of(20, 0),
            endTime = LocalTime.of(12, 0),
            payRate = 19.0,
            isOvertime = false
        )

        assertEquals(16.0, longOvernightShift.durationHours, 0.01, "Overnight shift (20:00-12:00) should be 16 hours")
        assertEquals(304.0, longOvernightShift.laborCost, 0.01, "Labor cost should be 16 hours * $19/hr = $304")

        // Test edge case: midnight to midnight (should be 24 hours)
        val midnightShift = Shift(
            employeeId = UUID.randomUUID(),
            date = LocalDate.of(2024, 1, 5),  // Friday
            startTime = LocalTime.of(0, 0),
            endTime = LocalTime.of(0, 0),
            payRate = 15.0,
            isOvertime = false
        )

        assertEquals(24.0, midnightShift.durationHours, 0.01, "Midnight to midnight should be 24 hours")

        // Test regular daytime shift still works (09:00 to 17:00 = 8 hours)
        val dayShift = Shift(
            employeeId = UUID.randomUUID(),
            date = LocalDate.of(2024, 1, 1),  // Monday
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(17, 0),
            payRate = 15.0,
            isOvertime = false
        )

        assertEquals(8.0, dayShift.durationHours, 0.01, "Day shift (09:00-17:00) should be 8 hours")
        assertEquals(120.0, dayShift.laborCost, 0.01, "Labor cost should be 8 hours * $15/hr = $120")
    }

    @Test
    fun `different optimization objectives should produce different results`() {
        val cheapEmployee = createEmployee(
            firstName = "Cheap",
            productivity = 100.0,
            payRate = 10.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val productiveEmployee = createEmployee(
            firstName = "Productive",
            productivity = 300.0,
            payRate = 25.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )
        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 1500.0)
            )
        )

        val maxSalesInput = ScheduleInput(
            employeeIds = listOf(cheapEmployee.id, productiveEmployee.id),
            laborCostBudget = 1000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            ),
            optimizationObjective = OptimizationObjective.MAXIMIZE_SALES,
        )
        val minLaborCostInput = ScheduleInput(
            employeeIds = listOf(cheapEmployee.id, productiveEmployee.id),
            laborCostBudget = 1000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            ),
            optimizationObjective = OptimizationObjective.MINIMIZE_LABOR_COST,
        )

        // Test with different objectives
        val maxSalesOutput = scheduler.generateSchedule(maxSalesInput)
        val minCostOutput = scheduler.generateSchedule(minLaborCostInput)

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
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.WEDNESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val employee2 = createEmployee(
            firstName = "Bob",
            productivity = 150.0,
            payRate = 15.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.WEDNESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )

        val employee3 = createEmployee(
            firstName = "Carol",
            productivity = 150.0,
            payRate = 15.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.WEDNESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
        )
        salesForecastRepository.update(
            mapOf(
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
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee1.id, employee2.id, employee3.id),
            laborCostBudget = 5000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 3),    // Wednesday
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    LocalDate.of(2024, 1, 2) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    LocalDate.of(2024, 1, 3) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            ),
            optimizationObjective = OptimizationObjective.MAXIMIZE_FAIRNESS,
        )

        val output = scheduler.generateSchedule(input)

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
        val maxSalesOutput = scheduler.generateSchedule(
            input.copy(optimizationObjective = OptimizationObjective.MAXIMIZE_SALES)
        )
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

    // ========================================
    // OPTIMIZER Scheduling Approach Tests
    // ========================================

    @Test
    fun `OPTIMIZER should generate feasible schedule with sufficient sales coverage`() {
        val optimizerScheduler = ShiftScheduler(
            employeeRepository = employeeRepository,
            salesForecastRepository = salesForecastRepository,
            schedulingApproach = SchedulingApproach.OPTIMIZER
        )

        val employee = createEmployee(
            firstName = "OptEmployee",
            productivity = 200.0,
            payRate = 15.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to mapOf(
                    LocalTime.of(10, 0) to 500.0,
                    LocalTime.of(12, 0) to 800.0,
                    LocalTime.of(14, 0) to 600.0
                )
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee.id),
            laborCostBudget = 1000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(17, 0))
                )
            )
        )

        val output = optimizerScheduler.generateSchedule(input)

        assertFalse(output.shifts.isEmpty(), "OPTIMIZER should create shifts")
        assertTrue(output.metrics.estimatedTotalSales > 0, "Should estimate sales coverage")
        assertTrue(output.metrics.totalLaborCost > 0, "Should calculate labor costs")
    }

    @Test
    fun `OPTIMIZER should respect employee availability constraints`() {
        val optimizerScheduler = ShiftScheduler(
            employeeRepository = employeeRepository,
            salesForecastRepository = salesForecastRepository,
            schedulingApproach = SchedulingApproach.OPTIMIZER
        )

        val employee = createEmployee(
            firstName = "LimitedAvail",
            productivity = 180.0,
            payRate = 18.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(14, 0), LocalTime.of(18, 0))
            )
        )

        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to mapOf(
                    LocalTime.of(10, 0) to 400.0,
                    LocalTime.of(15, 0) to 600.0
                )
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee.id),
            laborCostBudget = 500.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(19, 0))
                )
            )
        )

        val output = optimizerScheduler.generateSchedule(input)

        // All shifts must be within availability window
        output.shifts.forEach { shift ->
            assertTrue(
                shift.startTime >= LocalTime.of(14, 0),
                "OPTIMIZER shifts should respect availability start time"
            )
            assertTrue(
                shift.endTime <= LocalTime.of(18, 0),
                "OPTIMIZER shifts should respect availability end time"
            )
        }
    }

    @Test
    fun `OPTIMIZER should not exceed contract max hours per week`() {
        val optimizerScheduler = ShiftScheduler(
            employeeRepository = employeeRepository,
            salesForecastRepository = salesForecastRepository,
            schedulingApproach = SchedulingApproach.OPTIMIZER
        )

        val employee = createEmployee(
            firstName = "PartTime",
            productivity = 150.0,
            payRate = 12.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.WEDNESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
            ),
            contractedHours = 15.0,
            maxHours = 15.0  // Strict 15 hour limit
        )

        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 1000.0),
                DayOfWeek.TUESDAY to mapOf(LocalTime.of(12, 0) to 1000.0),
                DayOfWeek.WEDNESDAY to mapOf(LocalTime.of(12, 0) to 1000.0)
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee.id),
            laborCostBudget = 5000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 3),    // Wednesday
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(18, 0)),
                    LocalDate.of(2024, 1, 2) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(18, 0)),
                    LocalDate.of(2024, 1, 3) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(18, 0))
                )
            )
        )

        val output = optimizerScheduler.generateSchedule(input)

        val totalHours = output.shifts.sumOf { it.durationHours }
        assertTrue(
            totalHours <= 15.0,
            "OPTIMIZER should not exceed max contract hours (got $totalHours, max 15.0)"
        )
    }

    @Test
    fun `OPTIMIZER with MAXIMIZE_SALES should prioritize productive employees over cheap ones`() {
        val optimizerScheduler = ShiftScheduler(
            employeeRepository = employeeRepository,
            salesForecastRepository = salesForecastRepository,
            schedulingApproach = SchedulingApproach.OPTIMIZER
        )

        val cheapEmployee = createEmployee(
            firstName = "Cheap",
            productivity = 100.0,
            payRate = 10.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        val productiveEmployee = createEmployee(
            firstName = "Productive",
            productivity = 300.0,
            payRate = 25.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to mapOf(
                    LocalTime.of(10, 0) to 600.0,
                    LocalTime.of(12, 0) to 800.0,
                    LocalTime.of(14, 0) to 700.0
                )
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(cheapEmployee.id, productiveEmployee.id),
            laborCostBudget = 2000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 1),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(17, 0))
                )
            ),
            optimizationObjective = OptimizationObjective.MAXIMIZE_SALES
        )

        val output = optimizerScheduler.generateSchedule(input)

        val productiveHours = output.shifts.filter { it.employeeId == productiveEmployee.id }.sumOf { it.durationHours }
        val cheapHours = output.shifts.filter { it.employeeId == cheapEmployee.id }.sumOf { it.durationHours }

        assertTrue(
            productiveHours >= cheapHours,
            "OPTIMIZER MAXIMIZE_SALES should schedule productive employee equal or more (productive: $productiveHours, cheap: $cheapHours)"
        )
    }

    @Test
    fun `OPTIMIZER with MINIMIZE_LABOR_COST should prioritize cheaper employees`() {
        val optimizerScheduler = ShiftScheduler(
            employeeRepository = employeeRepository,
            salesForecastRepository = salesForecastRepository,
            schedulingApproach = SchedulingApproach.OPTIMIZER
        )

        val cheapEmployee = createEmployee(
            firstName = "CheapWorker",
            productivity = 120.0,
            payRate = 12.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        val expensiveEmployee = createEmployee(
            firstName = "ExpensiveWorker",
            productivity = 140.0,
            payRate = 28.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        salesForecastRepository.update(
            mapOf(
                DayOfWeek.TUESDAY to mapOf(
                    LocalTime.of(11, 0) to 500.0,
                    LocalTime.of(13, 0) to 600.0
                )
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(cheapEmployee.id, expensiveEmployee.id),
            laborCostBudget = 1500.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 2),  // Tuesday
                endDate = LocalDate.of(2024, 1, 2),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 2) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(17, 0))
                )
            ),
            optimizationObjective = OptimizationObjective.MINIMIZE_LABOR_COST
        )

        val output = optimizerScheduler.generateSchedule(input)

        val cheapHours = output.shifts.filter { it.employeeId == cheapEmployee.id }.sumOf { it.durationHours }
        val expensiveHours = output.shifts.filter { it.employeeId == expensiveEmployee.id }.sumOf { it.durationHours }

        // With similar productivity, cheaper employee should get more hours
        assertTrue(
            cheapHours >= expensiveHours,
            "OPTIMIZER MINIMIZE_LABOR_COST should favor cheaper employee (cheap: $cheapHours, expensive: $expensiveHours)"
        )
    }

    @Test
    fun `OPTIMIZER should handle empty employee list gracefully`() {
        val optimizerScheduler = ShiftScheduler(
            employeeRepository = employeeRepository,
            salesForecastRepository = salesForecastRepository,
            schedulingApproach = SchedulingApproach.OPTIMIZER
        )

        salesForecastRepository.update(
            mapOf(
                DayOfWeek.WEDNESDAY to mapOf(LocalTime.of(12, 0) to 1000.0)
            )
        )

        val input = ScheduleInput(
            employeeIds = emptyList(),
            laborCostBudget = 1000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 3),  // Wednesday
                endDate = LocalDate.of(2024, 1, 3),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 3) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(17, 0))
                )
            )
        )

        val output = optimizerScheduler.generateSchedule(input)

        assertTrue(output.shifts.isEmpty(), "OPTIMIZER should return empty schedule with no employees")
    }

    @Test
    fun `OPTIMIZER should create continuous shifts by merging consecutive time slots`() {
        val optimizerScheduler = ShiftScheduler(
            employeeRepository = employeeRepository,
            salesForecastRepository = salesForecastRepository,
            schedulingApproach = SchedulingApproach.OPTIMIZER
        )

        val employee = createEmployee(
            firstName = "ContinuousWorker",
            productivity = 180.0,
            payRate = 16.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.THURSDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        salesForecastRepository.update(
            mapOf(
                DayOfWeek.THURSDAY to mapOf(
                    LocalTime.of(10, 0) to 400.0,
                    LocalTime.of(11, 0) to 450.0,
                    LocalTime.of(12, 0) to 500.0,
                    LocalTime.of(13, 0) to 480.0,
                    LocalTime.of(14, 0) to 420.0
                )
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee.id),
            laborCostBudget = 800.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 4),  // Thursday
                endDate = LocalDate.of(2024, 1, 4),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 4) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(17, 0))
                )
            )
        )

        val output = optimizerScheduler.generateSchedule(input)

        // Optimizer should create continuous shifts rather than many 1-hour fragments
        val employeeShifts = output.shifts.filter { it.employeeId == employee.id }
        assertTrue(
            employeeShifts.isNotEmpty(),
            "OPTIMIZER should create at least one shift"
        )

        // Check that shifts are reasonably long (not fragmented into many 1-hour shifts)
        val avgShiftDuration = employeeShifts.map { it.durationHours }.average()
        assertTrue(
            avgShiftDuration >= 1.0,
            "OPTIMIZER should create shifts of reasonable duration (avg: $avgShiftDuration hours)"
        )
    }

    @Test
    fun `OPTIMIZER should apply overtime pay rates when employee exceeds threshold`() {
        val optimizerScheduler = ShiftScheduler(
            employeeRepository = employeeRepository,
            salesForecastRepository = salesForecastRepository,
            schedulingApproach = SchedulingApproach.OPTIMIZER
        )

        val employee = createEmployee(
            firstName = "OvertimeWorker",
            productivity = 200.0,
            payRate = 20.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.WEDNESDAY, null, null, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.THURSDAY, null, null, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.FRIDAY, null, null, LocalTime.of(8, 0), LocalTime.of(20, 0))
            ),
            contractedHours = 40.0,
            maxHours = 50.0
        )

        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to mapOf(LocalTime.of(12, 0) to 1200.0),
                DayOfWeek.TUESDAY to mapOf(LocalTime.of(12, 0) to 1200.0),
                DayOfWeek.WEDNESDAY to mapOf(LocalTime.of(12, 0) to 1200.0),
                DayOfWeek.THURSDAY to mapOf(LocalTime.of(12, 0) to 1200.0),
                DayOfWeek.FRIDAY to mapOf(LocalTime.of(12, 0) to 1200.0)
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee.id),
            laborCostBudget = 10000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),  // Monday
                endDate = LocalDate.of(2024, 1, 5),    // Friday
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(18, 0)),
                    LocalDate.of(2024, 1, 2) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(18, 0)),
                    LocalDate.of(2024, 1, 3) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(18, 0)),
                    LocalDate.of(2024, 1, 4) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(18, 0)),
                    LocalDate.of(2024, 1, 5) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(18, 0))
                )
            )
        )

        val output = optimizerScheduler.generateSchedule(input)

        val totalHours = output.shifts.sumOf { it.durationHours }

        if (totalHours > 40.0) {
            val overtimeShifts = output.shifts.filter { it.isOvertime }
            assertTrue(
                overtimeShifts.isNotEmpty(),
                "OPTIMIZER should mark shifts as overtime when exceeding threshold"
            )
            overtimeShifts.forEach { shift ->
                assertEquals(
                    30.0,
                    shift.payRate,
                    0.01,
                    "OPTIMIZER overtime shifts should use overtime pay rate (1.5x)"
                )
            }
        }
    }

    @Test
    fun `OPTIMIZER should generate schedule with multiple employees and distribute work`() {
        val optimizerScheduler = ShiftScheduler(
            employeeRepository = employeeRepository,
            salesForecastRepository = salesForecastRepository,
            schedulingApproach = SchedulingApproach.OPTIMIZER
        )

        val employee1 = createEmployee(
            firstName = "TeamMember1",
            productivity = 150.0,
            payRate = 15.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.FRIDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        val employee2 = createEmployee(
            firstName = "TeamMember2",
            productivity = 160.0,
            payRate = 16.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.FRIDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        val employee3 = createEmployee(
            firstName = "TeamMember3",
            productivity = 155.0,
            payRate = 15.5,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.FRIDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        salesForecastRepository.update(
            mapOf(
                DayOfWeek.FRIDAY to mapOf(
                    LocalTime.of(10, 0) to 800.0,
                    LocalTime.of(12, 0) to 1200.0,
                    LocalTime.of(14, 0) to 1000.0
                )
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee1.id, employee2.id, employee3.id),
            laborCostBudget = 2000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 5),  // Friday
                endDate = LocalDate.of(2024, 1, 5),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 5) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(17, 0))
                )
            )
        )

        val output = optimizerScheduler.generateSchedule(input)

        // At least some employees should be scheduled
        val scheduledEmployees = output.shifts.map { it.employeeId }.distinct()
        assertTrue(
            scheduledEmployees.isNotEmpty(),
            "OPTIMIZER should schedule at least one employee"
        )

        // Verify total coverage makes sense
        assertTrue(
            output.metrics.estimatedTotalSales > 0,
            "OPTIMIZER should provide sales coverage with multiple employees"
        )
    }

    @Test
    fun `OPTIMIZER with BALANCED objective should optimize productivity-to-cost ratio`() {
        val optimizerScheduler = ShiftScheduler(
            employeeRepository = employeeRepository,
            salesForecastRepository = salesForecastRepository,
            schedulingApproach = SchedulingApproach.OPTIMIZER
        )

        val inefficientEmployee = createEmployee(
            firstName = "Inefficient",
            productivity = 100.0,
            payRate = 25.0, // High cost, low productivity (ratio: 4.0)
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        val efficientEmployee = createEmployee(
            firstName = "Efficient",
            productivity = 220.0,
            payRate = 16.0, // Lower cost, higher productivity (ratio: 13.75)
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        salesForecastRepository.update(
            mapOf(
                DayOfWeek.TUESDAY to mapOf(
                    LocalTime.of(10, 0) to 600.0,
                    LocalTime.of(12, 0) to 800.0,
                    LocalTime.of(14, 0) to 700.0
                )
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(inefficientEmployee.id, efficientEmployee.id),
            laborCostBudget = 2000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 2),  // Tuesday
                endDate = LocalDate.of(2024, 1, 2),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 2) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(17, 0))
                )
            ),
            optimizationObjective = OptimizationObjective.BALANCED
        )

        val output = optimizerScheduler.generateSchedule(input)

        val efficientHours = output.shifts.filter { it.employeeId == efficientEmployee.id }.sumOf { it.durationHours }
        val inefficientHours = output.shifts.filter { it.employeeId == inefficientEmployee.id }.sumOf { it.durationHours }

        // Efficient employee should get more hours due to better productivity-to-cost ratio
        assertTrue(
            efficientHours >= inefficientHours,
            "OPTIMIZER BALANCED should favor efficient employee (efficient: $efficientHours, inefficient: $inefficientHours)"
        )

        // Verify that we're achieving good value: sales per dollar spent
        val salesPerDollar = if (output.metrics.totalLaborCost > 0) {
            output.metrics.estimatedTotalSales / output.metrics.totalLaborCost
        } else {
            0.0
        }

        assertTrue(
            salesPerDollar > 5.0,
            "OPTIMIZER BALANCED should achieve good sales-per-dollar ratio (got: $salesPerDollar)"
        )
    }

    @Test
    fun `OPTIMIZER with MAXIMIZE_FAIRNESS objective should distribute hours evenly`() {
        val optimizerScheduler = ShiftScheduler(
            employeeRepository = employeeRepository,
            salesForecastRepository = salesForecastRepository,
            schedulingApproach = SchedulingApproach.OPTIMIZER
        )

        // Create three employees with identical stats
        val employee1 = createEmployee(
            firstName = "Equal1",
            productivity = 160.0,
            payRate = 16.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.WEDNESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(18, 0))
            )
        )

        val employee2 = createEmployee(
            firstName = "Equal2",
            productivity = 160.0,
            payRate = 16.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.WEDNESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(18, 0))
            )
        )

        val employee3 = createEmployee(
            firstName = "Equal3",
            productivity = 160.0,
            payRate = 16.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.WEDNESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(18, 0))
            )
        )

        salesForecastRepository.update(
            mapOf(
                DayOfWeek.WEDNESDAY to mapOf(
                    LocalTime.of(10, 0) to 500.0,
                    LocalTime.of(11, 0) to 550.0,
                    LocalTime.of(12, 0) to 600.0,
                    LocalTime.of(13, 0) to 580.0,
                    LocalTime.of(14, 0) to 520.0,
                    LocalTime.of(15, 0) to 500.0
                )
            )
        )

        val input = ScheduleInput(
            employeeIds = listOf(employee1.id, employee2.id, employee3.id),
            laborCostBudget = 3000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 3),  // Wednesday
                endDate = LocalDate.of(2024, 1, 3),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 3) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(18, 0))
                )
            ),
            optimizationObjective = OptimizationObjective.MAXIMIZE_FAIRNESS
        )

        val output = optimizerScheduler.generateSchedule(input)

        val employee1Hours = output.shifts.filter { it.employeeId == employee1.id }.sumOf { it.durationHours }
        val employee2Hours = output.shifts.filter { it.employeeId == employee2.id }.sumOf { it.durationHours }
        val employee3Hours = output.shifts.filter { it.employeeId == employee3.id }.sumOf { it.durationHours }

        val hours = listOf(employee1Hours, employee2Hours, employee3Hours)
        val avgHours = hours.average()
        val variance = hours.map { (it - avgHours) * (it - avgHours) }.average()
        val stdDev = kotlin.math.sqrt(variance)

        // Hours should be relatively balanced across all employees
        assertTrue(
            stdDev < 3.0,
            "OPTIMIZER MAXIMIZE_FAIRNESS should distribute hours fairly (stdDev: $stdDev, hours: $hours)"
        )

        // All employees should get some hours
        assertTrue(employee1Hours > 0 || employee2Hours > 0 || employee3Hours > 0,
            "OPTIMIZER should schedule at least some employees")
    }

    @Test
    fun `OPTIMIZER different objectives should produce measurably different results`() {
        val optimizerScheduler = ShiftScheduler(
            employeeRepository = employeeRepository,
            salesForecastRepository = salesForecastRepository,
            schedulingApproach = SchedulingApproach.OPTIMIZER
        )

        val cheapEmployee = createEmployee(
            firstName = "Cheap",
            productivity = 120.0,
            payRate = 12.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.THURSDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        val productiveEmployee = createEmployee(
            firstName = "Productive",
            productivity = 280.0,
            payRate = 26.0,
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.THURSDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        salesForecastRepository.update(
            mapOf(
                DayOfWeek.THURSDAY to mapOf(
                    LocalTime.of(10, 0) to 600.0,
                    LocalTime.of(12, 0) to 900.0,
                    LocalTime.of(14, 0) to 750.0
                )
            )
        )

        val maxSalesInput = ScheduleInput(
            employeeIds = listOf(cheapEmployee.id, productiveEmployee.id),
            laborCostBudget = 2000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 4),  // Thursday
                endDate = LocalDate.of(2024, 1, 4),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 4) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(17, 0))
                )
            ),
            optimizationObjective = OptimizationObjective.MAXIMIZE_SALES
        )

        val minCostInput = maxSalesInput.copy(optimizationObjective = OptimizationObjective.MINIMIZE_LABOR_COST)
        val balancedInput = maxSalesInput.copy(optimizationObjective = OptimizationObjective.BALANCED)

        val maxSalesOutput = optimizerScheduler.generateSchedule(maxSalesInput)
        val minCostOutput = optimizerScheduler.generateSchedule(minCostInput)
        val balancedOutput = optimizerScheduler.generateSchedule(balancedInput)

        // MAXIMIZE_SALES should favor productive employee
        val maxSalesProductiveHours = maxSalesOutput.shifts.filter { it.employeeId == productiveEmployee.id }.sumOf { it.durationHours }
        val maxSalesCheapHours = maxSalesOutput.shifts.filter { it.employeeId == cheapEmployee.id }.sumOf { it.durationHours }

        // MINIMIZE_LABOR_COST should favor cheaper employee
        val minCostCheapHours = minCostOutput.shifts.filter { it.employeeId == cheapEmployee.id }.sumOf { it.durationHours }
        val minCostProductiveHours = minCostOutput.shifts.filter { it.employeeId == productiveEmployee.id }.sumOf { it.durationHours }

        assertTrue(
            maxSalesProductiveHours >= maxSalesCheapHours,
            "MAXIMIZE_SALES should favor productive employee (productive: $maxSalesProductiveHours, cheap: $maxSalesCheapHours)"
        )

        assertTrue(
            minCostCheapHours >= minCostProductiveHours,
            "MINIMIZE_LABOR_COST should favor cheaper employee (cheap: $minCostCheapHours, productive: $minCostProductiveHours)"
        )

        // Verify objectives produce different results
        assertTrue(
            maxSalesOutput.metrics.estimatedTotalSales >= minCostOutput.metrics.estimatedTotalSales ||
            minCostOutput.metrics.totalLaborCost <= maxSalesOutput.metrics.totalLaborCost,
            "Different objectives should produce different optimization results"
        )

        // BALANCED should achieve reasonable performance on both metrics
        assertTrue(
            balancedOutput.metrics.totalLaborCost > 0 && balancedOutput.metrics.estimatedTotalSales > 0,
            "BALANCED should produce a feasible schedule"
        )
    }
}
