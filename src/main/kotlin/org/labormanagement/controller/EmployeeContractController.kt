package org.labormanagement.controller

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.labormanagement.dto.EmployeeContractResponse
import org.labormanagement.dto.EmployeeContractsListResponse
import org.labormanagement.dto.toResponse
import org.labormanagement.model.ALLOWED_CONTRACT_CONTENT_TYPES
import org.labormanagement.model.EmployeeContract
import org.labormanagement.model.MAX_CONTRACT_SIZE_BYTES
import org.labormanagement.model.UserRole
import org.labormanagement.repository.EmployeeContractRepository
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.service.effectiveRoleOr
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Contract documents for an employee.
 *
 * Two audiences, deliberately on separate routes:
 *
 *  - Managers work under /api/businesses/{businessId}/employees/{employeeId}/contracts
 *    and may upload, list, download and delete. Restricted to the employee's
 *    *home* business, matching how EmployeeLocationController treats assignment:
 *    a location that has merely borrowed someone does not get their paperwork.
 *
 *  - The employee themselves works under /api/employees/me/contracts, which is
 *    not business-scoped (the caller does not know their businessId - the same
 *    reasoning as GET /api/employees/me) and is read-only. Nobody can reach
 *    another employee's documents through it, because the employee id is taken
 *    from the token rather than the URL.
 */
