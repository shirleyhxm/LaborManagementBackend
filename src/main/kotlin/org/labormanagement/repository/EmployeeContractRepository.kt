package org.labormanagement.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.database.EmployeeContracts
import org.labormanagement.model.EmployeeContract
import java.util.UUID

/**
 * Contract documents, stored as bytes in the database.
 *
 * The file bytes are only ever read by [findWithContent], for a single row, on
 * the download path. Every other query lists metadata and leaves the blob
 * column unselected - with a 384MB heap, a `selectAll()` on this table would
 * load every stored contract into memory just to render a list of file names.
 */
class EmployeeContractRepository {

    /**
     * Columns making up a listing: everything except the file bytes.
     */
    private val metadataColumns = listOf(
        EmployeeContracts.id,
        EmployeeContracts.employeeId,
        EmployeeContracts.businessId,
        EmployeeContracts.fileName,
        EmployeeContracts.contentType,
        EmployeeContracts.sizeBytes,
        EmployeeContracts.uploadedBy,
        EmployeeContracts.uploadedAt
    )

    fun create(contract: EmployeeContract): EmployeeContract = transaction {
        EmployeeContracts.insert {
            it[id] = contract.id
            it[employeeId] = contract.employeeId
            it[businessId] = contract.businessId
            it[fileName] = contract.fileName
            it[contentType] = contract.contentType
            it[sizeBytes] = contract.sizeBytes
            it[content] = contract.content ?: ByteArray(0)
            it[uploadedBy] = contract.uploadedBy
            it[uploadedAt] = contract.uploadedAt
        }
        contract
    }

    /**
     * One employee's contracts, newest first. Metadata only - the returned
     * models have a null `content`.
     */
    fun findByEmployee(employeeId: UUID): List<EmployeeContract> = transaction {
        EmployeeContracts
            .slice(metadataColumns)
            .select { EmployeeContracts.employeeId eq employeeId }
            .orderBy(EmployeeContracts.uploadedAt, SortOrder.DESC)
            .map { it.toMetadata() }
    }

    /**
     * A single contract's metadata, scoped to the employee it belongs to so a
     * caller cannot reach another employee's document by id alone.
     */
    fun find(employeeId: UUID, contractId: UUID): EmployeeContract? = transaction {
        EmployeeContracts
            .slice(metadataColumns)
            .select {
                (EmployeeContracts.id eq contractId) and
                    (EmployeeContracts.employeeId eq employeeId)
            }
            .singleOrNull()
            ?.toMetadata()
    }

    /**
     * A single contract *including* its bytes, for downloading. Scoped by
     * employee for the same reason as [find].
     */
    fun findWithContent(employeeId: UUID, contractId: UUID): EmployeeContract? = transaction {
        EmployeeContracts
            .select {
                (EmployeeContracts.id eq contractId) and
                    (EmployeeContracts.employeeId eq employeeId)
            }
            .singleOrNull()
            ?.let { it.toMetadata().copy(content = it[EmployeeContracts.content]) }
    }

    fun delete(employeeId: UUID, contractId: UUID): Boolean = transaction {
        EmployeeContracts.deleteWhere {
            (EmployeeContracts.id eq contractId) and
                (EmployeeContracts.employeeId eq employeeId)
        } > 0
    }

    /**
     * Drop every contract for an employee, e.g. when the employee is deleted -
     * the foreign key would otherwise block it.
     */
    fun deleteByEmployee(employeeId: UUID): Int = transaction {
        EmployeeContracts.deleteWhere { EmployeeContracts.employeeId eq employeeId }
    }

    /**
     * Clear all data (for testing).
     */
    fun clear() = transaction {
        EmployeeContracts.deleteAll()
    }

    /**
     * Build a model from a row that has no `content` selected. The bytes stay
     * null, which is what every caller but the download path wants.
     */
    private fun ResultRow.toMetadata(): EmployeeContract = EmployeeContract(
        id = this[EmployeeContracts.id],
        employeeId = this[EmployeeContracts.employeeId],
        businessId = this[EmployeeContracts.businessId],
        fileName = this[EmployeeContracts.fileName],
        contentType = this[EmployeeContracts.contentType],
        sizeBytes = this[EmployeeContracts.sizeBytes],
        content = null,
        uploadedBy = this[EmployeeContracts.uploadedBy],
        uploadedAt = this[EmployeeContracts.uploadedAt]
    )
}
