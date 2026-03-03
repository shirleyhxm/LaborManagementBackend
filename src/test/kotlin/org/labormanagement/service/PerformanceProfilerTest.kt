package org.labormanagement.service

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.labormanagement.model.*
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.SalesForecastRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Disabled
class PerformanceProfilerTest {
    // Test business ID for all tests
    private val testBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000001")
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

    private fun createEmployee(
        firstName: String,
        productivity: Double,
        payRate: Double,
        availability: List<Availability>,
        contractedHours: Double = 40.0,
        maxHours: Double = 50.0
    ): Employee {
        val employee = Employee(
            businessId = testBusinessId,
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
    fun `profile scheduling performance with small workload`() {
        val employees = listOf(
            createEmployee(
                firstName = "Alice",
                productivity = 150.0,
                payRate = 15.0,
                availability = listOf(
                    Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            ),
            createEmployee(
                firstName = "Bob",
                productivity = 200.0,
                payRate = 20.0,
                availability = listOf(
                    Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )
        salesForecastRepository.updateForBusiness(
            businessId = testBusinessId,
            weeklyPattern = mapOf(
                DayOfWeek.MONDAY to mapOf(
                    LocalTime.of(10, 0) to 300.0,
                    LocalTime.of(12, 0) to 500.0,
                    LocalTime.of(14, 0) to 400.0,
                    LocalTime.of(16, 0) to 350.0
                ),
                DayOfWeek.TUESDAY to mapOf(
                    LocalTime.of(10, 0) to 300.0,
                    LocalTime.of(12, 0) to 500.0,
                    LocalTime.of(14, 0) to 400.0,
                    LocalTime.of(16, 0) to 350.0
                )
            )
        )

        val input = ScheduleInput(
            businessId = testBusinessId,
            employeeIds = employees.map { e -> e.id },
            laborCostBudget = 2000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),
                endDate = LocalDate.of(2024, 1, 2),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    LocalDate.of(2024, 1, 2) to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        println("\n===== Small Workload Profile (2 employees, 2 days) =====")
        PerformanceProfiler.profile {
            scheduler.generateSchedule(input, businessId = testBusinessId)
        }
    }

    @Test
    fun `profile scheduling performance with medium workload`() {
        val employees = (1..10).map { i ->
            createEmployee(
                firstName = "Employee$i",
                productivity = 100.0 + (i * 10.0),
                payRate = 15.0 + (i * 0.5),
                availability = DayOfWeek.values().filter { it != DayOfWeek.SATURDAY && it != DayOfWeek.SUNDAY }.map { day ->
                    Availability(AvailabilityType.WEEKLY_RECURRING, day, null, null, LocalTime.of(8, 0), LocalTime.of(20, 0))
                }
            )
        }

        val salesPerHour = mapOf(
            LocalTime.of(8, 0) to 200.0,
            LocalTime.of(9, 0) to 300.0,
            LocalTime.of(10, 0) to 400.0,
            LocalTime.of(11, 0) to 500.0,
            LocalTime.of(12, 0) to 600.0,
            LocalTime.of(13, 0) to 500.0,
            LocalTime.of(14, 0) to 450.0,
            LocalTime.of(15, 0) to 400.0,
            LocalTime.of(16, 0) to 350.0,
            LocalTime.of(17, 0) to 300.0,
            LocalTime.of(18, 0) to 250.0,
            LocalTime.of(19, 0) to 200.0
        )
        salesForecastRepository.updateForBusiness(
            businessId = testBusinessId,
            weeklyPattern = mapOf(
                DayOfWeek.MONDAY to salesPerHour,
                DayOfWeek.TUESDAY to salesPerHour,
                DayOfWeek.WEDNESDAY to salesPerHour,
                DayOfWeek.THURSDAY to salesPerHour,
                DayOfWeek.FRIDAY to salesPerHour
            )
        )

        val input = ScheduleInput(
            businessId = testBusinessId,
            employeeIds = employees.map { e -> e.id },
            laborCostBudget = 10000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),
                endDate = LocalDate.of(2024, 1, 5),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    LocalDate.of(2024, 1, 2) to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    LocalDate.of(2024, 1, 3) to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    LocalDate.of(2024, 1, 4) to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    LocalDate.of(2024, 1, 5) to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0))
                )
            )
        )

        println("\n===== Medium Workload Profile (10 employees, 5 days) =====")
        PerformanceProfiler.profile {
            scheduler.generateSchedule(input, businessId = testBusinessId)
        }
    }

