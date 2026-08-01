package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.CsvReader
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.common.import.ExecuteResult
import com.sephilabs.sharedledger.common.import.MAX_ERRORS_REPORTED
import com.sephilabs.sharedledger.common.import.MAX_SKIPPED_REPORTED
import com.sephilabs.sharedledger.common.import.PreviewSummary
import com.sephilabs.sharedledger.common.import.RowError
import com.sephilabs.sharedledger.common.import.SkippedRow
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.portfolio.price.EquityPriceProvider
import com.sephilabs.sharedledger.portfolio.price.asHoldingProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Currency
import java.util.UUID

/** CSV import of holdings as purchase lots: one row per lot, rows sharing (asset_class, symbol) belong to
 *  one holding created on demand. Lots have no free-text identity, so duplicates are skipped rather than
 *  renamed like transactions — deduped against both the database and the file itself. */
@Service
class PortfolioImportService(
    private val holdings: HoldingRepository,
    private val lots: HoldingLotRepository,
    private val holdingService: HoldingService,
    private val equity: EquityPriceProvider,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(PortfolioImportService::class.java)

    private val requiredHeaders = listOf(
        "type", "asset_class", "symbol", "label", "native_currency", "isin",
        "traded_on", "quantity", "unit_price", "cost_currency", "fee", "note",
    )

    // Provider coordinates are an optional way to link a holding to its price source.
    // Older files (and hand-written ones) may omit them; export always writes them.
    private val optionalHeaders = listOf("provider", "provider_symbol")

    // Not read-only: previewing a new foreign currency may fetch FX history on demand.
    @Transactional
    fun preview(householdId: UUID, input: InputStream): PreviewSummary {
        val parsed = parseAndValidate(householdId, input)
        return parsed.toPreview()
    }

    @Transactional
    fun execute(householdId: UUID, input: InputStream, importer: User): ExecuteResult {
        log.info("Portfolio import started: household={} importer={}", householdId, importer.id)
        val parsed = parseAndValidate(householdId, input)
        if (parsed.errors.isNotEmpty()) {
            log.warn("Portfolio import aborted: {} validation errors", parsed.errors.size)
            throw AppException.badRequest("IMPORT_VALIDATION_FAILED")
        }

        val holdingsByKey = holdings.findAllByHouseholdIdOrderBySymbolAsc(householdId)
            .associateBy { it.assetClass to it.symbol }
            .toMutableMap()
        val seenKeys = parsed.existingKeys
        var inserted = 0
        val skippedList = mutableListOf<SkippedRow>()
        var skipped = 0

        // Insert in ledger order (per holding: by date, BUYs before SELLs) so that the
        // per-mutation oversell validation in HoldingService sees a consistent ledger.
        val ordered = parsed.validRows.sortedWith(
            compareBy({ it.assetClass }, { it.symbol }, { it.tradedOn }, { it.type != LotType.BUY }),
        )
        for (row in ordered) {
            val key = row.dedupKey()
            if (!seenKeys.add(key)) {
                skipped++
                if (skippedList.size < MAX_SKIPPED_REPORTED) skippedList.add(SkippedRow(row.rowNumber, row.summary()))
                continue
            }
            val holdingKey = row.assetClass to row.symbol
            val holding = holdingsByKey.getOrPut(holdingKey) { createHolding(householdId, row, importer) }
            holdingService.addLot(
                householdId, holding.id,
                LotRequest(
                    type = row.type,
                    tradedOn = row.tradedOn,
                    quantity = row.quantity,
                    unitPrice = row.unitPrice,
                    currency = row.costCurrency,
                    fee = row.fee,
                    note = row.note,
                ),
                importer,
                // Imports write in bulk and must stay silent, like every other importer.
                notify = false,
            )
            inserted++
        }

        log.info("Portfolio import finished: inserted={} skipped={} totalRows={}", inserted, skipped, parsed.totalRows)
        return ExecuteResult(
            inserted = inserted,
            skipped = skipped,
            replaced = 0,
            skippedRows = skippedList,
            truncatedSkipped = skipped > skippedList.size,
        )
    }

    private fun createHolding(householdId: UUID, row: ParsedRow, importer: User): Holding {
        val dto = holdingService.create(
            householdId,
            HoldingRequest(
                assetClass = row.assetClass,
                symbol = row.symbol,
                label = row.label,
                nativeCurrency = row.nativeCurrency,
                isin = row.isin,
            ),
            importer,
        )
        val holding = holdingService.loadOwn(householdId, dto.id)
        if (row.provider != null && row.providerSymbol != null) {
            linkExplicitly(householdId, holding, row, importer)
        } else {
            autoLinkByIsin(householdId, holding, importer)
        }
        return holding
    }

    /** The file named the price source directly; provider trouble never fails the import. */
    private fun linkExplicitly(householdId: UUID, holding: Holding, row: ParsedRow, importer: User) {
        try {
            holdingService.link(
                householdId, holding.id,
                LinkRequest(
                    provider = row.provider!!,
                    providerSymbol = row.providerSymbol!!,
                    nativeCurrency = row.nativeCurrency,
                    isin = row.isin,
                ),
                importer,
            )
        } catch (ex: Exception) {
            log.info("Explicit link skipped for {} ({}): {}", holding.symbol, row.providerSymbol, ex.message)
        }
    }

    /** Best-effort ISIN resolution for new equity holdings; provider trouble never fails the import. */
    private fun autoLinkByIsin(householdId: UUID, holding: Holding, importer: User) {
        val isin = holding.isin ?: return
        if (holding.assetClass != HoldingAssetClass.etf && holding.assetClass != HoldingAssetClass.stock) return
        try {
            val matches = equity.searchByIsin(isin)
                .filter { it.currency == null || it.currency == holding.nativeCurrency }
            val match = matches.singleOrNull() ?: return
            holdingService.link(
                householdId, holding.id,
                LinkRequest(
                    provider = props.portfolio.equityProvider.asHoldingProvider(),
                    providerSymbol = match.providerSymbol,
                    nativeCurrency = match.currency,
                ),
                importer,
            )
        } catch (ex: Exception) {
            log.info("ISIN auto-link skipped for {} ({}): {}", holding.symbol, isin, ex.message)
        }
    }

    private fun parseAndValidate(householdId: UUID, input: InputStream): ParsedFile {
        val parsed = CsvReader.parse(input)
        if (parsed.parseError != null) throw AppException.badRequest("IMPORT_PARSE_FAILED")
        val headerErrors = validateHeaders(parsed.headers)
        if (headerErrors.isNotEmpty()) {
            return ParsedFile(parsed.rows.size, headerErrors, mutableListOf(), mutableSetOf(), BigDecimal.ZERO, null, null)
        }

        val errors = mutableListOf<RowError>()
        val validRows = mutableListOf<ParsedRow>()
        var sumCostBasis = BigDecimal.ZERO
        var minDate: LocalDate? = null
        var maxDate: LocalDate? = null

        for ((idx, raw) in parsed.rows.withIndex()) {
            val rowNo = idx + 2
            val rowErrors = mutableListOf<RowError>()

            val typeRaw = raw["type"].orEmpty().trim().uppercase()
            val type =
                if (typeRaw.isEmpty()) LotType.BUY
                else runCatching { LotType.valueOf(typeRaw) }.getOrNull()
            if (type == null) {
                rowErrors += RowError(rowNo, "IMPORT_TYPE_INVALID", "type", raw["type"])
            }

            val assetClass = raw["asset_class"]?.trim()?.lowercase()
                ?.let { value -> runCatching { HoldingAssetClass.valueOf(value) }.getOrNull() }
            if (assetClass == null) {
                rowErrors += RowError(rowNo, "IMPORT_ASSET_CLASS_INVALID", "asset_class", raw["asset_class"])
            }

            val symbol = raw["symbol"].orEmpty().trim().uppercase()
            if (symbol.isEmpty() || symbol.length > 32) {
                rowErrors += RowError(rowNo, "IMPORT_SYMBOL_INVALID", "symbol", raw["symbol"])
            }

            val label = raw["label"]?.trim()?.takeIf { it.isNotEmpty() }

            val nativeCurrency = parseCurrency(raw["native_currency"])
            if (raw["native_currency"].orEmpty().isNotBlank() && nativeCurrency == null) {
                rowErrors += RowError(rowNo, "IMPORT_CURRENCY_INVALID", "native_currency", raw["native_currency"])
            }

            val isin = raw["isin"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            if (isin != null && !isin.matches(ISIN_PATTERN)) {
                rowErrors += RowError(rowNo, "IMPORT_ISIN_INVALID", "isin", raw["isin"])
            }

            // Optional explicit link: provider + provider_symbol must be given together.
            val providerRaw = raw["provider"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val providerSymbol = raw["provider_symbol"]?.trim()?.takeIf { it.isNotEmpty() }
            val provider = providerRaw?.let { runCatching { HoldingProvider.valueOf(it) }.getOrNull() }
            if (providerRaw != null && provider == null) {
                rowErrors += RowError(rowNo, "IMPORT_PROVIDER_INVALID", "provider", raw["provider"])
            }
            if ((providerRaw == null) != (providerSymbol == null)) {
                rowErrors += RowError(rowNo, "IMPORT_PROVIDER_INCOMPLETE", "provider", null)
            }

            val tradedOn = parseDate(raw["traded_on"])
            if (tradedOn == null) {
                rowErrors += RowError(rowNo, "IMPORT_DATE_INVALID", "traded_on", raw["traded_on"])
            } else if (tradedOn.isAfter(LocalDate.now().plusYears(1))) {
                rowErrors += RowError(rowNo, "IMPORT_DATE_FAR_FUTURE", "traded_on", raw["traded_on"])
            }

            val quantity = Csv.parseDecimal(raw["quantity"].orEmpty())
            if (quantity == null || quantity.signum() <= 0) {
                rowErrors += RowError(rowNo, "IMPORT_QUANTITY_INVALID", "quantity", raw["quantity"])
            }

            val unitPrice = Csv.parseDecimal(raw["unit_price"].orEmpty())
            if (unitPrice == null || unitPrice.signum() < 0) {
                rowErrors += RowError(rowNo, "IMPORT_UNIT_PRICE_INVALID", "unit_price", raw["unit_price"])
            }

            val costCurrency = parseCurrency(raw["cost_currency"])
            if (raw["cost_currency"].orEmpty().isNotBlank() && costCurrency == null) {
                rowErrors += RowError(rowNo, "IMPORT_CURRENCY_INVALID", "cost_currency", raw["cost_currency"])
            }

            val feeRaw = raw["fee"].orEmpty()
            val fee = if (feeRaw.isBlank()) null else Csv.parseDecimal(feeRaw)
            if (feeRaw.isNotBlank() && (fee == null || fee.signum() < 0)) {
                rowErrors += RowError(rowNo, "IMPORT_FEE_INVALID", "fee", raw["fee"])
            }

            val note = raw["note"]?.trim()?.takeIf { it.isNotEmpty() }
            if (note != null && note.length > MAX_NOTE_LENGTH) {
                rowErrors += RowError(rowNo, "IMPORT_DESCRIPTION_TOO_LONG", "note", null)
            }

            // The trade's FX rate freezes at import; the currency must be resolvable now.
            val lotCurrency = costCurrency ?: nativeCurrency ?: props.portfolio.baseCurrency
            var fxRate: BigDecimal? = null
            if (rowErrors.isEmpty()) {
                fxRate = try {
                    holdingService.fxRateToBase(lotCurrency, tradedOn!!)
                } catch (ex: AppException) {
                    rowErrors += RowError(rowNo, "LOT_FX_RATE_UNAVAILABLE", "cost_currency", lotCurrency)
                    null
                }
            }

            if (rowErrors.isEmpty()) {
                val row = ParsedRow(
                    rowNumber = rowNo,
                    type = type!!,
                    assetClass = assetClass!!,
                    symbol = symbol,
                    label = label,
                    nativeCurrency = nativeCurrency,
                    isin = isin,
                    provider = provider,
                    providerSymbol = providerSymbol,
                    tradedOn = tradedOn!!,
                    quantity = quantity!!,
                    unitPrice = unitPrice!!,
                    costCurrency = lotCurrency,
                    fee = fee,
                    note = note,
                    fxRate = fxRate ?: BigDecimal.ONE,
                )
                validRows += row
                if (type == LotType.BUY) {
                    sumCostBasis += quantity.multiply(unitPrice, MC)
                        .add(fee ?: BigDecimal.ZERO, MC)
                        .multiply(fxRate ?: BigDecimal.ONE, MC)
                }
                if (minDate == null || tradedOn.isBefore(minDate)) minDate = tradedOn
                if (maxDate == null || tradedOn.isAfter(maxDate)) maxDate = tradedOn
            } else {
                addErrors(errors, rowErrors)
            }
        }

        val existingKeys = existingLotKeys(householdId)
        validateSells(householdId, validRows, existingKeys, errors)
        return ParsedFile(parsed.rows.size, errors, validRows, existingKeys, Money.normalize(sumCostBasis), minDate, maxDate)
    }

    /** Simulates the post-import ledger per holding so an oversell is a row error at preview, not a mid-import
     *  failure. */
    private fun validateSells(
        householdId: UUID,
        validRows: List<ParsedRow>,
        existingKeys: Set<String>,
        errors: MutableList<RowError>,
    ) {
        if (validRows.none { it.type == LotType.SELL }) return
        val existingHoldings = holdings.findAllByHouseholdIdOrderBySymbolAsc(householdId)
            .associateBy { it.assetClass to it.symbol }
        val seen = existingKeys.toMutableSet()
        val rowsByHolding = validRows
            .filter { seen.add(it.dedupKey()) }
            .groupBy { it.assetClass to it.symbol }
        for ((key, rows) in rowsByHolding) {
            val existingEntries = existingHoldings[key]
                ?.let { holding -> lots.findAllByHoldingIdOrderByTradedOnAscCreatedAtAsc(holding.id).map { it.toEntry() } }
                ?: emptyList()
            val newEntries = rows.map {
                PortfolioValuationCalculator.LedgerEntry(it.type, it.tradedOn, it.quantity, it.unitPrice, it.fee, it.fxRate)
            }
            try {
                PortfolioValuationCalculator.replay(
                    existingEntries + newEntries, props.portfolio.costMethod, strict = true,
                )
            } catch (ex: PortfolioValuationCalculator.OversellException) {
                val offending = rows.firstOrNull { it.type == LotType.SELL && it.tradedOn == ex.tradedOn }
                    ?: rows.first { it.type == LotType.SELL }
                addErrors(errors, listOf(RowError(offending.rowNumber, "LOT_SELL_EXCEEDS_HOLDINGS", "quantity", null)))
            }
        }
    }

    private fun existingLotKeys(householdId: UUID): MutableSet<String> {
        val all = holdings.findAllByHouseholdIdOrderBySymbolAsc(householdId)
        if (all.isEmpty()) return mutableSetOf()
        val byId = all.associateBy { it.id }
        return lots.findAllByHoldingIdIn(byId.keys)
            .mapNotNull { lot ->
                byId[lot.holdingId]?.let { holding ->
                    lotKey(lot.type, holding.assetClass, holding.symbol, lot.tradedOn, lot.quantity, lot.unitPrice, lot.currency)
                }
            }
            .toMutableSet()
    }

    private fun validateHeaders(actual: List<String>): List<RowError> {
        val missing = requiredHeaders - actual.toSet()
        val unknown = actual - (requiredHeaders + optionalHeaders).toSet()
        if (missing.isEmpty() && unknown.isEmpty()) return emptyList()
        val args = (missing.map { "missing:$it" } + unknown.map { "unknown:$it" }).joinToString(", ")
        return listOf(RowError(1, "IMPORT_HEADER_INVALID", null, args))
    }

    private fun parseDate(value: String?): LocalDate? {
        if (value.isNullOrBlank()) return null
        return try { LocalDate.parse(value.trim()) } catch (_: DateTimeParseException) { null }
    }

    private fun parseCurrency(value: String?): String? {
        val trimmed = value?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        return if (runCatching { Currency.getInstance(trimmed) }.isSuccess) trimmed else null
    }

    private fun addErrors(sink: MutableList<RowError>, more: List<RowError>) {
        for (e in more) {
            if (sink.size >= MAX_ERRORS_REPORTED) return
            sink.add(e)
        }
    }

    private data class ParsedRow(
        val rowNumber: Int,
        val type: LotType,
        val assetClass: HoldingAssetClass,
        val symbol: String,
        val label: String?,
        val nativeCurrency: String?,
        val isin: String?,
        // Holding-level, not part of the lot dedupe identity: set when the file links explicitly.
        val provider: HoldingProvider?,
        val providerSymbol: String?,
        val tradedOn: LocalDate,
        val quantity: BigDecimal,
        val unitPrice: BigDecimal,
        val costCurrency: String,
        val fee: BigDecimal?,
        // Not part of the dedupe identity: re-importing an export with edited notes
        // still skips the matching trades rather than duplicating them.
        val note: String?,
        val fxRate: BigDecimal,
    ) {
        fun dedupKey(): String =
            lotKey(type, assetClass, symbol, tradedOn, quantity, unitPrice, costCurrency)

        fun summary(): String =
            "$tradedOn · ${type.name} · ${assetClass.name} · $symbol · ${plain(quantity)} × ${plain(unitPrice)}"
    }

    private data class ParsedFile(
        val totalRows: Int,
        val errors: List<RowError>,
        val validRows: MutableList<ParsedRow>,
        val existingKeys: MutableSet<String>,
        val sumCostBasis: BigDecimal,
        val dateFrom: LocalDate?,
        val dateTo: LocalDate?,
    ) {
        fun toPreview(): PreviewSummary {
            val seen = existingKeys.toMutableSet()
            val skippedList = mutableListOf<SkippedRow>()
            var wouldSkip = 0
            for (row in validRows) {
                if (!seen.add(row.dedupKey())) {
                    wouldSkip++
                    if (skippedList.size < MAX_SKIPPED_REPORTED) skippedList.add(SkippedRow(row.rowNumber, row.summary()))
                }
            }
            return PreviewSummary(
                totalRows = totalRows,
                wouldInsert = validRows.size - wouldSkip,
                wouldSkip = wouldSkip,
                wouldReplace = 0,
                errorCount = errors.size,
                errors = errors,
                truncatedErrors = errors.size >= MAX_ERRORS_REPORTED,
                skippedRows = skippedList,
                truncatedSkipped = wouldSkip > skippedList.size,
                sumAssets = sumCostBasis,
                dateFrom = dateFrom?.toString(),
                dateTo = dateTo?.toString(),
            )
        }
    }

    companion object {
        private val MC = MathContext.DECIMAL64
        private val ISIN_PATTERN = Regex("^[A-Z]{2}[A-Z0-9]{9}[0-9]$")
        private const val MAX_NOTE_LENGTH = 500

        private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

        private fun lotKey(
            type: LotType,
            assetClass: HoldingAssetClass,
            symbol: String,
            tradedOn: LocalDate,
            quantity: BigDecimal,
            unitPrice: BigDecimal,
            currency: String,
        ): String = "${type.name}|${assetClass.name}|$symbol|$tradedOn|${plain(quantity)}|${plain(unitPrice)}|$currency"
    }
}
