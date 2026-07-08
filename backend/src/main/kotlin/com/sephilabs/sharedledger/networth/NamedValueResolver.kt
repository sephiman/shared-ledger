package com.sephilabs.sharedledger.networth

import com.sephilabs.sharedledger.networth.amortization.AmortizationScheduleService
import com.sephilabs.sharedledger.networth.asset.AssetValueEntryRepository
import com.sephilabs.sharedledger.networth.liability.LiabilityBalanceEntryRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Single "value at date" facade over the storage split behind named assets and liabilities.
 * Assets read their own dated series (latest entry on or before the date). A liability reads its
 * amortization schedule when amortizable, otherwise its manual balance series — callers (snapshots)
 * never learn how a value is produced.
 */
@Service
class NamedValueResolver(
    private val assetValues: AssetValueEntryRepository,
    private val liabilityBalances: LiabilityBalanceEntryRepository,
    private val amortization: AmortizationScheduleService,
) {
    /** The asset's value as of [date] from its series, or null if it has no entry on or before [date]. */
    fun assetValueAt(assetId: UUID, date: LocalDate): BigDecimal? =
        assetValues.findFirstByAssetIdAndValueDateLessThanEqualOrderByValueDateDescCreatedAtDesc(assetId, date)?.value

    /**
     * The liability's balance as of [date]: the schedule-computed balance for an amortizable
     * liability, else the latest manual series entry on or before [date] (null if none).
     */
    fun liabilityBalanceAt(liabilityId: UUID, date: LocalDate): BigDecimal? {
        amortization.balanceAt(liabilityId, date)?.let { return it }
        return liabilityBalances
            .findFirstByLiabilityIdAndBalanceDateLessThanEqualOrderByBalanceDateDescCreatedAtDesc(liabilityId, date)
            ?.balance
    }
}
