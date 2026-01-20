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

@Disabled
class PerformanceProfilerTest {
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
                    Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    Availability(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            ),
            createEmployee(
                firstName = "Bob",
                productivity = 200.0,
                payRate = 20.0,
                availability = listOf(
                    Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    Availability(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )
        salesForecastRepository.update(
            mapOf(
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
            employeeIds = employees.map { e -> e.id },
            laborCostBudget = 2000.0,
            schedulePeriod = SchedulePeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    DayOfWeek.TUESDAY to OperatingHours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )

        println("\n===== Small Workload Profile (2 employees, 2 days) =====")
        PerformanceProfiler.profile {
            scheduler.generateSchedule(input)
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
                    Availability(day, LocalTime.of(8, 0), LocalTime.of(20, 0))
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
        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to salesPerHour,
                DayOfWeek.TUESDAY to salesPerHour,
                DayOfWeek.WEDNESDAY to salesPerHour,
                DayOfWeek.THURSDAY to salesPerHour,
                DayOfWeek.FRIDAY to salesPerHour
            )
        )

        val input = ScheduleInput(
            employeeIds = employees.map { e -> e.id },
            laborCostBudget = 10000.0,
            schedulePeriod = SchedulePeriod(
                daysToSchedule = listOf(
                    DayOfWeek.MONDAY,
                    DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY,
                    DayOfWeek.FRIDAY
                ),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    DayOfWeek.TUESDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    DayOfWeek.WEDNESDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    DayOfWeek.THURSDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    DayOfWeek.FRIDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0))
                )
            )
        )

        println("\n===== Medium Workload Profile (10 employees, 5 days) =====")
        PerformanceProfiler.profile {
            scheduler.generateSchedule(input)
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
                    Availability(day, LocalTime.of(6, 0), LocalTime.of(22, 0))
                }
            )
        }

        val salesPerHour = (6..21).associate { hour ->
            LocalTime.of(hour, 0) to (200.0 + (hour - 6) * 50.0 - if (hour > 14) (hour - 14) * 30.0 else 0.0)
        }
        salesForecastRepository.update(
            mapOf(
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
            employeeIds = employees.map { e -> e.id },
            laborCostBudget = 50000.0,
            schedulePeriod = SchedulePeriod(
                daysToSchedule = DayOfWeek.values().toList(),
                operatingHours = DayOfWeek.values().associate { day ->
                    day to OperatingHours(LocalTime.of(6, 0), LocalTime.of(22, 0))
                }
            )
        )

        println("\n===== Large Workload Profile (50 employees, 7 days, 16 hours/day) =====")
        PerformanceProfiler.profile {
            scheduler.generateSchedule(input)
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
                    Availability(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    Availability(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    Availability(DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(20, 0))
                )
            )
        }

        val salesPerHour = (8..19).associate { hour ->
            LocalTime.of(hour, 0) to 400.0
        }
        salesForecastRepository.update(
            mapOf(
                DayOfWeek.MONDAY to salesPerHour,
                DayOfWeek.TUESDAY to salesPerHour,
                DayOfWeek.WEDNESDAY to salesPerHour
            )
        )

        val baseInput = ScheduleInput(
            employeeIds = employees.map { e -> e.id },
            laborCostBudget = 15000.0,
            schedulePeriod = SchedulePeriod(
                daysToSchedule = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY),
                operatingHours = mapOf(
                    DayOfWeek.MONDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    DayOfWeek.TUESDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    DayOfWeek.WEDNESDAY to OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0))
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
                scheduler.generateSchedule(baseInput.copy(optimizationObjective = objective))
            }
            println()
        }
    }
}
