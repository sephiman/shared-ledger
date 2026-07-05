package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.portfolio.price.FxRateRepository
import com.sephilabs.sharedledger.portfolio.price.PriceRefreshService
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

/**
 * Converts a non-EUR bank movement into the household base currency (EUR) at the ECB rate of the
 * booking date (closed decision #5). Reuses the portfolio FX store + on-demand fetch exactly like
 * `HoldingService.fxRateToBase`, so bank ingestion needs no FX plumbing of its own. The original
 * amount/currency are preserved on the pending movement; only the converted value drives the
 * generated transaction.
 */
@Component
class BankFxConverter(
    private val fxRates: FxRateRepository,
    private val priceRefresh: PriceRefreshService,
    private val props: AppProperties,
) {
    private val baseCurrency: String get() = props.portfolio.baseCurrency

    fun toBase(amount: BigDecimal, currency: String, on: LocalDate): BigDecimal {
        if (currency.equals(baseCurrency, ignoreCase = true)) return Money.normalize(amount)
        val rate = storedRate(currency, on)
            ?: run {
                runCatching { priceRefresh.refreshFxCurrency(currency.uppercase(), LocalDate.now(), earliestNeeded = on) }
                storedRate(currency, on)
            }
            ?: throw AppException.badRequest("BANK_FX_RATE_UNAVAILABLE", currency)
        return Money.normalize(amount.multiply(rate, MathContext.DECIMAL64))
    }

    private fun storedRate(currency: String, on: LocalDate): BigDecimal? =
        fxRates.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
            currency.uppercase(), baseCurrency, on,
        )?.rate
}
