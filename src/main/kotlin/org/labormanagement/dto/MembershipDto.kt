package org.labormanagement.dto

/**
 * A person with access to a business, as shown in the Team list.
 *
 * Covers both the owner (derived from Businesses.ownerId, always ADMIN) and
 * managers (rows in business_memberships), so the UI can render one list.
 * `isOwner` tells them apart: an owner's access follows from owning the
 * account and cannot be edited or revoked here.
 */

data class BusinessMemberResponse(
    val userId: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val role: String,
    val status: String,
    val isOwner: Boolean
)


data class BusinessMembersListResponse(
    val members: List<BusinessMemberResponse>
)

/**
 * Grant someone manager access to this business, by email so the owner does
 * not need to know internal user IDs.
 */

data class AddBusinessMemberRequest(
    val email: String,
    val role: String = "MANAGER"
)


data class UpdateBusinessMemberRequest(
    val role: String
)

/**
 * Invite someone to manage this business. They need no prior account - the
 * link creates one when they set a password.
 */
data class InviteManagerRequest(
    val email: String,
    val firstName: String,
    val lastName: String
)
