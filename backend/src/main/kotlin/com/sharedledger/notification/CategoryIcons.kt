package com.sharedledger.notification

/**
 * Backend mirror of `frontend/src/lib/categoryGroup.ts`. The category-group emoji shown on the
 * mobile cards is reused verbatim in Telegram notifications. Keep this in sync with the frontend
 * GROUP_ICONS map.
 */
object CategoryIcons {
    private val GROUP_ICONS = mapOf(
        "income" to "💵",                 // 💵
        "home" to "🏠",                   // 🏠
        "transport" to "🚗",              // 🚗
        "groceries" to "🛒",              // 🛒
        "shopping" to "🛍️",         // 🛍️
        "outings" to "🎉",                // 🎉
        "financial" to "💳",              // 💳
        "health" to "🩺",                 // 🩺
        "personal" to "👤",               // 👤
        "ungrouped" to "🏷️",        // 🏷️
    )

    /** Mirrors groupFromCode: the segment before the first ".", or "ungrouped" when there is none. */
    fun groupFromCode(code: String): String {
        val i = code.indexOf('.')
        return if (i == -1) "ungrouped" else code.substring(0, i)
    }

    fun icon(code: String): String = GROUP_ICONS[groupFromCode(code)] ?: GROUP_ICONS.getValue("ungrouped")
}
