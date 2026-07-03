package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.portfolio.price.CryptoPriceProvider
import com.sephilabs.sharedledger.portfolio.price.EquityPriceProvider
import com.sephilabs.sharedledger.portfolio.price.ProviderException
import com.sephilabs.sharedledger.portfolio.price.SymbolCandidate
import org.springframework.stereotype.Service

/** Routes assisted symbol search to the provider that prices the asset class. */
@Service
class SymbolSearchService(
    private val crypto: CryptoPriceProvider,
    private val equity: EquityPriceProvider,
) {

    fun search(assetClass: HoldingAssetClass, query: String): List<SymbolCandidate> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            when (assetClass) {
                HoldingAssetClass.crypto -> crypto.search(trimmed)
                HoldingAssetClass.etf, HoldingAssetClass.stock -> equity.search(trimmed)
                HoldingAssetClass.fund -> throw AppException.badRequest("PORTFOLIO_FUND_NOT_SEARCHABLE")
            }.take(MAX_CANDIDATES)
        } catch (ex: ProviderException) {
            throw AppException(
                code = "SYMBOL_SEARCH_PROVIDER_ERROR",
                httpStatus = 502,
                cause = ex,
            )
        }
    }

    companion object {
        const val MAX_CANDIDATES = 20
    }
}
