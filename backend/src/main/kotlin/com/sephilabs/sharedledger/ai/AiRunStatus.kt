package com.sephilabs.sharedledger.ai

/** Lifecycle of an asynchronous AI review run (FIRE plan review, Analytics spending review). */
enum class AiRunStatus { running, completed, failed, interrupted }
