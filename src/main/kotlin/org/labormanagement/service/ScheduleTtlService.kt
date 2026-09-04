package org.labormanagement.service

import org.labormanagement.repository.BusinessRepository
import org.labormanagement.repository.ScheduleRepository
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Service for managing schedule Time-To-Live (TTL) cleanup.
 *
 * Automatically deletes schedules whose start dates are older than the configured
 * retention period (default: 2 weeks).
 *
 * Features:
 * - Configurable retention period
 * - Manual and automatic cleanup
 * - Logging of cleanup operations
 * - Statistics tracking
 * - Support for cleaning up schedules across all businesses or a specific business
 */
class ScheduleTtlService(
    private val scheduleRepository: ScheduleRepository,
    private val businessRepository: BusinessRepository,
    private val retentionWeeks: Int = 2
) {
    private val logger = LoggerFactory.getLogger(ScheduleTtlService::class.java)

    data class CleanupResult(
        val deletedCount: Int,
        val retainedCount: Int,
        val cutoffDate: LocalDate,
        val errors: List<String> = emptyList()
    )

    /**
     * Performs cleanup of schedules older than the retention period.
     *
     * @param businessId The business to clean up schedules for, or null to clean up all businesses
     * @param dryRun If true, only reports what would be deleted without actually deleting
     * @return CleanupResult with statistics about the cleanup operation
     */
    fun cleanupOldSchedules(businessId: UUID? = null, dryRun: Boolean = false): CleanupResult {
        val cutoffDate = calculateCutoffDate()

        if (businessId != null) {
            logger.info("Starting schedule cleanup for business $businessId. Cutoff date: $cutoffDate (retention: $retentionWeeks weeks), dryRun: $dryRun")
            return cleanupForBusiness(businessId, cutoffDate, dryRun)
        } else {
            logger.info("Starting schedule cleanup for ALL businesses. Cutoff date: $cutoffDate (retention: $retentionWeeks weeks), dryRun: $dryRun")
            return cleanupAllBusinesses(cutoffDate, dryRun)
        }
    }

    /**
     * Performs cleanup for a specific business.
     */
    private fun cleanupForBusiness(businessId: UUID, cutoffDate: LocalDate, dryRun: Boolean): CleanupResult {
        val allSchedules = scheduleRepository.findAllByBusiness(businessId, kind = null)
        val schedulesToDelete = allSchedules.filter { schedule ->
            schedule.schedulePeriod.startDate.isBefore(cutoffDate)
        }

        val errors = mutableListOf<String>()
        var deletedCount = 0

        if (!dryRun) {
            schedulesToDelete.forEach { schedule ->
                try {
                    val success = scheduleRepository.forceDelete(schedule.id)
                    if (success) {
                        deletedCount++
                        logger.debug("Deleted schedule: ${schedule.id} (startDate: ${schedule.schedulePeriod.startDate})")
                    } else {
                        val errorMsg = "Failed to delete schedule: ${schedule.id}"
                        errors.add(errorMsg)
                        logger.warn(errorMsg)
                    }
                } catch (e: Exception) {
                    val errorMsg = "Error deleting schedule ${schedule.id}: ${e.message}"
                    errors.add(errorMsg)
                    logger.error(errorMsg, e)
                }
            }
        } else {
            deletedCount = schedulesToDelete.size
            logger.info("DRY RUN: Would delete ${schedulesToDelete.size} schedules for business $businessId")
            schedulesToDelete.forEach { schedule ->
                logger.debug("Would delete: ${schedule.id} (startDate: ${schedule.schedulePeriod.startDate}, status: ${schedule.status})")
            }
        }

        val retainedCount = allSchedules.size - schedulesToDelete.size

        val result = CleanupResult(
            deletedCount = deletedCount,
            retainedCount = retainedCount,
            cutoffDate = cutoffDate,
            errors = errors
        )

        logger.info("Schedule cleanup completed for business $businessId. Deleted: $deletedCount, Retained: $retainedCount, Errors: ${errors.size}")
        return result
    }

    /**
     * Performs cleanup for all businesses.
     */
    private fun cleanupAllBusinesses(cutoffDate: LocalDate, dryRun: Boolean): CleanupResult {
        val businesses = businessRepository.findAll()
        var totalDeleted = 0
        var totalRetained = 0
        val allErrors = mutableListOf<String>()

        businesses.forEach { business ->
            val result = cleanupForBusiness(business.id, cutoffDate, dryRun)
            totalDeleted += result.deletedCount
            totalRetained += result.retainedCount
            allErrors.addAll(result.errors)
        }

        val result = CleanupResult(
            deletedCount = totalDeleted,
            retainedCount = totalRetained,
            cutoffDate = cutoffDate,
            errors = allErrors
        )

        logger.info("Schedule cleanup completed for ALL businesses. Total deleted: $totalDeleted, Total retained: $totalRetained, Errors: ${allErrors.size}")
        return result
    }

    /**
     * Calculates the cutoff date based on the retention period.
     * Schedules with start dates before this date will be deleted.
     */
    fun calculateCutoffDate(): LocalDate {
        return LocalDate.now().minus(retentionWeeks.toLong(), ChronoUnit.WEEKS)
    }

    /**
     * Gets statistics about schedules that would be cleaned up.
     *
     * @param businessId The business to get stats for, or null to get stats for all businesses
     */
    fun getCleanupStats(businessId: UUID? = null): Map<String, Any> {
        val cutoffDate = calculateCutoffDate()

        if (businessId != null) {
            val allSchedules = scheduleRepository.findAllByBusiness(businessId, kind = null)
            val schedulesToDelete = allSchedules.filter { schedule ->
                schedule.schedulePeriod.startDate.isBefore(cutoffDate)
            }

            return mapOf(
                "retentionWeeks" to retentionWeeks,
                "cutoffDate" to cutoffDate,
                "totalSchedules" to allSchedules.size,
                "schedulesToCleanup" to schedulesToDelete.size,
                "schedulesToRetain" to (allSchedules.size - schedulesToDelete.size)
            )
        } else {
            // Stats for all businesses
            val businesses = businessRepository.findAll()
            var totalSchedules = 0
            var totalToCleanup = 0

            businesses.forEach { business ->
                val allSchedules = scheduleRepository.findAllByBusiness(business.id, kind = null)
                val schedulesToDelete = allSchedules.filter { schedule ->
                    schedule.schedulePeriod.startDate.isBefore(cutoffDate)
                }
                totalSchedules += allSchedules.size
                totalToCleanup += schedulesToDelete.size
            }

            return mapOf(
                "retentionWeeks" to retentionWeeks,
                "cutoffDate" to cutoffDate,
                "totalSchedules" to totalSchedules,
                "schedulesToCleanup" to totalToCleanup,
                "schedulesToRetain" to (totalSchedules - totalToCleanup),
                "businessCount" to businesses.size
            )
        }
    }
}
