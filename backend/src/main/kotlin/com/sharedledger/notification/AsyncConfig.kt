package com.sharedledger.notification

import org.slf4j.LoggerFactory
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

/**
 * Enables @Async and provides a small, bounded executor for Telegram dispatch so a backlog or a
 * slow Telegram API can never exhaust threads or block request/scheduler threads.
 */
@Configuration
@EnableAsync
class AsyncConfig : AsyncConfigurer {

    @Bean("telegramExecutor")
    fun telegramExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 4
        queueCapacity = 200
        setThreadNamePrefix("telegram-")
        setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
        initialize()
    }

    override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler =
        AsyncUncaughtExceptionHandler { ex, method, _ ->
            LoggerFactory.getLogger(AsyncConfig::class.java)
                .error("Uncaught async error in {}", method.name, ex)
        }
}
