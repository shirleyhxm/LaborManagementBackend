package org.labormanagement.model

import java.time.Instant
import java.util.UUID

/**
 * Largest contract file we will accept, in bytes.
 *
 * The runtime container is capped at -Xmx384m, and an upload is held whole in
 * memory while it is read off the wire and handed to the driver. 10MB is
 * comfortably above any signed PDF while leaving the heap room to serve other
 * requests during an upload.
 */
const val MAX_CONTRACT_SIZE_BYTES = 10L * 1024 * 1024

/**
 * Content types we store. Contracts arrive as PDFs almost always, with the
 * occasional Word document or photographed signature page.
 *
 * Allow-listed rather than blocked, so an upload can never come back out of
 * the download endpoint as something a browser would execute.
 */
val ALLOWED_CONTRACT_CONTENT_TYPES = setOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "image/jpeg",
    "image/png"
)

/**
 * A contract document belonging to one employee.
 *
 * [content] is the file itself, stored in the database rather than on disk or
 * in object storage: contracts are small and low-volume, and keeping the bytes
 * transactional with the metadata means an upload cannot half-succeed and
 * leave a row pointing at a file that is not there.
 *
 * The bytes are deliberately nullable so listings can be read without them -
 * see EmployeeContractRepository.
 */
data class EmployeeContract(
    val id: UUID = UUID.randomUUID(),
    val employeeId: UUID,
    // The employee's *home* business. Only that business can manage contracts,
    // so this is the record's owner, not whichever location happened to upload.
    val businessId: UUID,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    // Null when the row was loaded for a listing, where selecting the blob
    // would pull every file into memory to render a list of names.
    val content: ByteArray? = null,
    val uploadedBy: String,
    val uploadedAt: Instant = Instant.now()
) {
    // ByteArray gives data classes reference equality, which would make two
    // reads of the same row compare unequal. Compared by id instead, which is
    // the only identity that matters here.
    override fun equals(other: Any?): Boolean = this === other || (other is EmployeeContract && other.id == id)

    override fun hashCode(): Int = id.hashCode()
}
