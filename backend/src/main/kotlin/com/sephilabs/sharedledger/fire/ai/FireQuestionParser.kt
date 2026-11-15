package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.bank.aisuggestion.JsonObjectReader
import com.sephilabs.sharedledger.fire.CashFlowEventKind
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

sealed interface ParsedFireQuestionAnswer {
    data class Read(
        val monthlyContribution: FireDecimalChange?,
        val monthlySpending: FireDecimalChange?,
        val targetYear: FireYearChange?,
        val safeWithdrawalRatePct: FireDecimalChange?,
        val lumpSum: BigDecimal?,
        val returnShiftPct: BigDecimal?,
        val events: List<FireParsedEvent>,
        val unsupported: List<FireQuestionUnsupported>,
        val confidence: Double,
    ) : ParsedFireQuestionAnswer

    data class Unreadable(val reason: FireQuestionRejection) : ParsedFireQuestionAnswer
}

object FireQuestionParser {

    private class Refused(val reason: FireQuestionRejection) : RuntimeException(null, null, false, false)

    fun parse(raw: String?): ParsedFireQuestionAnswer {
        val root = JsonObjectReader.first(raw) ?: return ParsedFireQuestionAnswer.Unreadable(FireQuestionRejection.unreadable)
        return try {
            read(root)
        } catch (refused: Refused) {
            ParsedFireQuestionAnswer.Unreadable(refused.reason)
        }
    }

    private fun read(root: JsonNode): ParsedFireQuestionAnswer.Read {
        val confidence = root.path("confidence").takeIf { it.isNumber }?.asDouble()?.takeIf { it in 0.0..1.0 }
            ?: malformed()
        val unsupported = unsupported(root.path("unsupported"))
        val whatIf = root.path("whatIf")
        if (whatIf.isMissingNode || whatIf.isNull) {
            return ParsedFireQuestionAnswer.Read(null, null, null, null, null, null, emptyList(), unsupported, confidence)
        }
        if (!whatIf.isObject) malformed()

        return ParsedFireQuestionAnswer.Read(
            monthlyContribution = decimalChange(whatIf.path("monthlyContribution")),
            monthlySpending = decimalChange(whatIf.path("monthlySpending")),
            targetYear = yearChange(whatIf.path("targetYear")),
            safeWithdrawalRatePct = decimalChange(whatIf.path("safeWithdrawalRatePct")),
            lumpSum = optionalDecimal(whatIf.path("lumpSum")),
            returnShiftPct = optionalDecimal(whatIf.path("returnShiftPct")),
            events = events(whatIf.path("cashFlowEvents")),
            unsupported = unsupported,
            confidence = confidence,
        )
    }

    private fun decimalChange(node: JsonNode): FireDecimalChange? {
        if (node.isMissingNode || node.isNull) return null
        if (!node.isObject) malformed()
        val kindName = optionalString(node.path("kind")) ?: malformed()
        val kind = FireAdjustmentKind.entries.firstOrNull { it.name == kindName } ?: malformed()
        val valueNode = node.path("value")
        val value = if (valueNode.isNumber) valueNode.decimalValue() else malformed()
        return FireDecimalChange(kind, value)
    }

    private fun yearChange(node: JsonNode): FireYearChange? {
        if (node.isMissingNode || node.isNull) return null
        if (!node.isObject) malformed()
        val kindName = optionalString(node.path("kind")) ?: malformed()
        val kind = FireYearAdjustmentKind.entries.firstOrNull { it.name == kindName } ?: malformed()
        val value = optionalInt(node.path("value")) ?: malformed()
        return FireYearChange(kind, value)
    }

    private fun events(node: JsonNode): List<FireParsedEvent> {
        if (node.isMissingNode || node.isNull) return emptyList()
        if (!node.isArray) malformed()
        return node.values().take(FireQuestionSchema.MAX_EVENTS).map { item ->
            if (!item.isObject) malformed()
            val year = optionalInt(item.path("year"))
            val yearsFromNow = optionalInt(item.path("yearsFromNow"))
            val kindName = optionalString(item.path("kind")) ?: malformed()
            val kind = CashFlowEventKind.entries.firstOrNull { it.name == kindName } ?: malformed()
            val amountNode = item.path("amount")
            val amount = if (amountNode.isNumber) amountNode.decimalValue() else malformed()
            val label = (optionalString(item.path("label")) ?: "").take(FireQuestionSchema.MAX_EVENT_LABEL)
            val template = optionalString(item.path("template"))?.takeIf { it in FireQuestionSchema.TEMPLATES }
            FireParsedEvent(year, yearsFromNow, kind, amount, label, template)
        }
    }

    private fun unsupported(node: JsonNode): List<FireQuestionUnsupported> {
        if (node.isMissingNode || node.isNull) return emptyList()
        if (!node.isArray) malformed()
        return node.values().take(FireQuestionSchema.MAX_UNSUPPORTED).map { item ->
            if (!item.isObject) malformed()
            val kind = optionalString(item.path("kind"))
                ?.let { name -> FireQuestionUnsupportedKind.entries.firstOrNull { it.name == name } }
                ?: FireQuestionUnsupportedKind.other
            val label = optionalString(item.path("label"))?.take(FireQuestionSchema.MAX_UNSUPPORTED_LABEL)
                ?: malformed()
            FireQuestionUnsupported(kind, label)
        }
    }

    private fun optionalString(node: JsonNode): String? =
        if (node.isMissingNode || node.isNull) null
        else node.takeIf { it.isString }?.asString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun optionalInt(node: JsonNode): Int? =
        if (node.isMissingNode || node.isNull) null
        else if (node.isInt || node.isLong) node.asInt() else malformed()

    private fun optionalDecimal(node: JsonNode): BigDecimal? =
        if (node.isMissingNode || node.isNull) null
        else if (node.isNumber) node.decimalValue() else malformed()

    private fun malformed(): Nothing = throw Refused(FireQuestionRejection.malformed)
}
