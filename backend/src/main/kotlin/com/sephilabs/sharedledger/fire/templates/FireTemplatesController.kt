package com.sephilabs.sharedledger.fire.templates

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.sharedledger.fire.FireService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

/** Amounts the life-event templates can suggest, read from the same spending window as the photo. */
data class FireTemplateSuggestions(
    /** Monthly average of the mortgage category over the current window; null when it has no spending there. */
    @JsonFormat(shape = JsonFormat.Shape.STRING) val mortgageMonthly: BigDecimal?,
    val windowMonths: Int,
)

@RestController
@RequestMapping("/api/households/{householdId}/fire/templates")
class FireTemplatesController(private val fire: FireService) {

    @GetMapping("/suggestions")
    fun suggestions(@PathVariable householdId: UUID): FireTemplateSuggestions {
        val window = fire.currentSpendingWindow(householdId)
        val mortgage = (window.essentialCategories + window.nonEssentialCategories)
            .firstOrNull { it.categoryCode == MORTGAGE_CATEGORY }
            ?.monthlyAverage
            ?.takeIf { it.signum() > 0 }
        return FireTemplateSuggestions(mortgageMonthly = mortgage, windowMonths = window.rangeMonths)
    }

    private companion object {
        const val MORTGAGE_CATEGORY = "home.mortgage"
    }
}
