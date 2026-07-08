package com.sephilabs.sharedledger.networth.csv

import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.HouseholdRepository
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}")
class NamedCsvExportController(
    private val export: NamedCsvExportService,
    private val households: HouseholdRepository,
) {

    @GetMapping("/assets/export.csv")
    fun exportAssets(@PathVariable householdId: UUID): ResponseEntity<String> =
        csv(householdId, "assets", export.exportAssets(householdId))

    @GetMapping("/liabilities/export.csv")
    fun exportLiabilities(@PathVariable householdId: UUID): ResponseEntity<String> =
        csv(householdId, "liabilities", export.exportLiabilities(householdId))

    @GetMapping("/liabilities/amortization/export.csv")
    fun exportAmortization(@PathVariable householdId: UUID): ResponseEntity<String> =
        csv(householdId, "amortization", export.exportAmortization(householdId))

    private fun csv(householdId: UUID, suffix: String, body: String): ResponseEntity<String> {
        val household = households.findById(householdId).orElseThrow { AppException.notFound("HOUSEHOLD_NOT_FOUND") }
        val filename = Csv.exportFilename(LocalDate.now(), household.name, suffix)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(body)
    }
}
