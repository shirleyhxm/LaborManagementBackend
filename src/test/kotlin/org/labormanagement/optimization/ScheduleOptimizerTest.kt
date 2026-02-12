package org.labormanagement.optimization

import org.junit.jupiter.api.Test
import org.labormanagement.model.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScheduleOptimizerTest {

    @Test
    fun `optimizer should find a feasible solution for simple scenario`() {
        // Create a simple employee
        val employee = Employee(
            id = UUID.randomUUID(),
            firstName = "Test",
            lastName = "Employee",
            dateOfBirth = LocalDate.of(1990, 1, 1),
            normalPayRate = 20.0,
            overtimePayRate = 30.0,
            productivity = 100.0,
            contract = Contract(
                contractedHoursPerWeek = 40.0,
                maxHoursPerWeek = 48.0,
                maxHoursPerDay = 8.0,
                overtimeThreshold = 40.0
            ),
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        // Create simple sales forecast
        val salesForecast = SalesForecast(
            weeklyPattern = mapOf(
                DayOfWeek.MONDAY to mapOf(
                    LocalTime.of(9, 0) to 100.0,
                    LocalTime.of(10, 0) to 100.0
                )
            )
        )

        // Build optimization input
        val input = OptimizationConverter.buildOptimizationInput(
            employees = listOf(employee),
            salesForecast = salesForecast,
            scheduleDates = listOf(LocalDate.of(2024, 1, 1)),
            operatingHoursMap = mapOf(
                LocalDate.of(2024, 1, 1) to Pair(LocalTime.of(9, 0), LocalTime.of(11, 0))
            ),
            coverageFraction = 0.8
        )

        // Run optimizer
        val optimizer = ScheduleOptimizer()
        val result = optimizer.optimize(input)

        // Verify result
        assertNotNull(result, "Should find a feasible solution")
        assertTrue(result.assignments.isNotEmpty(), "Should have at least one assignment")
        assertTrue(result.objectiveValue > 0, "Objective value should be positive")
    }

    @Test
    fun `optimizer should convert results to shifts correctly`() {
        val employee = Employee(
            id = UUID.randomUUID(),
            firstName = "Alice",
            lastName = "Smith",
            dateOfBirth = LocalDate.of(1995, 3, 15),
            normalPayRate = 18.0,
            overtimePayRate = 27.0,
            productivity = 120.0,
            contract = Contract(
                contractedHoursPerWeek = 40.0,
                maxHoursPerWeek = 48.0,
                maxHoursPerDay = 8.0,
                overtimeThreshold = 40.0
            ),
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        val salesForecast = SalesForecast(
            weeklyPattern = mapOf(
                DayOfWeek.MONDAY to mapOf(
                    LocalTime.of(9, 0) to 150.0,
                    LocalTime.of(10, 0) to 150.0,
                    LocalTime.of(11, 0) to 150.0
                )
            )
        )

        val input = OptimizationConverter.buildOptimizationInput(
            employees = listOf(employee),
            salesForecast = salesForecast,
            scheduleDates = listOf(LocalDate.of(2024, 1, 1)),
            operatingHoursMap = mapOf(
                LocalDate.of(2024, 1, 1) to Pair(LocalTime.of(9, 0), LocalTime.of(12, 0))
            )
        )

        val optimizer = ScheduleOptimizer()
        val result = optimizer.optimize(input)

        assertNotNull(result, "Should find a solution")

        // Convert to shifts
        val shifts = OptimizationConverter.convertToShifts(result, input)

        assertTrue(shifts.isNotEmpty(), "Should generate shifts")
        shifts.forEach { shift ->
            assertTrue(shift.employeeId == employee.id, "Shift should be for the correct employee")
            assertTrue(shift.durationHours > 0, "Shift duration should be positive")
            assertTrue(shift.laborCost > 0, "Labor cost should be positive")
        }
    }
}
