package org.labormanagement.model

data class Contract(
    val contractedHoursPerWeek: Double,
    val maxHoursPerWeek: Double,
    val maxHoursPerDay: Double,
    val overtimeThreshold: Double, // Hours per week before overtime kicks in
    val requiresBreak: Boolean = true,
    val breakDurationMinutes: Int = 30, // Break duration if shift exceeds breakThresholdMinutes
    val shiftLengthThresholdHours: Int = 4 // Shift length that requires a break
)