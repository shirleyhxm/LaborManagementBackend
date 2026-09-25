package org.labormanagement.service

import org.labormanagement.dto.BusinessHourOverrideDto
import org.labormanagement.dto.BusinessHoursResponse
import org.labormanagement.dto.UpdateBusinessHoursRequest
import org.labormanagement.dto.toModel
import org.labormanagement.dto.toResponse
import org.labormanagement.model.invalidIntervalsReason
import org.labormanagement.repository.BusinessHoursRepository
import org.labormanagement.repository.BusinessRepository
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.util.UUID

/**
 * Reading and writing a business's opening hours.
 *
 * Reads are open to anyone who can see the business - a manager needs to know when it is
 * open to make sense of a schedule. Writes are owner-only: when the business trades is an
 * owner's decision, and it silently changes every schedule generated afterwards.
 */
class BusinessHoursService(
    private val businessHoursRepository: BusinessHoursRepository = BusinessHoursRepository(),
    private val businessRepository: BusinessRepository = BusinessRepository()
) {
    private val logger = LoggerFactory.getLogger(BusinessHoursService::class.java)

    fun getHours(businessId: UUID): BusinessHoursResponse {
        businessRepository.findById(businessId)
            ?: throw NotFoundException("Business not found: $businessId")
        return businessHoursRepository.findByBusinessId(businessId).toResponse()
    }

    fun updateWeek(userId: String, businessId: UUID, request: UpdateBusinessHoursRequest): BusinessHoursResponse {
        requireOwner(userId, businessId)

        val week = request.week.map { it.toModel() }

        // A week missing a day would leave that day silently falling back to the legacy
        // default pair, which is not what an owner who just edited their hours expects.
        val days = week.map { it.dayOfWeek }.toSet()
        if (days.size != DayOfWeek.entries.size) {
            throw IllegalArgumentException(
                "All seven days must be supplied; got ${days.size} distinct day(s)"
            )
        }

        // A closed day is not checked: it keeps whatever times it had so reopening restores
        // them, and those are not being traded on.
        week.filterNot { it.isClosed }.forEach { day ->
            invalidIntervalsReason(day.toOperatingHours()!!.openIntervals())?.let { reason ->
                throw IllegalArgumentException("${day.dayOfWeek}: $reason")
            }
        }

        businessHoursRepository.saveWeek(businessId, week)
        logger.info("Business $businessId hours updated by $userId")
        return businessHoursRepository.findByBusinessId(businessId).toResponse()
    }

    fun saveOverride(userId: String, businessId: UUID, dto: BusinessHourOverrideDto): BusinessHoursResponse {
        requireOwner(userId, businessId)

        val override = dto.toModel(businessId)
        if (!override.isClosed) {
            invalidIntervalsReason(override.toOperatingHours()!!.openIntervals())?.let { reason ->
                throw IllegalArgumentException(reason)
            }
        }

        businessHoursRepository.saveOverride(override)
        return businessHoursRepository.findByBusinessId(businessId).toResponse()
    }

    fun deleteOverride(userId: String, businessId: UUID, overrideId: UUID): BusinessHoursResponse {
        requireOwner(userId, businessId)

        if (!businessHoursRepository.deleteOverride(businessId, overrideId)) {
            throw NotFoundException("Override not found: $overrideId")
        }
        return businessHoursRepository.findByBusinessId(businessId).toResponse()
    }

    private fun requireOwner(userId: String, businessId: UUID) {
        businessRepository.findById(businessId)
            ?: throw NotFoundException("Business not found: $businessId")
        if (!businessRepository.isOwner(userId, businessId)) {
            throw ForbiddenException("Only the business owner can change opening hours")
        }
    }
}
