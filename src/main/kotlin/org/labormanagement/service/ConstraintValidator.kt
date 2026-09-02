package org.labormanagement.service

import org.labormanagement.model.ConstraintViolation
import org.labormanagement.model.Employee
import org.labormanagement.model.Shift
import org.labormanagement.model.StaffingRequirement
import org.labormanagement.model.ViolationType
import java.time.LocalDate

/**
 * Validates a generated schedule.
 *
 * The per-employee rule checks live in [ShiftPlanValidator], which is shared with the
 * manual-edit path in ShiftModificationService so a dragged shift is judged against the
 * same rules a generated one is. What remains here is the part that is specific to a
 * freshly generated schedule: the staffing requirements it was built to satisfy, which a
 * single shift edit has no equivalent of.
 */
class ConstraintValidator(
    private val planValidator: ShiftPlanValidator = ShiftPlanValidator()
) {

    fun validate(
        shifts: List<Shift>,
        employees: List<Employee>,
        laborCostBudget: Double,
        staffingRequirements: List<StaffingRequirement> = emptyList(),
        rules: ShiftPlanValidator.Rules = ShiftPlanValidator.Rules()
    ): List<ConstraintViolation> {
        val violations = mutableListOf<ConstraintViolation>()

        violations.addAll(
            planValidator.validate(
                shifts = shifts,
                employees = employees,
                rules = rules.copy(laborCostBudget = laborCostBudget)
            )
        )

        violations.addAll(checkUnderstaffing(staffingRequirements))

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
