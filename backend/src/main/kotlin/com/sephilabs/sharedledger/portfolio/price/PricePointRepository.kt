package com.sephilabs.sharedledger.portfolio.price

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface PricePointRepository : JpaRepository<PricePoint, UUID> {

    fun findFirstByProviderAndProviderSymbolAndCurrencyAndPriceDateLessThanEqualOrderByPriceDateDesc(
        provider: String,
        providerSymbol: String,
        currency: String,
        priceDate: LocalDate,
    ): PricePoint?

    fun findByProviderAndProviderSymbolAndCurrencyAndPriceDate(
        provider: String,
        providerSymbol: String,
        currency: String,
        priceDate: LocalDate,
    ): PricePoint?

    fun findAllByProviderAndProviderSymbolAndCurrencyAndPriceDateBetweenOrderByPriceDateAsc(
        provider: String,
        providerSymbol: String,
        currency: String,
        from: LocalDate,
        to: LocalDate,
    ): List<PricePoint>

    @Query(
        """
        SELECT MAX(p.priceDate) FROM PricePoint p
        WHERE p.provider = :provider AND p.providerSymbol = :providerSymbol AND p.currency = :currency
        """
    )
    fun findMaxPriceDate(
        @Param("provider") provider: String,
        @Param("providerSymbol") providerSymbol: String,
        @Param("currency") currency: String,
    ): LocalDate?

    @Query(
        """
        SELECT MIN(p.priceDate) FROM PricePoint p
        WHERE p.provider = :provider AND p.providerSymbol = :providerSymbol AND p.currency = :currency
        """
    )
    fun findMinPriceDate(
        @Param("provider") provider: String,
        @Param("providerSymbol") providerSymbol: String,
        @Param("currency") currency: String,
    ): LocalDate?
}
