package org.labormanagement.service

import kotlinx.coroutines.*
import org.labormanagement.dto.*
import org.labormanagement.model.*
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.util.parseFlexibleTime
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for managing asynchronous optimization jobs.
 * Wraps ShiftScheduler for async execution and result tracking.
 */
class OptimizationJobService(
    private val shiftScheduler: ShiftScheduler,
    private val employeeRepository: EmployeeRepository
) {
    private val log = LoggerFactory.getLogger(OptimizationJobService::class.java)

    // In-memory job storage (for production, use a database or Redis)
    private val jobs = ConcurrentHashMap<String, OptimizationJob>()

    // Coroutine scope for async job execution
    private val jobScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Submit a new optimization job.
     * Returns immediately with a job ID for tracking.
     */
    fun submitJob(businessId: UUID, request: OptimizationRequestV2): OptimizationJobResponse {
        val jobId = UUID.randomUUID().toString()

        val job = OptimizationJob(
            id = jobId,
            status = JobStatus.QUEUED,
            businessId = businessId,
            request = request,
            createdAt = LocalDateTime.now()
        )

        jobs[jobId] = job

        log.info("[OptimizationJobService] Job $jobId submitted for business $businessId")

        // Start async processing
        jobScope.launch {
            processJob(jobId)
        }

        return OptimizationJobResponse(
            jobId = jobId,
            status = "QUEUED",
            message = "Optimization job queued successfully"
        )
    }

    /**
     * Get job status and results.
     */
    fun getJobStatus(jobId: String): OptimizationJobStatus? {
        val job = jobs[jobId] ?: return null

        return OptimizationJobStatus(
            jobId = job.id,
            status = job.status.name,
            solveStatus = job.solveStatus?.name,
            progress = job.progress,
            solveTimeMs = job.solveTimeMs,
            results = job.result,
            error = job.error,
            createdAt = job.createdAt.toString(),
            completedAt = job.completedAt?.toString()
        )
    }

    /**
     * Process an optimization job asynchronously.
     */
    private suspend fun processJob(jobId: String) {
        val job = jobs[jobId] ?: return

        try {
            log.info("[OptimizationJobService] Processing job $jobId")

            // Update status to RUNNING
            jobs[jobId] = job.copy(status = JobStatus.RUNNING, progress = 0)

            // Convert V2 DTO to ScheduleInput
            val scheduleInput = convertToScheduleInput(job.businessId, job.request)

            jobs[jobId] = job.copy(progress = 20)

            // Run scheduling using ShiftScheduler
            val startTime = System.currentTimeMillis()
            val schedule = shiftScheduler.generateSchedule(
                input = scheduleInput,
                name = "V2 API Schedule ${job.id}",
                generatedBy = job.request.requestedBy ?: "v2-api",
                businessId = job.businessId
            )
            val solveTime = System.currentTimeMillis() - startTime

            jobs[jobId] = job.copy(progress = 80)

            // Convert Schedule to V2 DTO
            val resultDto = convertScheduleToDto(schedule, solveTime)

            jobs[jobId] = job.copy(
                status = JobStatus.COMPLETED,
                solveStatus = SolveStatus.OPTIMAL,
                solveTimeMs = solveTime,
                progress = 100,
                result = resultDto,
                completedAt = LocalDateTime.now()
            )

            log.info("[OptimizationJobService] Job $jobId completed successfully with ${schedule.shifts.size} shifts")

        } catch (e: Exception) {
            log.error("[OptimizationJobService] Job $jobId failed", e)
            jobs[jobId] = job.copy(
                status = JobStatus.FAILED,
                error = "Optimization failed: ${e.message}",
                completedAt = LocalDateTime.now()
            )
        }
    }

    /**
     * Convert V2 API request to ScheduleInput for ShiftScheduler.
     * Simple conversion - ShiftScheduler handles fetching employees, constraints, etc.
     */
    private fun convertToScheduleInput(businessId: UUID, request: OptimizationRequestV2): ScheduleInput {
        // Parse dates
        val startDate = LocalDate.parse(request.demandMatrix.startDate)
        val endDate = LocalDate.parse(request.demandMatrix.endDate)

        // Get employee IDs (all employees if none specified)
        val employeeIds = if (request.employeeIds != null && request.employeeIds.isNotEmpty()) {
            request.employeeIds.map { UUID.fromString(it) }
        } else {
            employeeRepository.findAllByBusiness(businessId).map { it.id }
        }

        if (employeeIds.isEmpty()) {
            throw IllegalArgumentException("No employees available for optimization. Please create employees first.")
        }

        // Build operating hours map by LocalDate from demand matrix slots
        val operatingHoursMap = mutableMapOf<LocalDate, OperatingHours>()

        // Group slots by date and determine operating hours for each date
        request.demandMatrix.slots.groupBy { slot ->
            // Parse the day string (e.g., "MONDAY") and find the corresponding date in the period
            val dayOfWeek = DayOfWeek.valueOf(slot.day.uppercase())
            var currentDate = startDate
            while (!currentDate.isAfter(endDate)) {
                if (currentDate.dayOfWeek == dayOfWeek) {
                    return@groupBy currentDate
                }
                currentDate = currentDate.plusDays(1)
            }
            null
        }.forEach { (date, slots) ->
            if (date != null) {
                // "24:00" (end of day) parses to midnight, which would otherwise look
                // like the earliest possible time rather than the latest, so it's
                // compared as its own greatest-value case here before falling back
                // to normal LocalTime ordering.
                val earliestStart = slots.minByOrNull { parseFlexibleTime(it.startTime) }?.startTime
                val latestEnd = slots.maxByOrNull { slot ->
                    if (slot.endTime.trim() == "24:00" || slot.endTime.trim() == "24:00:00") {
                        LocalTime.MAX
                    } else {
                        parseFlexibleTime(slot.endTime)
                    }
                }?.endTime

                if (earliestStart != null && latestEnd != null) {
                    operatingHoursMap[date] = OperatingHours(
                        openTime = parseFlexibleTime(earliestStart),
                        closeTime = parseFlexibleTime(latestEnd)
                    )
                }
            }
        }

        // Map objective
        val objective = when (request.objective.primary.uppercase()) {
            "MINIMIZE_COST", "MINIMIZE_LABOR_COST" -> OptimizationObjective.MINIMIZE_LABOR_COST
            "MAXIMIZE_SALES", "MAXIMIZE_COVERAGE" -> OptimizationObjective.MAXIMIZE_SALES
            "BALANCE_WORKLOAD", "MAXIMIZE_FAIRNESS" -> OptimizationObjective.MAXIMIZE_FAIRNESS
            "BALANCED" -> OptimizationObjective.BALANCED
            else -> OptimizationObjective.BALANCED
        }

        val totalDays = startDate.datesUntil(endDate.plusDays(1)).count()
        log.info("[OptimizationJobService] Creating schedule for ${employeeIds.size} employees across $totalDays days")

        return ScheduleInput(
            businessId = businessId,
            employeeIds = employeeIds,
            laborCostBudget = Double.MAX_VALUE,  // Budget fetched from ConstraintsService by ShiftScheduler
            schedulePeriod = SchedulePeriod(
                startDate = startDate,
                endDate = endDate,
                operatingHours = operatingHoursMap
            ),
            optimizationObjective = objective
        )
    }

    /**
     * Convert Schedule model to V2 API result DTO.
     */
    private fun convertScheduleToDto(schedule: Schedule, solveTimeMs: Long): OptimizationResultDto {
        // Group shifts by employee
        val shiftsByEmployee = schedule.shifts.groupBy { it.employeeId }

        val assignments = shiftsByEmployee.map { (employeeId, shifts) ->
            val employee = employeeRepository.findById(schedule.businessId, employeeId)!!

            val shiftDtos = shifts.map { shift ->
                ShiftDto(
                    day = shift.date.dayOfWeek.toString(),
                    startTime = shift.startTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                    endTime = shift.endTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                    duration = shift.durationHours,
                    payRate = if (shift.isOvertime) employee.overtimePayRate else employee.normalPayRate,
                    isOvertime = shift.isOvertime
                )
            }

            val totalHours = shifts.sumOf { it.durationHours }
            val totalPay = shifts.sumOf {
                it.durationHours * if (it.isOvertime) employee.overtimePayRate else employee.normalPayRate
            }

            AssignmentDto(
                workerId = employeeId.toString(),
                workerName = employee.fullName,
                shifts = shiftDtos,
                totalHours = totalHours,
                totalPay = totalPay,
                utilization = (totalHours / employee.contract.maxHoursPerWeek) * 100
            )
        }

        // Calculate metrics
        val totalCost = assignments.sumOf { it.totalPay }
        val totalHours = assignments.sumOf { it.totalHours }
        val avgUtilization = if (assignments.isNotEmpty()) {
            assignments.map { it.utilization }.average()
        } else 0.0

        val metrics = ResultMetricsDto(
            totalLaborCost = totalCost,
            totalHours = totalHours,
            coveragePercent = 100.0,  // Assume full coverage if schedule generated
            averageUtilization = avgUtilization,
            numberOfWorkers = assignments.size,
            numberOfShifts = schedule.shifts.size
        )

        return OptimizationResultDto(
            solveStatus = "OPTIMAL",
            isOptimal = true,
            solveTimeMs = solveTimeMs,
            assignments = assignments,
            metrics = metrics,
            violations = emptyList()  // Constraints checked by ShiftScheduler
        )
    }

    /**
     * Clean up old completed jobs (for memory management).
     */
    fun cleanupOldJobs(olderThanHours: Long = 24) {
        val cutoffTime = LocalDateTime.now().minusHours(olderThanHours)
        val toRemove = jobs.filter { (_, job) ->
            job.status == JobStatus.COMPLETED || job.status == JobStatus.FAILED &&
            job.completedAt?.isBefore(cutoffTime) == true
        }.keys

        toRemove.forEach { jobs.remove(it) }
        if (toRemove.isNotEmpty()) {
            log.info("[OptimizationJobService] Cleaned up ${toRemove.size} old jobs")
        }
    }
}

/**
 * Internal job representation.
 */
data class OptimizationJob(
    val id: String,
    val status: JobStatus,
    val businessId: UUID,
    val request: OptimizationRequestV2,
    val progress: Int? = null,
    val solveStatus: SolveStatus? = null,
    val solveTimeMs: Long? = null,
    val result: OptimizationResultDto? = null,
    val error: String? = null,
    val createdAt: LocalDateTime,
    val completedAt: LocalDateTime? = null
)

enum class JobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}

enum class SolveStatus {
    OPTIMAL,
    FEASIBLE,
    INFEASIBLE,
    TIMEOUT
}
