package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.bank.aisuggestion.IbanRedaction
import com.sephilabs.sharedledger.bank.rulegeneralisation.RULE_GENERALISATION_DENY_LIST
import com.sephilabs.sharedledger.bank.rulegeneralisation.denyKey
import java.text.Normalizer

/** Channel-first classifier deciding whether a counterparty name may leave the instance in the AI payload
 *  or must be anonymized to a generic category-based label. Local views always show the real name. */
object MerchantSanitizer {

    enum class Verdict { PASS, ANONYMIZE }

    enum class Reason {
        CARD_OR_TERMINAL_CHANNEL,
        DIRECT_DEBIT_CHANNEL,
        COMMERCIAL_INDICATOR,
        SINGLE_TOKEN_REPEAT_MERCHANT,
        MERCHANT_CATEGORY,
        LEADING_PARTICLE_BRAND,
        DEFAULT_MERCHANT,
        BLANK_OR_SHORT,
        P2P_CHANNEL,
        PII_TOKEN,
        HOUSEHOLD_MEMBER,
        PROCESSOR_ONLY,
        TRANSFER_CATEGORY,
        PERSON_NAME_FORMAT,
        CREDIT_TRANSFER_PERSON_CANDIDATE,
        UNVERIFIED_SINGLE_TOKEN,
        MANUAL_PERSON_CANDIDATE,
    }

    data class Classification(
        val verdict: Verdict,
        val reason: Reason,
        val sanitizedLabel: String?,
    ) {
        val allowedInPayload: Boolean get() = verdict == Verdict.PASS && !sanitizedLabel.isNullOrBlank()
    }

    private val NON_ASCII_MARKS = Regex("""\p{InCombiningDiacriticalMarks}+""")
    private val SPLIT_SEPARATOR = " – "

