package com.sephilabs.sharedledger.fire

import java.math.BigDecimal
import java.time.LocalDate

/** A complete settings request with plain defaults; tests override only what they are about. */
fun fireSettingsRequest(
    swrPct: String = "4.00",
    monthlyContribution: String = "1500.00",
    scenarios: List<ReturnScenarioInput> = listOf(ReturnScenarioInput(BigDecimal("6.0"), BigDecimal("12.0"), false, "Base")),
    referenceTier: FireTierKey = FireTierKey.fire,
    targetAmount: String = "0.00",
    tierCustomEnabled: Boolean = false,
    totalSpendingMode: SpendingBaseMode = SpendingBaseMode.derived,
    manualTotalSpending: String = "0.00",
    cashFlowEvents: List<FireCashFlowEventInput> = emptyList(),
    targetYear: Short = (LocalDate.now().year + 20).toShort(),
) = FireSettingsRequest(
    targetAmount = BigDecimal(targetAmount),
    targetYear = targetYear,
    monthlyContribution = BigDecimal(monthlyContribution),
    returnScenarios = scenarios,
    qualifyingAssetClasses = listOf("etfs"),
    expectedInflationPct = BigDecimal("2.0"),
    safeWithdrawalRatePct = BigDecimal(swrPct),
    fatMultiplier = BigDecimal("1.5"),
    contributionMode = ContributionMode.manual,
    indexContribution = true,
    essentialSpendingMode = SpendingBaseMode.derived,
    manualEssentialSpending = BigDecimal.ZERO,
    totalSpendingMode = totalSpendingMode,
    manualTotalSpending = BigDecimal(manualTotalSpending),
    tierLeanEnabled = true,
    tierFireEnabled = true,
    tierFatEnabled = true,
    tierCustomEnabled = tierCustomEnabled,
    applyCapitalGainsTax = false,
    indexTaxBrackets = false,
    drawdownYears = 30,
    fallbackGainFractionPct = BigDecimal("50.0"),
    taxBrackets = FireDefaults.SPANISH_SAVINGS_TAX_BRACKETS.map { TaxBracketInput(it.lowerBound, it.ratePct) },
    cashFlowEvents = cashFlowEvents,
    referenceTier = referenceTier,
    referenceScenarioIndex = 0,
)
