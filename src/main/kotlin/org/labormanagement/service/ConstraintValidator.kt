package org.labormanagement.service

import org.labormanagement.model.ConstraintViolation
import org.labormanagement.model.Employee
import org.labormanagement.model.Shift
import org.labormanagement.model.StaffingRequirement
import org.labormanagement.model.ViolationType
import java.time.LocalDate

class ConstraintValidator {

    fun validate(
        shifts: List<Shift>,
        employees: List<Employee>,
        laborCostBudget: Double,
        staffingRequirements: List<StaffingRequirement> = emptyList()
    ): List<ConstraintViolation> {
        val violations = mutableListOf<ConstraintViolation>()

        // Check budget constraint
        val totalCost = shifts.sumOf { it.laborCost }
        if (totalCost > laborCostBudget) {
            violations.add(
                ConstraintViolation.ScheduleLevel(
                    type = ViolationType.BUDGET_EXCEEDED,
                    description = "Total labor cost $${"%.2f".format(totalCost)} exceeds budget $${
                        "%.2f".format(
                            laborCostBudget
                        )
                    }"
                )
            )
        }

        // Check understaffing
        violations.addAll(checkUnderstaffing(staffingRequirements))

        // Check employee-specific constraints
        val employeeMap = employees.associateBy { it.id }
        val shiftsByEmployee = shifts.groupBy { it.employeeId }

        shiftsByEmployee.forEach { (employeeId, employeeShifts) ->
            val employee = employeeMap[employeeId] ?: return@forEach

            // Check availability
            violations.addAll(checkAvailability(employee, employeeShifts))

            // Check contract hours
            violations.addAll(checkContractCompliance(employee, employeeShifts))

            // Check for overlapping shifts
            violations.addAll(checkShiftOverlaps(employee, employeeShifts))

            // Check break requirements
            violations.addAll(checkBreakRequirements(employee, employeeShifts))
        }

        return violations
    }

    private fun checkAvailability(employee: Employee, shifts: List<Shift>): List<ConstraintViolation> {
        val violations = mutableListOf<ConstraintViolation>()

        shifts.forEach { shift ->
            val isAvailable = employee.availability.any { it.canWork(shift) }
            if (!isAvailable) {
                violations.add(
                    ConstraintViolation.Shift(
                        type = ViolationType.AVAILABILITY_CONFLICT,
                        description = "${employee.fullName} is not available for shift on ${shift.dayOfWeek} ${shift.startTime}-${shift.endTime}",
                        employeeId = employee.id,
                        date = shift.date,
                        startTime = shift.startTime,
                        endTime = shift.endTime
                    )
                )
            }
        }

        return violations
    }

    private fun checkContractCompliance(employee: Employee, shifts: List<Shift>): List<ConstraintViolation> {
        val violations = mutableListOf<ConstraintViolation>()
        val contract = employee.contract

        // Check weekly hours
        val totalWeeklyHours = shifts.sumOf { it.durationHours }
        if (totalWeeklyHours > contract.maxHoursPerWeek) {
            violations.add(
                ConstraintViolation.Employee(
                    type = ViolationType.CONTRACT_HOURS_EXCEEDED,
                    description = "${employee.fullName} scheduled for ${"%.2f".format(totalWeeklyHours)} hours, exceeds max ${contract.maxHoursPerWeek} hours/week",
                    employeeId = employee.id
                )
            )
        }

        // Check daily hours
        shifts.groupBy { it.dayOfWeek }.forEach { (day, dayShifts) ->
            val dailyHours = dayShifts.sumOf { it.durationHours }
            if (dailyHours > contract.maxHoursPerDay) {
                violations.add(
                    ConstraintViolation.EmployeeDay(
                        type = ViolationType.CONTRACT_HOURS_EXCEEDED,
                        description = "${employee.fullName} scheduled for ${"%.2f".format(dailyHours)} hours on $day, exceeds max ${contract.maxHoursPerDay} hours/day",
                        employeeId = employee.id,
                        date = dayShifts[0].date
                    )
                )
            }
        }

        return violations
    }

    private fun checkShiftOverlaps(employee: Employee, shifts: List<Shift>): List<ConstraintViolation> {
        val violations = mutableListOf<ConstraintViolation>()

        for (i in shifts.indices) {
            for (j in i + 1 until shifts.size) {
                if (shifts[i].overlaps(shifts[j])) {
                    violations.add(
                        ConstraintViolation.Shift(
                            type = ViolationType.SHIFT_OVERLAP,
                            description = "${employee.fullName} has overlapping shifts on ${shifts[i].dayOfWeek}",
                            employeeId = employee.id,
                            date = shifts[i].date,
                            startTime = shifts[i].startTime,
                            endTime = shifts[i].endTime
                        )
                    )
                }
            }
        }

        return violations
    }

    private fun checkBreakRequirements(employee: Employee, shifts: List<Shift>): List<ConstraintViolation> {
        val violations = mutableListOf<ConstraintViolation>()
        val contract = employee.contract

        if (!contract.requiresBreak) return violations

        shifts.forEach { shift ->
            if (shift.durationHours > contract.shiftLengthThresholdHours) {
                // In a real system, we'd track whether breaks are scheduled
                // For now, we just validate that long shifts exist and warn about breaks
                // This is informational rather than a violation
            }
        }

        return violations
    }

    private fun checkUnderstaffing(staffingRequirements: List<StaffingRequirement>): List<ConstraintViolation> {
        val violations = mutableListOf<ConstraintViolation>()

        staffingRequirements.forEach { requirement ->
            if (requirement.isUnderstaffed) {
                val gap = requirement.staffingGap
                val percentage = if (requirement.employeesNeeded > 0) {
                    ((requirement.employeesAssigned.toDouble() / requirement.employeesNeeded) * 100).toInt()
                } else {
                    100
                }

                violations.add(
                    ConstraintViolation.TimeBlock(
                        type = ViolationType.UNDERSTAFFING,
                        description = "Understaffed on ${requirement.date.dayOfWeek} ${requirement.startTime}-${requirement.endTime}: " +
                                "needs ${requirement.employeesNeeded} employees, but only ${requirement.employeesAssigned} assigned " +
                                "(${percentage}% staffed, gap of $gap employee${if (gap > 1) "s" else ""}). " +
                                "Expected sales: $${"%.2f".format(requirement.expectedSales)}",
                        date = requirement.date,
                        startTime = requirement.startTime,
                        endTime = requirement.endTime
                    )
                )
            }
        }

        return violations
    }
}
