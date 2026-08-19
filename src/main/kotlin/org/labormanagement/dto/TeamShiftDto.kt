package org.labormanagement.dto

import org.labormanagement.model.TeamShiftRow
import java.util.UUID

data class TeamShiftResponse(
    val id: String,
    val employeeId: String,
    val employeeName: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val durationHours: Double,
    val isOvertime: Boolean,
    val payRate: Double?,
    val isMine: Boolean
)

fun TeamShiftRow.toTeamShiftResponse(callerEmployeeId: UUID?): TeamShiftResponse {
    val mine = callerEmployeeId != null && employeeId == callerEmployeeId
    return TeamShiftResponse(
        id = id.toString(),
        employeeId = employeeId.toString(),
        employeeName = employeeName,
        date = date.toString(),
        startTime = startTime.toString(),
        endTime = endTime.toString(),
        durationHours = durationHours,
        isOvertime = isOvertime,
        payRate = if (mine) payRate else null,
        isMine = mine
    )
}
