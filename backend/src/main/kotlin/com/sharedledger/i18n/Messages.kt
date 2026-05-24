package com.sharedledger.i18n

import org.springframework.context.MessageSource
import org.springframework.context.NoSuchMessageException
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Component
import java.util.Locale

@Component
class Messages(private val source: MessageSource) {
    fun resolve(code: String, args: Array<out Any> = emptyArray(), fallback: String = code, locale: Locale = LocaleContextHolder.getLocale()): String {
        return try {
            source.getMessage(code, args, locale)
        } catch (_: NoSuchMessageException) {
            fallback
        }
    }
}