    @Test
    fun `profile scheduling performance with large workload`() {
        val employees = (1..50).map { i ->
            createEmployee(
                firstName = "Employee$i",
                productivity = 100.0 + (i % 20) * 10.0,
                payRate = 15.0 + (i % 10) * 1.0,
                availability = DayOfWeek.values().filter { it != DayOfWeek.SATURDAY && it != DayOfWeek.SUNDAY }.map { day ->
                    Availability(AvailabilityType.WEEKLY_RECURRING, day, null, null, LocalTime.of(6, 0), LocalTime.of(22, 0))
                }
            )
        }

        val salesPerHour = (6..21).associate { hour ->
            LocalTime.of(hour, 0) to (200.0 + (hour - 6) * 50.0 - if (hour > 14) (hour - 14) * 30.0 else 0.0)
        }
        salesForecastRepository.updateForBusiness(
            businessId = testBusinessId,
            weeklyPattern = mapOf(
                DayOfWeek.MONDAY to salesPerHour,
                DayOfWeek.TUESDAY to salesPerHour,
                DayOfWeek.WEDNESDAY to salesPerHour,
                DayOfWeek.THURSDAY to salesPerHour,
                DayOfWeek.FRIDAY to salesPerHour,
                DayOfWeek.SATURDAY to salesPerHour,
                DayOfWeek.SUNDAY to salesPerHour
            )
        )

        val input = ScheduleInput(
            businessId = testBusinessId,
            employeeIds = employees.map { e -> e.id },
            laborCostBudget = 50000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),
                endDate = LocalDate.of(2024, 1, 7),
                operatingHours = (1..7).associate { day ->
                    LocalDate.of(2024, 1, day) to OperatingHours(LocalTime.of(6, 0), LocalTime.of(22, 0))
                }
            )
        )

        println("\n===== Large Workload Profile (50 employees, 7 days, 16 hours/day) =====")
        PerformanceProfiler.profile {
            scheduler.generateSchedule(input, businessId = testBusinessId)
        }
    }

    @Test
    fun `compare performance across optimization objectives`() {
        val employees = (1..20).map { i ->
            createEmployee(
                firstName = "Employee$i",
                productivity = 100.0 + (i * 5.0),
                payRate = 15.0 + (i * 0.3),
                availability = listOf(
                    Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.WEDNESDAY, null, null, LocalTime.of(8, 0), LocalTime.of(20, 0))
                )
            )
        }

        val salesPerHour = (8..19).associate { hour ->
            LocalTime.of(hour, 0) to 400.0
        }
        salesForecastRepository.updateForBusiness(
            businessId = testBusinessId,
            weeklyPattern = mapOf(
                DayOfWeek.MONDAY to salesPerHour,
                DayOfWeek.TUESDAY to salesPerHour,
                DayOfWeek.WEDNESDAY to salesPerHour
            )
        )

        val baseInput = ScheduleInput(
            businessId = testBusinessId,
            employeeIds = employees.map { e -> e.id },
            laborCostBudget = 15000.0,
            schedulePeriod = SchedulePeriod(
                startDate = LocalDate.of(2024, 1, 1),
                endDate = LocalDate.of(2024, 1, 3),
                operatingHours = mapOf(
                    LocalDate.of(2024, 1, 1) to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    LocalDate.of(2024, 1, 2) to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    LocalDate.of(2024, 1, 3) to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0))
                )
            )
        )

        println("\n===== Comparing Optimization Objectives (20 employees, 3 days) =====\n")

        val objectives = listOf(
            OptimizationObjective.MAXIMIZE_SALES,
            OptimizationObjective.MINIMIZE_LABOR_COST,
            OptimizationObjective.BALANCED,
            OptimizationObjective.MAXIMIZE_FAIRNESS
        )

        for (objective in objectives) {
            println("--- $objective ---")
            PerformanceProfiler.profile {
                scheduler.generateSchedule(baseInput.copy(optimizationObjective = objective), businessId = testBusinessId)
            }
            println()
        }
    }
}
