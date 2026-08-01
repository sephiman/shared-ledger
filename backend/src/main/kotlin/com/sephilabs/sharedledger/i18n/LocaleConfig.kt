package com.sephilabs.sharedledger.i18n

import com.sephilabs.sharedledger.identity.user.UserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.DispatcherServlet
import org.springframework.web.servlet.LocaleResolver
import java.util.Locale

@Configuration
class LocaleConfig {

    @Bean(name = [DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME])
    fun localeResolver(users: UserRepository): LocaleResolver = UserAwareLocaleResolver(users)

    /** Explicit message source for `i18n/messages_*.properties`, declared rather than auto-configured so
     *  Telegram notifications render real localized text instead of falling back to the raw key. */
    @Bean
    fun messageSource(): MessageSource = ResourceBundleMessageSource().apply {
        setBasename("i18n/messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }
}

class UserAwareLocaleResolver(private val users: UserRepository) : LocaleResolver {

    private val supported = setOf(Locale.ENGLISH, Locale.of("es"))
    private val default = Locale.ENGLISH

    override fun resolveLocale(request: HttpServletRequest): Locale {
        request.getHeader("Accept-Language")?.let { header ->
            for (range in Locale.LanguageRange.parse(header)) {
                val candidate = Locale.forLanguageTag(range.range.split('-').first())
                if (supported.any { it.language == candidate.language }) {
                    return supported.first { it.language == candidate.language }
                }
            }
        }
        val auth = SecurityContextHolder.getContext().authentication
        val principal = auth?.principal
        if (principal is org.springframework.security.core.userdetails.UserDetails) {
            users.findByEmailIgnoreCase(principal.username)?.locale?.let { code ->
                val pref = Locale.forLanguageTag(code)
                if (supported.any { it.language == pref.language }) {
                    return supported.first { it.language == pref.language }
                }
            }
        }
        return default
    }

    override fun setLocale(request: HttpServletRequest, response: HttpServletResponse?, locale: Locale?) {
        // Locale is persisted on the user entity; nothing to do here.
    }
}
