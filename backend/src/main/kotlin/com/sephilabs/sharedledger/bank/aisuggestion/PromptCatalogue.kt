package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.i18n.Messages
import com.sephilabs.sharedledger.transaction.Direction
import org.springframework.stereotype.Component
import java.util.Locale
import java.util.UUID

/** The household's categories as every AI prompt shows them. */
@Component
class PromptCatalogue(private val categories: CategoryService, private val messages: Messages) {

    fun forHousehold(householdId: UUID): List<CatalogueEntry> =
        categories.listForHousehold(householdId).map {
            CatalogueEntry(
                code = it.code,
                // Global names are i18n keys; the prompt is in English whatever the members read.
                name = if (it.custom) it.name else messages.resolve(it.name, fallback = it.code, locale = Locale.ENGLISH),
                group = it.group,
                direction = Direction.valueOf(it.kind),
                essential = it.essential,
            )
        }
}
