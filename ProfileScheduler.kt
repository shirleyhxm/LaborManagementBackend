#!/usr/bin/env kotlin

@file:DependsOn("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")

import org.labormanagement.model.*
import org.labormanagement.service.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

fun main() {
    println("Labor Management Performance Profiling")
    println("=" .repeat(80))

    // Run the medium workload test
    runMediumWorkloadProfile()
}

fun runMediumWorkloadProfile() {
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

    val input = SchedulingInput(
        employees = employees,
        laborCostBudget = 10000.0,
        salesForecast = mapOf(
            DayOfWeek.MONDAY to salesPerHour,
            DayOfWeek.TUESDAY to salesPerHour,
            DayOfWeek.WEDNESDAY to salesPerHour,
            DayOfWeek.THURSDAY to salesPerHour,
            DayOfWeek.FRIDAY to salesPerHour
        ),
        schedulingPeriod = SchedulingPeriod(
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
        ),
        optimizationObjective = OptimizationObjective.MAXIMIZE_FAIRNESS
    )

    val scheduler = ShiftScheduler()

    println("\n===== Medium Workload Profile (10 employees, 5 days) =====")
    PerformanceProfiler.profile {
        scheduler.generateSchedule(input)
    }
}

fun createEmployee(
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