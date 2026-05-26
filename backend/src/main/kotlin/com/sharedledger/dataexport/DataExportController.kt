package com.sharedledger.dataexport

import com.sharedledger.common.Csv
import com.sharedledger.common.errors.AppException
import com.sharedledger.household.HouseholdRepository
import com.sharedledger.networth.movement.MovementService
import com.sharedledger.networth.snapshot.SnapshotService
import com.sharedledger.recurring.RecurringService
import com.sharedledger.transaction.TransactionSearchCriteria
import com.sharedledger.transaction.TransactionService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RestController
@RequestMapping("/api/households/{householdId}")
class DataExportController(
    private val transactions: TransactionService,
    private val recurring: RecurringService,
    private val movements: MovementService,
    private val snapshots: SnapshotService,
    private val households: HouseholdRepository,
) {

    @GetMapping("/export-all.zip")
    fun exportAll(@PathVariable householdId: UUID): ResponseEntity<ByteArray> {
        val household = households.findById(householdId)
            .orElseThrow { AppException.notFound("HOUSEHOLD_NOT_FOUND") }
        val today = LocalDate.now()

        val files = listOf(
            Csv.exportFilename(today, household.name, "transactions") to transactions.exportCsv(
                TransactionSearchCriteria(
                    householdId = householdId,
                    from = null,
                    to = null,
                    direction = null,
                    categoryCode = null,
                    categoryGroup = null,
                    page = 0,
                    size = Int.MAX_VALUE,
                    sort = "date_asc",
                ),
            ),
            Csv.exportFilename(today, household.name, "recurring-templates") to recurring.exportCsv(householdId),
            Csv.exportFilename(today, household.name, "movements") to movements.exportCsv(householdId),
            Csv.exportFilename(today, household.name, "snapshots") to snapshots.exportCsv(householdId),
        )

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, body) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }

        val zipName = Csv.exportFilename(today, household.name, "all").removeSuffix(".csv") + ".zip"
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$zipName\"")
            .body(out.toByteArray())
    }
}
