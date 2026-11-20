package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.recurring.Cadence
import com.sephilabs.sharedledger.recurring.RecurringTemplate
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class MerchantSanitizerAndRecurringTest {

    @Test
    fun `channel-first classifier passes card and direct debit merchants even with SEPA or Transfer words`() {
        val cases = listOf(
            Triple("Vattenfall", "SEPA Incasso Europese Machtiging MREF-12345", "home.utilities"),
            Triple("Allianz Direct", "SEPA Direct Debit Transfer Creditor ID NL98ZZZ12345", "home.insurance_fees"),
            Triple("Albert Heijn 8591", "Betaalautomaat Pasvolgnr 014 Apple Pay", "groceries.groceries"),
            Triple("Mercadona", "Compra tarjeta TPV 4588", "groceries.groceries"),
            Triple("Iberdrola", "Recibo domiciliación transferencia SEPA", "home.utilities"),
            Triple("Spotify", "CCV*Spotify AB", "outings.subscriptions"),
        )
        for ((label, bankDesc, cat) in cases) {
            val res = MerchantSanitizer.classifyForPayload(label, bankDesc, cat)
            assertThat(res.verdict).describedAs("$label / $bankDesc").isEqualTo(MerchantSanitizer.Verdict.PASS)
            assertThat(res.sanitizedLabel).isEqualTo(label)
        }
    }

    @Test
    fun `classifier anonymizes P2P channels, person credit transfers, IBANs, phones, emails, and household members`() {
        val excluded = listOf(
            Triple("Carlos Ruiz", "Bizum enviado a Carlos Ruiz", "outings.restaurants"),
            Triple("J. van den Berg", "Tikkie betaalverzoek diner", "outings.restaurants"),
            Triple("Maria Garcia Lopez", "Overboeking NL91ABNA0417164300", "home.rent"),
            Triple("GARCIA LOPEZ, JUAN", "Credit transfer rent", "home.rent"),
            Triple("M.A. de Vries", "Payment request drinks", "outings.restaurants"),
            Triple("Sr. Pedro Sanchez", "Pago a particular", "shopping.gifts"),
            Triple("ES9121000418450200051332", "Transfer", "home.other"),
            Triple("PayPal", "PayPal", "shopping.other"),
            Triple("juan@example.com", "Payment", "shopping.other"),
            Triple("+34612345678", "Payment", "shopping.other"),
            Triple("Visita Sole y Salva", null, "outings.hobbies"),
            Triple("Onni Nieminen", "Onni Nieminen-DBIT-5.35-e56hg", "shopping.other"),
        )
        for ((label, bankDesc, cat) in excluded) {
            val res = MerchantSanitizer.classifyForPayload(label, bankDesc, cat, setOf("juanjo"))
            assertThat(res.verdict).describedAs("$label / $bankDesc").isEqualTo(MerchantSanitizer.Verdict.ANONYMIZE)
            assertThat(res.allowedInPayload).isFalse()
        }

        // Household member token guard
        val selfRes = MerchantSanitizer.classifyForPayload("Juanjo Savings", "Overboeking", "home.other", setOf("juanjo"))
        assertThat(selfRes.verdict).isEqualTo(MerchantSanitizer.Verdict.ANONYMIZE)
        assertThat(selfRes.reason).isEqualTo(MerchantSanitizer.Reason.HOUSEHOLD_MEMBER)
    }

    @Test
    fun `real-shaped ingested bank counterparty fixture classifies every channel and rule deterministically`() {
        data class BankRow(
            val label: String,
            val bankDesc: String?,
            val category: String,
            val occurrences: Int,
            val expectedVerdict: MerchantSanitizer.Verdict,
            val expectedReason: MerchantSanitizer.Reason,
        )

        val fixture = listOf(
            // Card / terminal payments (NL & ES)
            BankRow("Albert Heijn 1234", "BEA, Apple Pay ALBERT HEIJN 1234 ALMERE pasnr. 014", "groceries.groceries", 1, MerchantSanitizer.Verdict.PASS, MerchantSanitizer.Reason.CARD_OR_TERMINAL_CHANNEL),
            BankRow("Lidl Nederland", "Betaalautomaat Pasvolgnr 004 Contactless", "groceries.groceries", 1, MerchantSanitizer.Verdict.PASS, MerchantSanitizer.Reason.CARD_OR_TERMINAL_CHANNEL),
            BankRow("MERCADONA S.A.", "COMPRA TARJETA 5412****8901 MERCADONA MADRID", "groceries.groceries", 1, MerchantSanitizer.Verdict.PASS, MerchantSanitizer.Reason.CARD_OR_TERMINAL_CHANNEL),
            BankRow("La Plaza", "PAGO CON TARJETA TPV RESTAURANTE LA PLAZA", "outings.restaurants", 1, MerchantSanitizer.Verdict.PASS, MerchantSanitizer.Reason.CARD_OR_TERMINAL_CHANNEL),
            // Direct debits with creditor id and mandate
            BankRow("Vattenfall Klantenservice N.V.", "SEPA Incasso algemeen doorlopend Incassant: NL89ZZZ341502410000 Mandaat: 001234567890", "home.utilities", 1, MerchantSanitizer.Verdict.PASS, MerchantSanitizer.Reason.DIRECT_DEBIT_CHANNEL),
            BankRow("IBERDROLA CLIENTES S.A.U.", "ADEUDO SEPA CORE RECIBO LUZ MANDATO ES45000B95758389", "home.utilities", 1, MerchantSanitizer.Verdict.PASS, MerchantSanitizer.Reason.DIRECT_DEBIT_CHANNEL),
            BankRow("Allianz Direct", "Direct Debit Creditor ID DE98ZZZ09999999999 Mandate MREF-2024-881", "home.insurance_fees", 1, MerchantSanitizer.Verdict.PASS, MerchantSanitizer.Reason.DIRECT_DEBIT_CHANNEL),
            BankRow("Florius", "SEPA Incasso hypotheek NL12ZZZ300012340000", "home.mortgage", 1, MerchantSanitizer.Verdict.PASS, MerchantSanitizer.Reason.DIRECT_DEBIT_CHANNEL),
            // Plain transfers to individuals vs commercial payees
            BankRow("Jan de Vries", "Overboeking NL91ABNA0417164300 etentje vrijdag", "outings.restaurants", 5, MerchantSanitizer.Verdict.ANONYMIZE, MerchantSanitizer.Reason.CREDIT_TRANSFER_PERSON_CANDIDATE),
            BankRow("Carlos", "Overboeking cadeau", "shopping.gifts", 12, MerchantSanitizer.Verdict.ANONYMIZE, MerchantSanitizer.Reason.CREDIT_TRANSFER_PERSON_CANDIDATE),
            BankRow("J.M. van den Berg", "SEPA Overboeking huur", "home.rent", 12, MerchantSanitizer.Verdict.ANONYMIZE, MerchantSanitizer.Reason.PERSON_NAME_FORMAT),
            BankRow("Maria Lopez Garcia", "Transferencia ordinaria cena cumpleanos", "outings.restaurants", 4, MerchantSanitizer.Verdict.ANONYMIZE, MerchantSanitizer.Reason.CREDIT_TRANSFER_PERSON_CANDIDATE),
            BankRow("Belastingdienst", "Overboeking motorrijtuigenbelasting", "home.taxes", 1, MerchantSanitizer.Verdict.PASS, MerchantSanitizer.Reason.COMMERCIAL_INDICATOR),
            // Tikkie / Bizum / P2P payment requests
            BankRow("Tikkie", "Tikkie ID 123456789 betaalverzoek lunch", "outings.restaurants", 6, MerchantSanitizer.Verdict.ANONYMIZE, MerchantSanitizer.Reason.P2P_CHANNEL),
            BankRow("ABN AMRO Bank N.V.", "Betaalverzoek Tikkie diner met vrienden", "outings.restaurants", 4, MerchantSanitizer.Verdict.ANONYMIZE, MerchantSanitizer.Reason.P2P_CHANNEL),
            BankRow("Carlos Perez", "BIZUM ENVIADO A CARLOS PEREZ", "outings.restaurants", 5, MerchantSanitizer.Verdict.ANONYMIZE, MerchantSanitizer.Reason.P2P_CHANNEL),
            BankRow("PayPal *Juan Garcia", "PayPal Friends dinner split", "outings.restaurants", 3, MerchantSanitizer.Verdict.ANONYMIZE, MerchantSanitizer.Reason.P2P_CHANNEL),
            // Payment processors: bare processor excluded, processor+merchant passes
            BankRow("PayPal", "Betaalautomaat PayPal", "shopping.other", 6, MerchantSanitizer.Verdict.ANONYMIZE, MerchantSanitizer.Reason.PROCESSOR_ONLY),
            BankRow("Adyen", "BEA Adyen", "shopping.other", 6, MerchantSanitizer.Verdict.ANONYMIZE, MerchantSanitizer.Reason.PROCESSOR_ONLY),
            BankRow("SumUp", "Compra tarjeta SumUp", "outings.restaurants", 4, MerchantSanitizer.Verdict.ANONYMIZE, MerchantSanitizer.Reason.PROCESSOR_ONLY),
            BankRow("PAYPAL *SPOTIFY", "Betaalautomaat PAYPAL *SPOTIFY", "outings.subscriptions", 1, MerchantSanitizer.Verdict.PASS, MerchantSanitizer.Reason.CARD_OR_TERMINAL_CHANNEL),
            BankRow("CCV*Bakkerij Bart", "BEA CCV*Bakkerij Bart", "groceries.groceries", 1, MerchantSanitizer.Verdict.PASS, MerchantSanitizer.Reason.CARD_OR_TERMINAL_CHANNEL),
            BankRow("SumUp *Cafe Central", "Compra tarjeta SumUp *Cafe Central", "outings.restaurants", 1, MerchantSanitizer.Verdict.PASS, MerchantSanitizer.Reason.CARD_OR_TERMINAL_CHANNEL),
        )

        for (row in fixture) {
            val res = MerchantSanitizer.classifyForPayload(row.label, row.bankDesc, row.category, occurrences = row.occurrences)
            assertThat(res.verdict).describedAs("verdict for ${row.label} | ${row.bankDesc}").isEqualTo(row.expectedVerdict)
            assertThat(res.reason).describedAs("reason for ${row.label} | ${row.bankDesc}").isEqualTo(row.expectedReason)
        }
    }

    @Test
    fun `single-token rule never passes plain transfers or unlinked low-count entries on its own`() {
        // Unlinked 1-2 purchases without commercial indicator -> ANONYMIZE
        val unlinkedOnce = MerchantSanitizer.classifyForPayload("Carlos", null, "outings.restaurants", occurrences = 1)
        assertThat(unlinkedOnce.verdict).isEqualTo(MerchantSanitizer.Verdict.ANONYMIZE)
        assertThat(unlinkedOnce.reason).isEqualTo(MerchantSanitizer.Reason.UNVERIFIED_SINGLE_TOKEN)

        val unlinkedTwice = MerchantSanitizer.classifyForPayload("Japon", null, "outings.travel", occurrences = 2)
        assertThat(unlinkedTwice.verdict).isEqualTo(MerchantSanitizer.Verdict.ANONYMIZE)
        assertThat(unlinkedTwice.reason).isEqualTo(MerchantSanitizer.Reason.UNVERIFIED_SINGLE_TOKEN)

        // Plain credit transfer single token even with >= 3 occurrences -> ANONYMIZE
        val transferRepeat = MerchantSanitizer.classifyForPayload("Carlos", "Overboeking huur", "home.rent", occurrences = 12)
        assertThat(transferRepeat.verdict).isEqualTo(MerchantSanitizer.Verdict.ANONYMIZE)
        assertThat(transferRepeat.reason).isEqualTo(MerchantSanitizer.Reason.CREDIT_TRANSFER_PERSON_CANDIDATE)

        // Unlinked meeting MIN_MERCHANT_PURCHASES (3) -> PASS
        val unlinkedRepeat = MerchantSanitizer.classifyForPayload("Vattenfall", null, "home.utilities", occurrences = 3)
        assertThat(unlinkedRepeat.verdict).isEqualTo(MerchantSanitizer.Verdict.PASS)
        assertThat(unlinkedRepeat.reason).isEqualTo(MerchantSanitizer.Reason.SINGLE_TOKEN_REPEAT_MERCHANT)
    }

    @Test
    fun `classifier passes real Dutch and Spanish supermarkets, utilities, insurers, taxes and subscriptions with repeat evidence`() {
        val merchants = listOf(
            "AH" to "groceries.groceries",
            "Lidl" to "groceries.groceries",
            "Vomar" to "groceries.groceries",
            "Vattenfall" to "home.utilities",
            "Odido" to "home.utilities",
            "Vitens" to "home.utilities",
            "Greenchoice" to "home.utilities",
            "Florius" to "home.mortgage",
            "Loonzorg" to "home.insurance_fees",
            "Allianz" to "home.insurance_fees",
            "Reaal" to "home.insurance_fees",
            "Van Mossel" to "home.insurance_fees",
            "Gemeente Almere" to "home.taxes",
            "Belastingdienst" to "home.taxes",
            "GBLT" to "home.taxes",
            "Amazon" to "home.services",
            "HBO" to "home.services",
            "Feenstra" to "home.services",
            "Vriendenloterij" to "outings.lottery",
            "Nat Postcode" to "outings.lottery",
            "Postcode Loterij" to "outings.lottery",
            "Kia mantenimiento" to "transport.car_maintenance",
            "NS Noe" to "transport.public",
        )
        for ((label, cat) in merchants) {
            val res = MerchantSanitizer.classifyForPayload(label, null, cat, occurrences = 3)
            assertThat(res.verdict).describedAs("$label ($cat)").isEqualTo(MerchantSanitizer.Verdict.PASS)
        }
    }

    @Test
    fun `recurring detector identifies weekly, monthly with YoY price hike, quarterly, yearly, and merges templates without duplicates`() {
        val hid = UUID.randomUUID()
        val uid = UUID.randomUUID()
        val tplId = UUID.randomUUID()
        val template = RecurringTemplate(
            id = tplId,
            householdId = hid,
            direction = Direction.expense,
            categoryCode = "home.mortgage",
            amount = BigDecimal("1200.00"),
            description = "Florius",
            cadence = Cadence.monthly,
            dayOfMonth = 28,
            startDate = LocalDate.of(2024, 1, 28),
            createdByUserId = uid,
            updatedByUserId = uid,
        )

        val history = mutableListOf<ExpenseRecord>()
        // Template-generated rows (must not be duplicated by detector)
        for (m in 0L until 18L) {
            history += rec(LocalDate.of(2024, 12, 28).plusMonths(m), "home.mortgage", "home", true, "1200.00", "Florius", tplId)
        }
        // Monthly subscription with a step-change YoY price hike (€4.99 -> €6.99)
        for (m in 0L until 12L) {
            history += rec(LocalDate.of(2024, 6, 10).plusMonths(m), "outings.subscriptions", "outings", false, "4.99", "HBO")
        }
        for (m in 0L until 12L) {
            history += rec(LocalDate.of(2025, 6, 10).plusMonths(m), "outings.subscriptions", "outings", false, "6.99", "HBO")
        }
        // Weekly charge (6 occurrences, 7 days apart)
        for (w in 0L until 6L) {
            history += rec(LocalDate.of(2026, 4, 1).plusWeeks(w), "outings.hobbies", "outings", false, "15.00", "PadelClub")
        }
        // Quarterly charge (4 occurrences, 91 days apart)
        for (q in 0L until 4L) {
            history += rec(LocalDate.of(2025, 6, 1).plusDays(q * 91), "home.utilities", "home", true, "90.00", "WaterBoard")
        }
        // Yearly charge (2 occurrences, 365 days apart, with YoY hike 100 -> 110)
        history += rec(LocalDate.of(2025, 5, 15), "home.insurance_fees", "home", true, "100.00", "AnnualIns")
        history += rec(LocalDate.of(2026, 5, 15), "home.insurance_fees", "home", true, "110.00", "AnnualIns")
        // Irregular grocery spend (must NOT be detected as recurring)
        val erraticAmounts = listOf("12.40", "88.10", "5.50", "140.00", "21.30", "73.20")
        erraticAmounts.forEachIndexed { i, amt ->
            history += rec(LocalDate.of(2025, 12, 5).plusMonths(i.toLong()), "groceries.groceries", "groceries", true, amt, "AH")
        }

        val catMeta = mapOf(
            "home.mortgage" to ("home" to true),
            "outings.subscriptions" to ("outings" to false),
            "outings.hobbies" to ("outings" to false),
            "home.utilities" to ("home" to true),
            "home.insurance_fees" to ("home" to true),
            "groceries.groceries" to ("groceries" to true),
        )

        val detected = RecurringChargeDetector.detect(history, listOf(template), catMeta, LocalDate.of(2025, 6, 1))
        val byName = detected.associateBy { it.merchant }

        assertThat(byName.keys).containsExactlyInAnyOrder("Florius", "HBO", "PadelClub", "WaterBoard", "AnnualIns")
        assertThat(byName["Florius"]!!.source).isEqualTo(RecurringSource.template)

        val hbo = byName["HBO"]!!
        assertThat(hbo.cadence).isEqualTo(DetectedCadence.monthly)
        assertThat(hbo.monthlyCost).isEqualByComparingTo("6.99")
        assertThat(hbo.annualCost).isEqualByComparingTo("83.88")
        assertThat(hbo.priorYearAmount).isEqualByComparingTo("4.99")
        assertThat(hbo.yoyDeltaAbs).isEqualByComparingTo("2.00")
        assertThat(hbo.yoyDeltaPct!!).isGreaterThan(39.0)

        assertThat(byName["PadelClub"]!!.cadence).isEqualTo(DetectedCadence.weekly)
        assertThat(byName["PadelClub"]!!.annualCost).isEqualByComparingTo("780.00")

        assertThat(byName["WaterBoard"]!!.cadence).isEqualTo(DetectedCadence.quarterly)
        assertThat(byName["WaterBoard"]!!.annualCost).isEqualByComparingTo("360.00")
        assertThat(byName["WaterBoard"]!!.monthlyCost).isEqualByComparingTo("30.00")

        assertThat(byName["AnnualIns"]!!.cadence).isEqualTo(DetectedCadence.yearly)
        assertThat(byName["AnnualIns"]!!.yoyDeltaAbs).isEqualByComparingTo("10.00")
        assertThat(byName["AnnualIns"]!!.yoyChangeKind).isEqualTo(YoyChangeKind.price_increase)
    }

    @Test
    fun `recurring detector merges payee variants, rejects tiny or mismatched yearly pairs, and distinguishes price_increase from changed`() {
        val history = mutableListOf<ExpenseRecord>()

        // 1. Shared distinctive token + case/punctuation variants -> ONE lottery subscription
        val lotteryLabels = listOf(
            "Nat Postcode", "Nat postcode", "National Postcode",
            "Nat Postcode Loterij", "Nat postcode loterij", "Postcode-Loterij",
        )
        for (m in 0L until 18L) {
            val d = LocalDate.of(2024, 12, 21).plusMonths(m)
            val amt = if (m < 12L) "20.50" else "21.00"
            val label = lotteryLabels[(m % lotteryLabels.size).toInt()]
            history += rec(d, "outings.lottery", "outings", false, amt, label)
        }

        // 2. Edit-distance typo variant ("Loonzorg" + "Loonzonrg") with 2 charges/month -> ONE monthly charge, stable count -> price_increase
        for (m in 0L until 18L) {
            val base = LocalDate.of(2024, 12, 4).plusMonths(m)
            val amt = if (m < 12L) "178.45" else "184.80"
            val label = if (m == 17L) "Loonzonrg" else "Loonzorg"
            history += rec(base, "home.insurance_fees", "home", true, amt, label)
            history += rec(base.plusDays(1), "home.insurance_fees", "home", true, amt, label)
        }

        // 3. Multi-charge payee with constant monthly total ("Reaal": 6.50 + 7.54 = 14.04 every month) -> 14.04/mo, 0% YoY
        for (m in 0L until 18L) {
            val base = LocalDate.of(2024, 12, 5).plusMonths(m)
            history += rec(base, "home.insurance_fees", "home", true, "6.50", "Reaal")
            history += rec(base.plusDays(1), "home.insurance_fees", "home", true, "7.54", "Reaal")
        }

        // 4. Charge-count change ("ING": 2x 2.50 = 5.00 last year -> 1x 5.20 this year) -> yoyChangeKind == changed (NOT price_increase!)
        for (m in 0L until 14L) {
            val base = LocalDate.of(2024, 12, 10).plusMonths(m)
            history += rec(base, "home.insurance_fees", "home", true, "2.50", "ING")
            history += rec(base.plusDays(1), "home.insurance_fees", "home", true, "2.50", "ING")
        }
        for (m in 14L until 18L) {
            val base = LocalDate.of(2024, 12, 10).plusMonths(m)
            history += rec(base, "home.insurance_fees", "home", true, "5.20", "ING")
        }

        // 5. Tiny yearly parking (4.00 & 4.50 -> below RECURRING_MIN_ANNUAL_COST and RECURRING_YEARLY_MIN_AMOUNT) -> REJECTED
        history += rec(LocalDate.of(2025, 3, 13), "transport.parking", "transport", false, "4.00", "Batavia Stad")
        history += rec(LocalDate.of(2026, 3, 9), "transport.parking", "transport", false, "4.50", "Batavia Stad")

        // 6. Two unrelated yearly purchases with >15% mismatch (12.00 vs 14.99, +24.9%) -> REJECTED
        history += rec(LocalDate.of(2024, 11, 13), "home.services", "home", false, "12.00", "AH")
        history += rec(LocalDate.of(2025, 11, 14), "home.services", "home", false, "14.99", "AH")

        // 7. Two larger unrelated yearly purchases with big amount mismatch (40.00 vs 65.00) -> REJECTED
        history += rec(LocalDate.of(2025, 4, 1), "shopping.other", "shopping", false, "40.00", "RandomShop")
        history += rec(LocalDate.of(2026, 4, 2), "shopping.other", "shopping", false, "65.00", "RandomShop")

        val catMeta = mapOf(
            "outings.lottery" to ("outings" to false),
            "home.insurance_fees" to ("home" to true),
            "transport.parking" to ("transport" to false),
            "home.services" to ("home" to false),
            "shopping.other" to ("shopping" to false),
        )

        val detected = RecurringChargeDetector.detect(history, emptyList(), catMeta, LocalDate.of(2025, 6, 1))
        val byName = detected.associateBy { it.merchant }

        assertThat(byName.keys).doesNotContain("Batavia Stad", "AH", "RandomShop", "Loonzonrg")
        assertThat(detected.filter { it.categoryCode == "outings.lottery" }).hasSize(1)
        val lottery = detected.first { it.categoryCode == "outings.lottery" }
        assertThat(lottery.occurrences).isEqualTo(18)
        assertThat(lottery.searchQuery).isEqualTo("Postcode")
        assertThat(lottery.yoyChangeKind).isEqualTo(YoyChangeKind.price_increase)
        assertThat(lottery.mergedVariants).containsExactlyInAnyOrder(
            "Nat Postcode",
            "National Postcode",
            "Nat Postcode Loterij",
            "Postcode-Loterij",
        )

        val loonzorg = byName["Loonzorg"]!!
        assertThat(loonzorg.occurrences).isEqualTo(36)
        assertThat(loonzorg.monthlyCost).isEqualByComparingTo("369.60")
        assertThat(loonzorg.priorYearAmount).isEqualByComparingTo("356.90")
        assertThat(loonzorg.yoyDeltaAbs).isEqualByComparingTo("12.70")
        assertThat(loonzorg.yoyChangeKind).isEqualTo(YoyChangeKind.price_increase)
        assertThat(loonzorg.mergedVariants).containsExactlyInAnyOrder("Loonzorg", "Loonzonrg")

        val reaal = byName["Reaal"]!!
        assertThat(reaal.monthlyCost).isEqualByComparingTo("14.04")
        assertThat(reaal.priorYearAmount).isEqualByComparingTo("14.04")
        assertThat(reaal.yoyDeltaAbs).isEqualByComparingTo("0.00")
        assertThat(reaal.yoyChangeKind).isNull()
        assertThat(reaal.mergedVariants).isEmpty()

        val ing = byName["ING"]!!
        assertThat(ing.monthlyCost).isEqualByComparingTo("5.20")
        assertThat(ing.priorYearAmount).isEqualByComparingTo("5.00")
        assertThat(ing.yoyDeltaAbs).isEqualByComparingTo("0.20")
        assertThat(ing.yoyChangeKind).isEqualTo(YoyChangeKind.changed)
        assertThat(ing.action).isEqualTo(RecurringAction.negotiable)
        assertThat(lottery.action).isEqualTo(RecurringAction.cancellable)
    }

    @Test
    fun `actionability mapping classifies cancellable, negotiable, and none categories deterministically`() {
        // Built-in named constant map
        assertThat(SpendingReviewConstants.RECURRING_ACTION_BY_CATEGORY["outings.subscriptions"]).isEqualTo(RecurringAction.cancellable)
        assertThat(SpendingReviewConstants.RECURRING_ACTION_BY_CATEGORY["outings.lottery"]).isEqualTo(RecurringAction.cancellable)
        assertThat(SpendingReviewConstants.RECURRING_ACTION_BY_CATEGORY["home.services"]).isEqualTo(RecurringAction.cancellable)
        assertThat(SpendingReviewConstants.RECURRING_ACTION_BY_CATEGORY["home.utilities"]).isEqualTo(RecurringAction.negotiable)
        assertThat(SpendingReviewConstants.RECURRING_ACTION_BY_CATEGORY["home.insurance_fees"]).isEqualTo(RecurringAction.negotiable)
        assertThat(SpendingReviewConstants.RECURRING_ACTION_BY_CATEGORY["home.mortgage"]).isEqualTo(RecurringAction.negotiable)
        assertThat(SpendingReviewConstants.RECURRING_ACTION_BY_CATEGORY["financial.fees"]).isEqualTo(RecurringAction.negotiable)
        assertThat(SpendingReviewConstants.RECURRING_ACTION_BY_CATEGORY["home.taxes"]).isEqualTo(RecurringAction.none)
        assertThat(SpendingReviewConstants.RECURRING_ACTION_BY_CATEGORY["home.rent"]).isEqualTo(RecurringAction.none)

        // Custom category fallback rules
        assertThat(SpendingReviewConstants.resolveRecurringAction("outings.gym_membership", essential = false)).isEqualTo(RecurringAction.cancellable)
        assertThat(SpendingReviewConstants.resolveRecurringAction("home.telecom_fiber", essential = true)).isEqualTo(RecurringAction.negotiable)
        assertThat(SpendingReviewConstants.resolveRecurringAction("transport.traffic_fines", essential = true)).isEqualTo(RecurringAction.none)
        assertThat(SpendingReviewConstants.resolveRecurringAction("financial.loan_principal", essential = true)).isEqualTo(RecurringAction.none)
        assertThat(SpendingReviewConstants.resolveRecurringAction("home.municipal_tax", essential = false)).isEqualTo(RecurringAction.none)
    }

    @Test
    fun `mover merchant breakdown computes sanitized period drivers, anonymizes persons and rare payees, and aggregates long tail`() {
        val records = mutableListOf<ExpenseRecord>()
        // AH has >= 3 purchases in 12m (passes), Carlos Bizum is P2P (anonymized), OneOffVenue has 1 purchase in 12m (anonymized),
        // plus one blank/unlabelled entry and 2 small tail purchases.
        for (m in 0L..2L) {
            records += rec(LocalDate.of(2026, 2, 10).plusMonths(m), "outings.restaurants", "outings", false, "50.00", "AH")
        }
        // Period = May 2026
        records += rec(LocalDate.of(2026, 5, 5), "outings.restaurants", "outings", false, "100.00", "AH")
        records += rec(LocalDate.of(2026, 5, 12), "outings.restaurants", "outings", false, "100.00", "AH")
        records += rec(LocalDate.of(2026, 5, 18), "outings.restaurants", "outings", false, "400.00", "Carlos Bizum")
        records += rec(LocalDate.of(2026, 5, 22), "outings.restaurants", "outings", false, "300.00", "OneOffVenue")
        records += ExpenseRecord(
            id = UUID.randomUUID(),
            occurrenceDate = LocalDate.of(2026, 5, 25),
            categoryCode = "outings.restaurants",
            groupCode = "outings",
            essential = false,
            amount = BigDecimal("60.00"),
            rawLabel = null,
            bankDescription = null,
            recurringTemplateId = null,
        )
        records += rec(LocalDate.of(2026, 5, 28), "outings.restaurants", "outings", false, "40.00", "SmallCafe")

        val map = MoverMerchantsBuilder.build(
            nonRefund12m = records,
            periodStart = LocalDate.of(2026, 5, 1),
            periodEnd = LocalDate.of(2026, 5, 31),
            increaseCategoryCodes = setOf("outings.restaurants"),
            recurringKeysByCategory = emptyMap(),
            memberTokens = setOf("juanjo"),
        )
        val bd = map["outings.restaurants"]!!
        assertThat(bd.periodTotal).isEqualByComparingTo("1000.00")
        assertThat(bd.periodPurchases).isEqualTo(6)
        assertThat(bd.topMerchants).hasSize(3)
        // #1: Carlos Bizum -> anonymized (40%)
        assertThat(bd.topMerchants[0].anonymized).isTrue()
        assertThat(bd.topMerchants[0].merchant).isEqualTo("Counterparty in outings.restaurants")
        assertThat(bd.topMerchants[0].shareOfCategoryPct).isEqualTo(40.0)
        // #2: OneOffVenue -> anonymized because < 3 purchases in 12m (30%)
        assertThat(bd.topMerchants[1].anonymized).isTrue()
        assertThat(bd.topMerchants[1].merchant).isEqualTo("Counterparty in outings.restaurants")
        assertThat(bd.topMerchants[1].shareOfCategoryPct).isEqualTo(30.0)
        // #3: AH -> passes because >= 3 purchases in 12m (20%, 2 period purchases)
        assertThat(bd.topMerchants[2].anonymized).isFalse()
        assertThat(bd.topMerchants[2].merchant).isEqualTo("AH")
        assertThat(bd.topMerchants[2].purchases).isEqualTo(2)
        assertThat(bd.topMerchants[2].shareOfCategoryPct).isEqualTo(20.0)
        // Top 3 combined = 900.00 (90.0%, 4 purchases)
        assertThat(bd.topMerchantsSummary!!.shareOfCategoryPct).isEqualTo(90.0)
        assertThat(bd.topMerchantsSummary.purchases).isEqualTo(4)
        // Long tail (60 + 40 = 100, 2 purchases, 2 merchants)
        assertThat(bd.otherMerchants!!.total).isEqualByComparingTo("100.00")
        assertThat(bd.otherMerchants.purchases).isEqualTo(2)
    }

    @Test
    fun `auto-materialized templates in unimported months do not hide active non-template recurring charges`() {
        val hid = UUID.randomUUID()
        val uid = UUID.randomUUID()
        val tplId = UUID.randomUUID()
        val tpl = RecurringTemplate(
            id = tplId,
            householdId = hid,
            direction = Direction.expense,
            categoryCode = "transport.car_maintenance",
            amount = BigDecimal("100.00"),
            description = "StandingCarTpl",
            cadence = Cadence.monthly,
            dayOfMonth = 15,
            startDate = LocalDate.of(2026, 6, 15),
            createdByUserId = uid,
            updatedByUserId = uid,
        )
        val history = mutableListOf<ExpenseRecord>()
        // Real active bill up to May 2026
        for (m in 0L until 12L) {
            history += rec(LocalDate.of(2025, 6, 10).plusMonths(m), "outings.lottery", "outings", false, "21.00", "Nat Postcode")
        }
        // Cancelled bill stopped July 2025 (10 months before May 2026)
        for (m in 0L until 6L) {
            history += rec(LocalDate.of(2025, 2, 10).plusMonths(m), "home.services", "home", false, "6.99", "OldSub")
        }
        // Auto-materialized template rows June..October 2026 (no manual/bank imports since May)
        for (m in 0L until 5L) {
            history += rec(LocalDate.of(2026, 6, 15).plusMonths(m), "transport.car_maintenance", "transport", true, "100.00", "StandingCarTpl", tplId)
        }
        val catMeta = mapOf(
            "outings.lottery" to ("outings" to false),
            "home.services" to ("home" to false),
            "transport.car_maintenance" to ("transport" to true),
        )
        val detected = RecurringChargeDetector.detect(history, listOf(tpl), catMeta, LocalDate.of(2025, 11, 1))
        val names = detected.map { it.merchant }
        assertThat(names).contains("Nat Postcode", "StandingCarTpl")
        assertThat(names).doesNotContain("OldSub")
    }

    private fun rec(
        date: LocalDate,
        cat: String,
        group: String,
        essential: Boolean,
        amount: String,
        label: String,
        templateId: UUID? = null,
    ) = ExpenseRecord(
        id = UUID.randomUUID(),
        occurrenceDate = date,
        categoryCode = cat,
        groupCode = group,
        essential = essential,
        amount = BigDecimal(amount),
        rawLabel = label,
        bankDescription = null,
        recurringTemplateId = templateId,
    )
}
