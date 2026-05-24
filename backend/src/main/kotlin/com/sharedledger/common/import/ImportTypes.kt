package com.sharedledger.common.import

import com.fasterxml.jackson.annotation.JsonFormat
import java.math.BigDecimal

const val MAX_ERRORS_REPORTED: Int = 500
const val MAX_SKIPPED_REPORTED: Int = 100

data class RowError(
    val row: Int,
    val code: String,
    val field: String? = null,
    val value: String? = null,
)

data class SkippedRow(
    val row: Int,
    val summary: String,
)

data class PreviewSummary(
    val totalRows: Int,
    val wouldInsert: Int,
    val wouldSkip: Int,
    val wouldReplace: Int,
    val errorCount: Int,
    val errors: List<RowError>,
    val truncatedErrors: Boolean,
    val skippedRows: List<SkippedRow> = emptyList(),
    val truncatedSkipped: Boolean = false,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val sumIncome: BigDecimal? = null,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val sumExpense: BigDecimal? = null,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val sumAssets: BigDecimal? = null,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val sumLiabilities: BigDecimal? = null,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val sumContributions: BigDecimal? = null,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val sumWithdrawals: BigDecimal? = null,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val sumDebtPayments: BigDecimal? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
)

data class ExecuteResult(
    val inserted: Int,
    val skipped: Int,
    val replaced: Int,
    val failedRowsCsv: String? = null,
    val skippedRows: List<SkippedRow> = emptyList(),
    val truncatedSkipped: Boolean = false,
)
