package com.sephilabs.sharedledger.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class AppMetrics(private val registry: MeterRegistry) {

    fun transactionCreated(direction: String, categoryGroup: String) {
        Counter.builder("sl_transactions_created_total")
            .tag("direction", direction)
            .tag("category_group", categoryGroup)
            .register(registry)
            .increment()
    }

    fun movementCreated(type: String, targetClass: String?, targetLiabilityId: String?) {
        val builder = Counter.builder("sl_movements_created_total").tag("movement_type", type)
        targetClass?.let { builder.tag("target_class", it) }
        targetLiabilityId?.let { builder.tag("target_liability_id", it) }
        builder.register(registry).increment()
    }

    fun snapshotCreated() {
        Counter.builder("sl_snapshots_created_total").register(registry).increment()
    }

    fun recurringMaterialized(templateId: String) {
        Counter.builder("sl_recurring_materialized_total")
            .tag("template_id", templateId)
            .register(registry)
            .increment()
    }

    fun recurringFailure(templateId: String) {
        Counter.builder("sl_recurring_materialization_failures_total")
            .tag("template_id", templateId)
            .register(registry)
            .increment()
    }

    fun loginAttempt(outcome: String) {
        Counter.builder("sl_login_attempts_total")
            .tag("outcome", outcome)
            .register(registry)
            .increment()
    }

    fun registration(mode: String, outcome: String) {
        Counter.builder("sl_registrations_total")
            .tag("mode", mode)
            .tag("outcome", outcome)
            .register(registry)
            .increment()
    }

    fun invitationIssued(role: String) {
        Counter.builder("sl_invitations_issued_total")
            .tag("role", role)
            .register(registry)
            .increment()
    }

    fun invitationAccepted(role: String) {
        Counter.builder("sl_invitations_accepted_total")
            .tag("role", role)
            .register(registry)
            .increment()
    }

    fun priceRefreshed(provider: String) {
        Counter.builder("sl_portfolio_prices_refreshed_total")
            .tag("provider", provider)
            .register(registry)
            .increment()
    }

    fun priceRefreshFailure(provider: String) {
        Counter.builder("sl_portfolio_price_refresh_failures_total")
            .tag("provider", provider)
            .register(registry)
            .increment()
    }

    fun bankMovementsIngested(count: Int) {
        if (count <= 0) return
        Counter.builder("sl_bank_movements_ingested_total")
            .register(registry)
            .increment(count.toDouble())
    }

    fun bankSyncFailure() {
        Counter.builder("sl_bank_sync_failures_total")
            .register(registry)
            .increment()
    }

    fun analyticsTimer(endpoint: String): Timer =
        Timer.builder("sl_analytics_request_seconds")
            .tag("endpoint", endpoint)
            .register(registry)

    fun registerActiveSessionsGauge(supplier: () -> Number) {
        io.micrometer.core.instrument.Gauge
            .builder("sl_active_sessions") { supplier().toDouble() }
            .register(registry)
    }
}
