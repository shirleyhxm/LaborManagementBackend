package org.labormanagement.dto

data class CreateInviteRequest(
    val email: String
)

data class CreateInviteResponse(
    val inviteLink: String
)

data class InviteDetailsResponse(
    val email: String,
    val businessName: String,
    val employeeFirstName: String,
    val employeeLastName: String
)

data class AcceptInviteRequest(
    val password: String
)
