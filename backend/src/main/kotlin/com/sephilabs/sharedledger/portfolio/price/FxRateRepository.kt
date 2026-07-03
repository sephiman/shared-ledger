package com.sephilabs.sharedledger.portfolio.price

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface FxRateRepository : JpaRepository<FxRate, UUID> {

    fun findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
        baseCurrency: String,
        quoteCurrency: String,
        rateDate: LocalDate,
    ): FxRate?

    fun findAllByBaseCurrencyAndQuoteCurrencyAndRateDateBetweenOrderByRateDateAsc(
        baseCurrency: String,
        quoteCurrency: String,
        from: LocalDate,
        to: LocalDate,
    ): List<FxRate>

    fun findByProviderAndBaseCurrencyAndQuoteCurrencyAndRateDate(
        provider: String,
        baseCurrency: String,
        quoteCurrency: String,
        rateDate: LocalDate,
    ): FxRate?

    @Query(
        """
        SELECT MAX(r.rateDate) FROM FxRate r
        WHERE r.baseCurrency = :base AND r.quoteCurrency = :quote
        """
    )
    fun findMaxRateDate(@Param("base") base: String, @Param("quote") quote: String): LocalDate?

    @Query(
        """
        SELECT MIN(r.rateDate) FROM FxRate r
        WHERE r.baseCurrency = :base AND r.quoteCurrency = :quote
        """
    )
    fun findMinRateDate(@Param("base") base: String, @Param("quote") quote: String): LocalDate?
}
