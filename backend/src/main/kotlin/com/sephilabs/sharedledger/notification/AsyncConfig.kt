package com.sephilabs.sharedledger.notification

import org.slf4j.LoggerFactory
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

/** Enables @Async with a small bounded executor for Telegram dispatch, so a backlog or a slow Telegram API
 *  can never exhaust threads or block request/scheduler threads. */
@Configuration
@EnableAsync
class AsyncConfig : AsyncConfigurer {

    @Bean("telegramExecutor")
    @Profile("!test")
    fun telegramExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 4
        queueCapacity = 200
        setThreadNamePrefix("telegram-")
        setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
        initialize()
    }

    /** Backfill fans a handful of provider calls per holding; a small bounded pool keeps request threads free,
     *  and CallerRuns degrades to synchronous under backlog rather than dropping work. Tests supply a
     *  synchronous executor of the same name. */
    @Bean("backfillExecutor")
    @Profile("!test")
    fun backfillExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 2
        queueCapacity = 500
        setThreadNamePrefix("backfill-")
        setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
        initialize()
    }

    /** Bank sync fans out per-connection provider I/O. A small bounded pool keeps the linking request thread
     *  free — the initial sync runs off-thread after the connection commits. Tests supply a synchronous
     *  executor of the same name so they can assert on results immediately. */
    @Bean("bankSyncExecutor")
    @Profile("!test")
    fun bankSyncExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 2
        queueCapacity = 200
        setThreadNamePrefix("bank-sync-")
        setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
        initialize()
    }

    override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler =
        AsyncUncaughtExceptionHandler { ex, method, _ ->
            LoggerFactory.getLogger(AsyncConfig::class.java)
                .error("Uncaught async error in {}", method.name, ex)
        }
}
