package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.notification.NotifyAction
import com.sephilabs.sharedledger.notification.NotifyActor
import com.sephilabs.sharedledger.notification.NotificationPublisher
import com.sephilabs.sharedledger.portfolio.price.FxRateRepository
import com.sephilabs.sharedledger.portfolio.price.HoldingBackfillRequested
import com.sephilabs.sharedledger.portfolio.price.PriceRefreshService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class HoldingService(
    private val holdings: HoldingRepository,
    private val lots: HoldingLotRepository,
    private val fxRates: FxRateRepository,
    private val priceRefresh: PriceRefreshService,
    private val notifications: NotificationPublisher,
    private val events: ApplicationEventPublisher,
    private val props: AppProperties,
) {

    private val baseCurrency: String get() = props.portfolio.baseCurrency

    @Transactional(readOnly = true)
    fun list(householdId: UUID): List<HoldingDto> =
        holdings.findAllByHouseholdIdOrderBySymbolAsc(householdId).map { toDto(it) }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, id: UUID): HoldingDto = toDto(loadOwn(householdId, id))

    @Transactional
    fun create(householdId: UUID, request: HoldingRequest, by: User): HoldingDto {
        val symbol = normalizeSymbol(request.symbol)
        if ((request.provider == null) != (request.providerSymbol.isNullOrBlank())) {
            throw AppException.badRequest("HOLDING_LINK_INVALID")
        }
        if (holdings.existsByHouseholdIdAndAssetClassAndSymbol(householdId, request.assetClass, symbol)) {
            throw AppException.badRequest("HOLDING_DUPLICATE_SYMBOL")
        }
        val holding = Holding(
            householdId = householdId,
            assetClass = request.assetClass,
            symbol = symbol,
            label = request.label?.trim()?.takeIf { it.isNotEmpty() },
            nativeCurrency = normalizeCurrency(request.nativeCurrency) ?: baseCurrency,
            isin = normalizeIsin(request.isin),
            provider = request.provider,
            providerSymbol = request.providerSymbol?.trim()?.takeIf { it.isNotEmpty() },
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        holdings.save(holding)
        // Backfill runs async after commit; a provider outage must never fail the create.
        if (holding.linked) events.publishEvent(HoldingBackfillRequested(holding.id))
        return toDto(holding)
    }

    @Transactional
    fun link(householdId: UUID, id: UUID, request: LinkRequest, by: User): HoldingDto {
        val holding = loadOwn(householdId, id)
        holding.provider = request.provider
        holding.providerSymbol = request.providerSymbol.trim()
        request.nativeCurrency?.let { currency ->
            normalizeCurrency(currency)?.let { holding.nativeCurrency = it }
        }
        request.isin?.let { holding.isin = normalizeIsin(it) }
        holding.updatedByUserId = by.id
        // Non-fatal: backfill runs async after commit; nightly jobs catch up on any failure.
        events.publishEvent(HoldingBackfillRequested(holding.id))
        return toDto(holding)
    }

    @Transactional
    fun unlink(householdId: UUID, id: UUID, by: User): HoldingDto {
        val holding = loadOwn(householdId, id)
        if (!holding.linked) throw AppException.badRequest("HOLDING_NOT_LINKED")
        // Stored price history stays: it is keyed by provider coordinates and shareable.
        holding.provider = null
        holding.providerSymbol = null
        holding.updatedByUserId = by.id
        return toDto(holding)
    }

    @Transactional
    fun update(householdId: UUID, id: UUID, request: HoldingUpdateRequest, by: User): HoldingDto {
        val holding = loadOwn(householdId, id)
        request.symbol?.let {
            val symbol = normalizeSymbol(it)
            if (symbol != holding.symbol &&
                holdings.existsByHouseholdIdAndAssetClassAndSymbol(householdId, holding.assetClass, symbol)
            ) {
                throw AppException.badRequest("HOLDING_DUPLICATE_SYMBOL")
            }
            holding.symbol = symbol
        }
        request.label?.let { holding.label = it.trim().takeIf { l -> l.isNotEmpty() } }
        request.isin?.let { holding.isin = normalizeIsin(it) }
        request.nativeCurrency?.let {
            val currency = normalizeCurrency(it) ?: return@let
            if (currency != holding.nativeCurrency) {
                // The provider dictates the price currency once linked.
                if (holding.linked) throw AppException.badRequest("HOLDING_ALREADY_LINKED")
                holding.nativeCurrency = currency
            }
        }
        request.active?.let { holding.active = it }
        holding.updatedByUserId = by.id
        return toDto(holding)
    }

    @Transactional
    fun delete(householdId: UUID, id: UUID, by: User) {
        val holding = loadOwn(householdId, id)
        val now = Instant.now()
        lots.findAllByHoldingIdOrderByTradedOnAscCreatedAtAsc(holding.id).forEach {
            it.deletedAt = now
            it.updatedByUserId = by.id
        }
        holding.deletedAt = now
        holding.updatedByUserId = by.id
    }

    @Transactional
    fun addLot(householdId: UUID, holdingId: UUID, request: LotRequest, by: User, notify: Boolean = true): LotDto {
        val holding = loadOwn(householdId, holdingId)
        validateLot(request)
        val currency = normalizeCurrency(request.currency) ?: holding.nativeCurrency
        val previousEarliest = lots.findMinTradedOn(holding.id)
        val lot = HoldingLot(
            holdingId = holding.id,
            type = request.type,
            tradedOn = request.tradedOn,
            quantity = request.quantity,
            unitPrice = request.unitPrice,
            currency = currency,
            fee = request.fee,
            fxRateToBase = fxRateToBase(currency, request.tradedOn),
            note = request.note?.trim()?.takeIf { it.isNotEmpty() },
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        validateLedger(ledgerEntries(holding.id) + lot.toEntry())
        lots.save(lot)
        if (previousEarliest == null || request.tradedOn.isBefore(previousEarliest)) {
            // Head backfill runs async after commit; nightly jobs catch up on any failure.
            events.publishEvent(HoldingBackfillRequested(holding.id, request.tradedOn))
        }
        if (notify) notifyTrade(holding, lot, NotifyAction.CREATE, by)
        return toDto(lot)
    }

    @Transactional
    fun updateLot(householdId: UUID, holdingId: UUID, lotId: UUID, request: LotRequest, by: User): LotDto {
        val holding = loadOwn(householdId, holdingId)
        validateLot(request)
        val lot = lots.findByIdAndHoldingId(lotId, holding.id)
            ?: throw AppException.notFound("HOLDING_LOT_NOT_FOUND")
        val currency = normalizeCurrency(request.currency) ?: holding.nativeCurrency
        val previousEarliest = lots.findMinTradedOn(holding.id)
        if (currency != lot.currency || request.tradedOn != lot.tradedOn) {
            lot.fxRateToBase = fxRateToBase(currency, request.tradedOn)
        }
        lot.type = request.type
        lot.tradedOn = request.tradedOn
        lot.quantity = request.quantity
        lot.unitPrice = request.unitPrice
        lot.currency = currency
        lot.fee = request.fee
        lot.note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        lot.updatedByUserId = by.id
        validateLedger(ledgerEntries(holding.id, exclude = lot.id) + lot.toEntry())
        if (previousEarliest == null || request.tradedOn.isBefore(previousEarliest)) {
            // Editing a lot to an earlier date needs the head backfilled too.
            events.publishEvent(HoldingBackfillRequested(holding.id, request.tradedOn))
        }
        notifyTrade(holding, lot, NotifyAction.UPDATE, by)
        return toDto(lot)
    }

    @Transactional
    fun deleteLot(householdId: UUID, holdingId: UUID, lotId: UUID, by: User) {
        val holding = loadOwn(householdId, holdingId)
        val lot = lots.findByIdAndHoldingId(lotId, holding.id)
            ?: throw AppException.notFound("HOLDING_LOT_NOT_FOUND")
        // Deleting a BUY must not leave later SELLs uncovered.
        validateLedger(ledgerEntries(holding.id, exclude = lot.id))
        lot.deletedAt = Instant.now()
        lot.updatedByUserId = by.id
        notifyTrade(holding, lot, NotifyAction.DELETE, by)
    }

    /** Publishes a buy/sell notification (Telegram etc.); holding lifecycle stays silent. */
    private fun notifyTrade(holding: Holding, lot: HoldingLot, action: NotifyAction, by: User) {
        notifications.holdingTrade(
            householdId = holding.householdId,
            symbol = holding.symbol,
            typeName = lot.type.name,
            quantity = lot.quantity,
            unitPrice = lot.unitPrice,
            unitCurrency = lot.currency,
            tradedOn = lot.tradedOn,
            amountBase = lotAmountBase(lot),
            action = action,
            actor = NotifyActor.Human(by.email),
        )
    }

    private fun ledgerEntries(holdingId: UUID, exclude: UUID? = null): List<PortfolioValuationCalculator.LedgerEntry> =
        lots.findAllByHoldingIdOrderByTradedOnAscCreatedAtAsc(holdingId)
            .filter { it.id != exclude }
            .map { it.toEntry() }

    /** Rejects any mutation after which a SELL would exceed the quantity held on its date. */
    private fun validateLedger(entries: List<PortfolioValuationCalculator.LedgerEntry>) {
        try {
            PortfolioValuationCalculator.replay(entries, props.portfolio.costMethod, strict = true)
        } catch (ex: PortfolioValuationCalculator.OversellException) {
            throw AppException.badRequest("LOT_SELL_EXCEEDS_HOLDINGS", ex.tradedOn.toString())
        }
    }

    @Transactional(readOnly = true)
    fun exportCsv(householdId: UUID): String {
        val sb = StringBuilder()
        sb.append(
            Csv.row(
                "type", "asset_class", "symbol", "label", "native_currency", "isin",
                "provider", "provider_symbol",
                "traded_on", "quantity", "unit_price", "cost_currency", "fee", "note",
            )
        )
        for (holding in holdings.findAllByHouseholdIdOrderBySymbolAsc(householdId)) {
            for (lot in lots.findAllByHoldingIdOrderByTradedOnAscCreatedAtAsc(holding.id)) {
                sb.append(
                    Csv.row(
                        lot.type.name,
                        holding.assetClass.name,
                        holding.symbol,
                        holding.label,
                        holding.nativeCurrency,
                        holding.isin,
                        // Provider coordinates round-trip so re-import restores the price link.
                        holding.provider?.name,
                        holding.providerSymbol,
                        lot.tradedOn,
                        Csv.decimal(lot.quantity),
                        Csv.decimal(lot.unitPrice),
                        lot.currency,
                        lot.fee?.let { Csv.decimal(it) },
                        lot.note,
                    )
                )
            }
        }
        return sb.toString()
    }

    fun loadOwn(householdId: UUID, id: UUID): Holding =
        holdings.findByIdAndHouseholdId(id, householdId)
            ?: throw AppException.notFound("HOLDING_NOT_FOUND")

    /** FX from the lot currency into the household base, frozen at registration time. */
    fun fxRateToBase(currency: String, on: LocalDate): BigDecimal {
        if (currency == baseCurrency) return BigDecimal.ONE
        val stored = fxRates.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
            currency, baseCurrency, on,
        )
        if (stored != null) return stored.rate
        // First time this currency (or this old a date) shows up: fetch on demand before giving up.
        runCatching { priceRefresh.refreshFxCurrency(currency, LocalDate.now(), earliestNeeded = on) }
        return fxRates.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
            currency, baseCurrency, on,
        )?.rate ?: throw AppException.badRequest("LOT_FX_RATE_UNAVAILABLE", currency)
    }

    fun toDto(holding: Holding): HoldingDto {
        val lotEntities = lots.findAllByHoldingIdOrderByTradedOnAscCreatedAtAsc(holding.id)
        val entries = lotEntities.map { it.toEntry() }
        val state = PortfolioValuationCalculator.replay(entries, props.portfolio.costMethod)
        val breakdown = PortfolioValuationCalculator.breakdownByLot(entries, props.portfolio.costMethod)
        val sortedLotEntities = lotEntities.sortedWith(
            compareByDescending<HoldingLot> { it.tradedOn }.thenByDescending { it.createdAt }
        )
        return HoldingDto(
            id = holding.id,
            assetClass = holding.assetClass,
            symbol = holding.symbol,
            label = holding.label,
            nativeCurrency = holding.nativeCurrency,
            isin = holding.isin,
            provider = holding.provider,
            providerSymbol = holding.providerSymbol,
            linked = holding.linked,
            active = holding.active,
            lots = sortedLotEntities.map { toDto(it, breakdown[it.id]) },
            netQuantity = state.netQuantity,
            remainingCostBasis = state.remainingCostBasisBase,
            realizedPnl = state.realizedPnlBase,
            closed = state.netQuantity.signum() == 0 && lotEntities.isNotEmpty(),
            createdAt = holding.createdAt,
        )
    }

    private fun toDto(lot: HoldingLot, breakdown: PortfolioValuationCalculator.LotBreakdown? = null): LotDto = LotDto(
        id = lot.id,
        type = lot.type,
        tradedOn = lot.tradedOn,
        quantity = lot.quantity,
        unitPrice = lot.unitPrice,
        currency = lot.currency,
        fee = lot.fee,
        fxRateToBase = lot.fxRateToBase,
        note = lot.note,
        amountBase = lotAmountBase(lot),
        // BUY carries a remaining position; SELL only a realized figure. Unrealized is filled
        // in later by the valuation service, which has the current price.
        remainingQty = breakdown?.takeIf { lot.type == LotType.BUY }?.remainingQty,
        remainingCostBasis = breakdown?.takeIf { lot.type == LotType.BUY }?.remainingCostBasisBase,
        realizedPnl = breakdown?.realizedPnlBase,
    )

    /** Cost of a BUY / proceeds of a SELL in base currency, fee included. */
    private fun lotAmountBase(lot: HoldingLot): BigDecimal {
        val mc = MathContext.DECIMAL64
        val gross = lot.quantity.multiply(lot.unitPrice, mc)
        val withFee = when (lot.type) {
            LotType.BUY -> gross.add(lot.fee ?: BigDecimal.ZERO, mc)
            LotType.SELL -> gross.subtract(lot.fee ?: BigDecimal.ZERO, mc)
        }
        return Money.normalize(withFee.multiply(lot.fxRateToBase, mc))
    }

    private fun validateLot(request: LotRequest) {
        if (request.quantity.signum() <= 0) throw AppException.badRequest("LOT_QUANTITY_INVALID")
        if (request.unitPrice.signum() < 0) throw AppException.badRequest("LOT_UNIT_PRICE_INVALID")
        if (request.fee != null && request.fee.signum() < 0) throw AppException.badRequest("LOT_FEE_INVALID")
    }

    private fun normalizeSymbol(symbol: String): String {
        val normalized = symbol.trim().uppercase()
        if (normalized.isEmpty() || normalized.length > 32) throw AppException.badRequest("HOLDING_SYMBOL_INVALID")
        return normalized
    }

    private fun normalizeCurrency(currency: String?): String? =
        currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    private fun normalizeIsin(isin: String?): String? =
        isin?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
}

fun HoldingLot.toEntry(): PortfolioValuationCalculator.LedgerEntry =
    PortfolioValuationCalculator.LedgerEntry(type, tradedOn, quantity, unitPrice, fee, fxRateToBase, id)