class EmployeeContractController(
    private val contractRepository: EmployeeContractRepository,
    private val employeeRepository: EmployeeRepository
) {
    private val logger = LoggerFactory.getLogger(EmployeeContractController::class.java)

    fun Route.employeeContractRoutes() {
        employeeSelfServiceRoutes()
        managerRoutes()
    }

    /**
     * The employee's own read-only view of their contracts.
     */
    private fun Route.employeeSelfServiceRoutes() {
        route("/api/employees/me/contracts") {
            authenticate("auth-jwt") {

                get {
                    val employeeId = call.requireOwnEmployeeId() ?: return@get
                    call.respond(
                        HttpStatusCode.OK,
                        EmployeeContractsListResponse(
                            employeeId = employeeId.toString(),
                            contracts = contractRepository.findByEmployee(employeeId).map { it.toResponse() }
                        )
                    )
                }

                get("/{contractId}/download") {
                    val employeeId = call.requireOwnEmployeeId() ?: return@get
                    val contractId = call.contractIdParam() ?: return@get
                    call.respondContractDownload(employeeId, contractId)
                }
            }
        }
    }

    /**
     * Manager management of one employee's contracts.
     */
    private fun Route.managerRoutes() {
        route("/api/businesses/{businessId}/employees/{employeeId}/contracts") {
            authenticate("auth-jwt") {

                get {
                    val ctx = call.requireManagerOfHomeBusiness() ?: return@get
                    call.respond(
                        HttpStatusCode.OK,
                        EmployeeContractsListResponse(
                            employeeId = ctx.employeeId.toString(),
                            contracts = contractRepository.findByEmployee(ctx.employeeId).map { it.toResponse() }
                        )
                    )
                }

                post {
                    val ctx = call.requireManagerOfHomeBusiness() ?: return@post

                    val upload = try {
                        call.readContractUpload()
                    } catch (e: ContractUploadException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                        return@post
                    } catch (e: Exception) {
                        call.application.log.error("Failed to read contract upload", e)
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Could not read the uploaded file"))
                        return@post
                    }

                    val saved = contractRepository.create(
                        EmployeeContract(
                            employeeId = ctx.employeeId,
                            businessId = ctx.businessId,
                            fileName = upload.fileName,
                            contentType = upload.contentType,
                            sizeBytes = upload.bytes.size.toLong(),
                            content = upload.bytes,
                            uploadedBy = ctx.userId,
                            uploadedAt = Instant.now()
                        )
                    )

                    logger.info(
                        "[EmployeeContractController] ${ctx.userId} uploaded contract ${saved.id} " +
                            "(${saved.sizeBytes} bytes) for employee ${ctx.employeeId}"
                    )

                    call.respond(HttpStatusCode.Created, saved.toResponse())
                }

                get("/{contractId}/download") {
                    val ctx = call.requireManagerOfHomeBusiness() ?: return@get
                    val contractId = call.contractIdParam() ?: return@get
                    call.respondContractDownload(ctx.employeeId, contractId)
                }

                delete("/{contractId}") {
                    val ctx = call.requireManagerOfHomeBusiness() ?: return@delete
                    val contractId = call.contractIdParam() ?: return@delete

                    if (!contractRepository.delete(ctx.employeeId, contractId)) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Contract not found"))
                        return@delete
                    }

                    logger.info(
                        "[EmployeeContractController] ${ctx.userId} deleted contract $contractId " +
                            "for employee ${ctx.employeeId}"
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }

    /**
     * Send the file itself. Shared by both audiences - by this point the caller
     * has already been proven entitled to this employee's documents.
     */
    private suspend fun ApplicationCall.respondContractDownload(employeeId: UUID, contractId: UUID) {
        val contract = contractRepository.findWithContent(employeeId, contractId)
        if (contract?.content == null) {
            respond(HttpStatusCode.NotFound, mapOf("error" to "Contract not found"))
            return
        }

        // Attachment rather than inline: these are uploaded files, and letting
        // the browser render one in the page's own origin is how a stored file
        // turns into a scripting hole.
        response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment
                .withParameter(ContentDisposition.Parameters.FileName, contract.fileName)
                .toString()
        )
        respondBytes(
            bytes = contract.content,
            contentType = ContentType.parse(contract.contentType)
        )
    }

    private data class ContractContext(
        val userId: String,
        val businessId: UUID,
        val employeeId: UUID
    )

    /**
     * Require an ADMIN/MANAGER caller, and that the employee genuinely belongs
     * to the business in the path.
     *
     * findOwnedById rather than findById is the whole point: it matches only
     * the employee's home business, so a location that has borrowed someone
     * cannot read or delete their contracts.
     *
     * Responds and returns null on failure, so callers can `?: return@get`.
     */
    private suspend fun ApplicationCall.requireManagerOfHomeBusiness(): ContractContext? {
        val principal = principal<JWTPrincipal>()
        if (principal == null) {
            respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            return null
        }
        val userId = principal.payload.getClaim("userId").asString()

        val businessId = parameters["businessId"]?.let {
            try { UUID.fromString(it) } catch (e: Exception) { null }
        }
        val employeeId = parameters["employeeId"]?.let {
            try { UUID.fromString(it) } catch (e: Exception) { null }
        }
        if (businessId == null || employeeId == null) {
            respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business or employee ID"))
            return null
        }

        val callerRole = effectiveRoleOr(
            try {
                UserRole.valueOf(principal.payload.getClaim("role").asString())
            } catch (e: Exception) {
                UserRole.EMPLOYEE
            }
        )
        if (callerRole != UserRole.ADMIN && callerRole != UserRole.MANAGER) {
            respond(HttpStatusCode.Forbidden, mapOf("error" to "Requires ADMIN or MANAGER role"))
            return null
        }

        if (employeeRepository.findOwnedById(businessId, employeeId) == null) {
            respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "This employee's contracts are managed by their home location")
            )
            return null
        }

        return ContractContext(userId, businessId, employeeId)
    }

    /**
     * Resolve the caller's *own* employee record. The id never comes from the
     * URL here, so this route cannot be pointed at somebody else.
     */
    private suspend fun ApplicationCall.requireOwnEmployeeId(): UUID? {
        val principal = principal<JWTPrincipal>()
        if (principal == null) {
            respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            return null
        }
        val userId = principal.payload.getClaim("userId").asString()
        val employee = employeeRepository.findByUserId(userId)
        if (employee == null) {
            respond(HttpStatusCode.NotFound, mapOf("error" to "No employee record linked to this account"))
            return null
        }
        return employee.id
    }

    private suspend fun ApplicationCall.contractIdParam(): UUID? {
        val contractId = parameters["contractId"]?.let {
            try { UUID.fromString(it) } catch (e: Exception) { null }
        }
        if (contractId == null) {
            respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid contract ID"))
            return null
        }
        return contractId
    }

    private class ContractUploadException(message: String) : Exception(message)

    private class ContractUpload(
        val fileName: String,
        val contentType: String,
        val bytes: ByteArray
    )

    /**
     * Pull the single file out of a multipart upload.
     *
     * Size is enforced against the bytes actually read, not the declared
     * Content-Length, so an understated header cannot smuggle a large file past
     * the cap and onto a 384MB heap.
     */
    private suspend fun ApplicationCall.readContractUpload(): ContractUpload {
        var fileName: String? = null
        var contentType: String? = null
        var bytes: ByteArray? = null

        receiveMultipart().forEachPart { part ->
            if (part is PartData.FileItem && bytes == null) {
                fileName = part.originalFileName?.substringAfterLast('/')?.substringAfterLast('\\')
                contentType = part.contentType?.withoutParameters()?.toString()
                bytes = part.streamProvider().use { it.readBytes() }
            }
            part.dispose()
        }

        val readBytes = bytes ?: throw ContractUploadException("No file was included in the upload")
        val name = fileName?.takeIf { it.isNotBlank() }
            ?: throw ContractUploadException("The uploaded file has no name")
        val type = contentType?.takeIf { it.isNotBlank() }
            ?: throw ContractUploadException("The uploaded file has no content type")

        if (readBytes.isEmpty()) {
            throw ContractUploadException("The uploaded file is empty")
        }
        if (readBytes.size > MAX_CONTRACT_SIZE_BYTES) {
            throw ContractUploadException(
                "Contract must be ${MAX_CONTRACT_SIZE_BYTES / (1024 * 1024)}MB or smaller"
            )
        }
        if (type !in ALLOWED_CONTRACT_CONTENT_TYPES) {
            throw ContractUploadException("Contracts must be a PDF, Word document, or image")
        }

        return ContractUpload(name.take(255), type, readBytes)
    }
}
