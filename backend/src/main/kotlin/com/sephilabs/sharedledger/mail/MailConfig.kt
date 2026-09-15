package com.sephilabs.sharedledger.mail

import com.sephilabs.sharedledger.config.AppProperties
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskDecorator

@Configuration
class MailConfig {

    private val log = LoggerFactory.getLogger(MailConfig::class.java)

    @Bean
    fun smtpSettings(props: AppProperties): SmtpSettings {
        val settings = SmtpSettings.resolve(props.mail, props.publicUrl)
        when (settings) {
            is SmtpSettings.Configured ->
                log.info("smtp_configured host={} port={} starttls={}", settings.host, settings.port, settings.startTls)
            is SmtpSettings.PartiallyConfigured ->
                log.warn(
                    "smtp_partially_configured missing={} outcome=password_reset_hidden",
                    settings.missing.joinToString(","),
                )
            SmtpSettings.Absent -> log.info("smtp_not_configured outcome=password_reset_hidden")
        }
        return settings
    }

    @Bean
    fun emailSender(settings: SmtpSettings): EmailSender = when (settings) {
        is SmtpSettings.Configured -> SmtpEmailSender(settings)
        else -> DisabledEmailSender
    }
}

/** Carries the request's MDC (requestId, clientIp) onto the mail worker so every `password_reset_*` line
 *  stays correlated with the request that caused it. */
object MdcPropagatingTaskDecorator : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable {
        val context = MDC.getCopyOfContextMap()
        return Runnable {
            if (context != null) MDC.setContextMap(context) else MDC.clear()
            try {
                runnable.run()
            } finally {
                MDC.clear()
            }
        }
    }
}
