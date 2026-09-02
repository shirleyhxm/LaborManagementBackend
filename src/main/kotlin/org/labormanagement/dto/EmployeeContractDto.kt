package org.labormanagement.dto

import org.labormanagement.model.EmployeeContract

/**
 * One contract document, without its bytes - the file is fetched separately
 * from the download endpoint.
 */
data class EmployeeContractResponse(
    val id: String,
    val employeeId: String,
    val businessId: String,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val uploadedBy: String,
    val uploadedAt: String
)

data class EmployeeContractsListResponse(
    val employeeId: String,
    val contracts: List<EmployeeContractResponse>
)

fun EmployeeContract.toResponse(): EmployeeContractResponse = EmployeeContractResponse(
    id = id.toString(),
    employeeId = employeeId.toString(),
    businessId = businessId.toString(),
    fileName = fileName,
    contentType = contentType,
    sizeBytes = sizeBytes,
    uploadedBy = uploadedBy,
    uploadedAt = uploadedAt.toString()
)
