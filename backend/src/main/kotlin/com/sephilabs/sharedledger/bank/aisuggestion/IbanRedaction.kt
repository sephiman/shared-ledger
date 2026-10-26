package com.sephilabs.sharedledger.bank.aisuggestion

/** Every IBAN-shaped token — compact or in groups of four — becomes `[IBAN]` before text leaves the system.
 *  Accounts only keep their last four digits, so this cannot single out the household's own IBANs; it removes
 *  them all. Over-matching an IBAN-like reference (a SEPA creditor id) is harmless. */
object IbanRedaction {

    const val PLACEHOLDER = "[IBAN]"

    private val iban = Regex("""\b[A-Z]{2}[0-9]{2}(?:[A-Z0-9]{11,30}|(?: [A-Z0-9]{4}){2,7}(?: [A-Z0-9]{1,4})?)\b""")

    fun redact(text: String?): String? = text?.replace(iban, PLACEHOLDER)
}