    // Payment-channel signals (checked BEFORE name shape; "sepa", "transfer", "transferencia" are deliberately NOT excluded).
    private val P2P_CHANNEL = Regex(
        """\b(bizum|tikkie|betaalverzoek|payment\s*request|solicitud\s*de\s*pago|revolut\s*p2p|verse|venmo|zelle|twint|wero|paypal\.me|paypal\s*friends|envio\s+a|pago\s+a\s+particular|devolucion\s+a)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CARD_OR_TERMINAL = Regex(
        """\b(betaalautomaat|pasvolgnr|pasnr|pinbetaling|bea|gea|apple\s*pay|google\s*pay|contactless|visa|mastercard|maestro|vpay|debit\s*card|credit\s*card|tarjeta|compra\s+tarjeta|pago\s+con\s+tarjeta|compra\s+en|tpv|ccv\*|bck\*|sumup|zettle|iz\*|adyen|mollie|stripe|paypal\s*\*)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DIRECT_DEBIT = Regex(
        """\b(incasso|sepa\s+incasso|sepa\s+core|sepa\s+b2b|machtiging|mandaat|mandate|mandato|mref|incassant|creditor\s*id|id\s*acreedor|adeudo|recibo|domiciliacion|direct\s*debit|lastschrift|prelevement|[a-z]{2}\d{2}(?:zzz|\d{3})[a-z0-9]{5,})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CREDIT_TRANSFER = Regex(
        """\b(overboeking|overschrijving|credit\s*transfer|sepa\s+ct|traspaso|bank\s*transfer|transferencia\s+(?:ordinaria|inmediata|puntual|emitida|a\s+favor)|dbit|crdt)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val EMAIL_TOKEN = Regex("""\S+@\S+\.\S+""")
    private val PHONE_TOKEN = Regex("""(?:\+\d{1,3}[\s-]?)?\b\d{9,12}\b""")
    private val HONORIFIC = Regex(
        """^(sr\.?|sra\.?|d\.|dna\.?|don|dona|dhr\.?|mevr\.?|hr\.?|mw\.?|mr\.?|mrs\.?|ms\.?|dr\.?)\s+""",
        RegexOption.IGNORE_CASE,
    )
    // Requires dotted initials ("J. Garcia", "M.A. van den Berg") or isolated single-letter tokens ("J Garcia"),
    // never matching ordinary 2-3 letter prefixes like "Kia", "Nat", or "NS".
    private val INITIALS_NAME = Regex(
        """^(?:(?:[a-z]\.\s*){1,3}|[a-z]\s+(?:[a-z]\s+)?)[a-z]{2,}""",
        RegexOption.IGNORE_CASE,
    )
    private val COMMA_INVERTED_NAME = Regex("""^[a-z\s]{2,},\s*[a-z\s]{2,}$""", RegexOption.IGNORE_CASE)
    private val COMMERCIAL_INDICATOR = Regex(
        """\b(b\.?v\.?|n\.?v\.?|s\.?l\.?u?\.?|s\.?a\.?|gmbh|ltd|llc|inc|plc|sas|srl|cooperatie|stichting|vereniging|gemeente|belastingdienst|gblt|waterschap|ayuntamiento|hacienda|seguridad\s*social|ministerio|diputacion|comunidad|universidad|hospital|clinica|farmacia|dentista|seguros|verzekering|zorg|energie|energy|water|telecom|bank|banco|hypotheek|mortgage|loterij|loteria|postcode|market|super|supermercado|auto|motor|mantenimiento|frenos|ruedas|service|services|solutions|systems|media|digital|tech|cloud|hosting|group|holding|direct|online|airlines|flights|asientos|parking|hotel|gym|fitness|club|shop|store|cafe|bar|restaurante|bakery|ropa|muebles|pla|cargador|filamento|anual|oriental|gasolina|stad|centr[oe]|eats|music|video|prime|plus|pro|premium|express|delivery|subscription|suscripcion|fibra|movil|internet|luz|gas|agua|taxi|metro|tren|cine|teatro)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val COMMERCIAL_PUNCT_OR_DIGIT = Regex("""[\d&*+#/.]""")
    private val TRANSFER_CATEGORY_HINT = Regex("""\b(transfer|traspaso|bizum|tikkie)\b""", RegexOption.IGNORE_CASE)
    private val WORD_TOKEN = Regex("""[a-z]+""")

    private val PROCESSOR_ONLY_KEYS: Set<String> = RULE_GENERALISATION_DENY_LIST.map(::denyKey).toSet()
    private val NOBILIARY_PARTICLES: Set<String> = setOf(
        "de", "del", "la", "las", "los", "y", "van", "der", "den", "ter", "ten", "te", "von", "zu", "di", "da", "le", "du",
    )
    private val INSTITUTIONAL_MERCHANT_CATEGORIES: Set<String> = setOf(
        "groceries.groceries",
        "home.utilities",
        "home.insurance_fees",
        "home.taxes",
        "home.mortgage",
        "outings.subscriptions",
        "outings.lottery",
        "transport.fuel",
        "transport.public",
        "transport.airfare",
        "financial.fees",
    )

    /** Extracts the raw local merchant/counterparty label (used for recurring detection and local display). */
    fun extractLocalLabel(counterparty: String?, txDescription: String?): String? {
        val fromCounterparty = counterparty?.trim()?.takeIf { it.isNotEmpty() }
        if (fromCounterparty != null) return fromCounterparty
        val desc = txDescription?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sep = desc.indexOf(SPLIT_SEPARATOR)
        return if (sep > 0) desc.substring(0, sep).trim().ifEmpty { desc } else desc
    }

    /** Classifies a raw local counterparty for outbound AI payload safety using channel signals first.
     *  Single-token plain transfers or unlinked entries never pass on token count alone: they require
     *  channel evidence, a commercial indicator, or meeting [SpendingReviewConstants.MIN_MERCHANT_PURCHASES]. */
    fun classifyForPayload(
        localLabel: String?,
        bankDescription: String?,
        categoryCode: String,
        memberTokens: Set<String> = emptySet(),
        occurrences: Int = 1,
    ): Classification {
        val raw = localLabel?.trim().orEmpty()
        if (raw.length < SpendingReviewConstants.MIN_MERCHANT_NAME_LENGTH) {
            return Classification(Verdict.ANONYMIZE, Reason.BLANK_OR_SHORT, null)
        }
        val redacted = IbanRedaction.redact(raw) ?: raw
        if (redacted.contains(IbanRedaction.PLACEHOLDER) || EMAIL_TOKEN.containsMatchIn(raw) || PHONE_TOKEN.containsMatchIn(raw)) {
            return Classification(Verdict.ANONYMIZE, Reason.PII_TOKEN, null)
        }

        val foldedLabel = fold(redacted)
        val combinedChannelText = fold(listOfNotNull(raw, bankDescription).joinToString(" "))

        if (TRANSFER_CATEGORY_HINT.containsMatchIn(categoryCode)) {
            return Classification(Verdict.ANONYMIZE, Reason.TRANSFER_CATEGORY, null)
        }
        if (memberTokens.isNotEmpty() && memberTokens.any { token -> token.length >= 3 && foldedLabel.contains(token) }) {
            return Classification(Verdict.ANONYMIZE, Reason.HOUSEHOLD_MEMBER, null)
        }
        if (P2P_CHANNEL.containsMatchIn(combinedChannelText)) {
            return Classification(Verdict.ANONYMIZE, Reason.P2P_CHANNEL, null)
        }
        if (denyKey(redacted) in PROCESSOR_ONLY_KEYS) {
            return Classification(Verdict.ANONYMIZE, Reason.PROCESSOR_ONLY, null)
        }

        // Channel-first merchant determination: card/terminal and direct debit are merchants by construction.
        if (CARD_OR_TERMINAL.containsMatchIn(combinedChannelText)) {
            return Classification(Verdict.PASS, Reason.CARD_OR_TERMINAL_CHANNEL, redacted)
        }
        if (DIRECT_DEBIT.containsMatchIn(combinedChannelText)) {
            return Classification(Verdict.PASS, Reason.DIRECT_DEBIT_CHANNEL, redacted)
        }

        val isCreditTransfer = CREDIT_TRANSFER.containsMatchIn(combinedChannelText)

        // Person-name format checks apply to credit transfers and unlabelled manual entries.
        if (HONORIFIC.containsMatchIn(foldedLabel) ||
            INITIALS_NAME.containsMatchIn(foldedLabel) ||
            COMMA_INVERTED_NAME.matches(foldedLabel)
        ) {
            return Classification(Verdict.ANONYMIZE, Reason.PERSON_NAME_FORMAT, null)
        }

        if (COMMERCIAL_INDICATOR.containsMatchIn(foldedLabel) || COMMERCIAL_PUNCT_OR_DIGIT.containsMatchIn(redacted)) {
            return Classification(Verdict.PASS, Reason.COMMERCIAL_INDICATOR, redacted)
        }

        if (isCreditTransfer) {
            return Classification(Verdict.ANONYMIZE, Reason.CREDIT_TRANSFER_PERSON_CANDIDATE, null)
        }

        val words = WORD_TOKEN.findAll(foldedLabel).map { it.value }.toList()
        if (words.size <= 1) {
            return if (occurrences >= SpendingReviewConstants.MIN_MERCHANT_PURCHASES) {
                Classification(Verdict.PASS, Reason.SINGLE_TOKEN_REPEAT_MERCHANT, redacted)
            } else {
                Classification(Verdict.ANONYMIZE, Reason.UNVERIFIED_SINGLE_TOKEN, null)
            }
        }

        if (categoryCode in INSTITUTIONAL_MERCHANT_CATEGORIES && occurrences >= SpendingReviewConstants.MIN_MERCHANT_PURCHASES) {
            return Classification(Verdict.PASS, Reason.MERCHANT_CATEGORY, redacted)
        }

        if (words.first() in NOBILIARY_PARTICLES) {
            return Classification(Verdict.PASS, Reason.LEADING_PARTICLE_BRAND, redacted)
        }

        // Conservative fallback for unlabelled 2-4 word alphabetic names in discretionary categories.
        if (words.size in 2..4) {
            return Classification(Verdict.ANONYMIZE, Reason.MANUAL_PERSON_CANDIDATE, null)
        }

        return Classification(Verdict.PASS, Reason.DEFAULT_MERCHANT, redacted)
    }

    fun anonymizedLabel(kind: String, categoryCode: String): String = "$kind in $categoryCode"

    fun fold(text: String): String =
        NON_ASCII_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "").lowercase().trim()
}
