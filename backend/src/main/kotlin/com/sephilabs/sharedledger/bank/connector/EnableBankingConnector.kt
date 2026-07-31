package com.sephilabs.sharedledger.bank.connector

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.transaction.Direction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Primary [BankConnector]: Enable Banking's PSD2 AIS API (Restricted Production, read-only).
 * Every call carries a fresh RS256 JWT bearer ([EnableBankingJwt]); JSON is mapped defensively via
 * JsonNode because ASPSP payloads vary in which optional fields they populate. Any HTTP or mapping
 * failure surfaces as [BankConnectorException] so the sync service can mark the connection and move
 * on without killing the run.
 */
@Component
class EnableBankingConnector(
    private val props: AppProperties,
    private val jwt: EnableBankingJwt,
    builder: RestClient.Builder,
) : BankConnector {

    private val log = LoggerFactory.getLogger(EnableBankingConnector::class.java)
    private val client: RestClient = builder.baseUrl(props.enableBanking.baseUrl).build()
    // Parse responses ourselves (Jackson 2 tree): the app runs on Spring/Jackson 3, whose HTTP
    // converter cannot materialize a com.fasterxml JsonNode, so we read the raw String and readTree.
    private val mapper = ObjectMapper()

    @Volatile
    private var lastCallAt = 0L

    private companion object {
        const val RATE_LIMIT_CODE = "ASPSP_RATE_LIMIT_EXCEEDED"
    }

    override fun listAspsps(country: String): List<Aspsp> = call("aspsps country=$country") {
        val body = get("/aspsps?country=$country")
        body.path("aspsps").map {
            Aspsp(
                name = it.path("name").asText(),
                country = it.path("country").textOrNull() ?: country,
                logoUrl = it.path("logo").textOrNull(),
            )
        }
    }

    override fun startAuthorization(request: AuthStartRequest): AuthStart = call("auth aspsp='${request.aspspName}'") {
        val payload = mapOf(
            "access" to mapOf("valid_until" to DateTimeFormatter.ISO_INSTANT.format(request.validUntil)),
            "aspsp" to mapOf("name" to request.aspspName, "country" to request.country),
            "state" to request.state,
            "redirect_url" to props.enableBanking.redirectUrl,
            "psu_type" to "personal",
        )
        val body = post("/auth", payload)
        AuthStart(
            url = body.path("url").textOrNull() ?: throw BankConnectorException("Auth start returned no url"),
            authorizationId = body.path("authorization_id").textOrNull(),
        )
    }

    override fun completeAuthorization(code: String): AuthorizedSession = call("sessions") {
        val body = post("/sessions", mapOf("code" to code))
        val accountsNode = body.path("accounts")
        if (!accountsNode.isArray || accountsNode.isEmpty) {
            // No accounts on the session means the sync will have nothing to fetch — log the payload
            // shape (not values) so a bad link is diagnosable without re-linking blind.
            log.warn(
                "eb_session_no_accounts accountsNodeType={} topLevelKeys={}",
                accountsNode.nodeType, body.fieldNames().asSequence().toList(),
            )
        }
        val accounts = accountsNode.map { acc ->
            AuthorizedAccount(
                uid = acc.path("uid").textOrNull()
                    ?: acc.path("account_id").path("iban").textOrNull()
                    ?: throw BankConnectorException("Account without uid"),
                iban = acc.path("account_id").path("iban").textOrNull() ?: acc.path("iban").textOrNull(),
                name = acc.path("name").textOrNull() ?: acc.path("product").textOrNull(),
                currency = acc.path("currency").textOrNull(),
            )
        }
        log.info("eb_session_created accounts={}", accounts.size)
        AuthorizedSession(
            sessionId = body.path("session_id").textOrNull() ?: throw BankConnectorException("No session_id"),
            accounts = accounts,
            consentExpiresAt = body.path("access").path("valid_until").textOrNull()?.let { parseInstant(it) },
        )
    }

    override fun sessionStatus(sessionId: String): ConsentStatus = call("session status") {
        val body = get("/sessions/$sessionId")
        when (body.path("status").asText().uppercase()) {
            "AUTHORIZED", "VALID", "ACTIVE" -> ConsentStatus.ACTIVE
            "EXPIRED", "REVOKED", "CLOSED" -> ConsentStatus.EXPIRED
            else -> ConsentStatus.INVALID
        }
    }

    override fun fetchMovements(
        sessionId: String,
        accountUid: String,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        strategy: FetchStrategy,
        continuationKey: String?,
        psu: PsuContext?,
    ): MovementPage {
        val query = buildString {
            append("/accounts/").append(accountUid).append("/transactions")
            append("?strategy=").append(if (strategy == FetchStrategy.LONGEST) "longest" else "default")
            // longest: date_from is only a hint and date_to is ignored; default: dates bound the window.
            dateFrom?.let { append("&date_from=").append(it.format(DateTimeFormatter.ISO_LOCAL_DATE)) }
            if (strategy == FetchStrategy.DEFAULT) {
                dateTo?.let { append("&date_to=").append(it.format(DateTimeFormatter.ISO_LOCAL_DATE)) }
            }
            if (continuationKey != null) append("&continuation_key=").append(continuationKey)
        }
        val headers = if (psu != null) {
            mapOf("psu-ip-address" to psu.ipAddress, "psu-user-agent" to psu.userAgent)
        } else {
            emptyMap()
        }
        // The op label (not the raw query — it carries the opaque continuation key) is what a failure
        // log shows, so an ASPSP that refuses one particular window/page is identifiable afterwards.
        val op = "transactions account=$accountUid strategy=$strategy from=$dateFrom to=$dateTo " +
            "page=${if (continuationKey == null) "first" else "next"} psu=${psu != null}"
        return fetchPage(op, query, headers, accountUid)
    }

    private fun fetchPage(
        op: String,
        query: String,
        headers: Map<String, String>,
        accountUid: String,
    ): MovementPage = call(op) {
        val body = get(query, headers)
        val raw = body.path("transactions")
        val movements = raw.mapNotNull { txn ->
            mapMovement(txn) ?: run {
                // Log the SHAPE (never values) of a dropped row so an unmapped movement — e.g. a
                // pending entry whose fields the mapper rejects — is diagnosable from prod logs.
                log.warn(
                    "eb_drop account={} status={} hasCreditDebit={} hasBooking={} hasValue={} hasTxnDate={} hasAmount={}",
                    accountUid,
                    txn.path("status").textOrNull(),
                    txn.hasNonNull("credit_debit_indicator"),
                    txn.hasNonNull("booking_date"),
                    txn.hasNonNull("value_date"),
                    txn.hasNonNull("transaction_date"),
                    txn.path("transaction_amount").hasNonNull("amount"),
                )
                null
            }
        }
        if (raw.size() != movements.size) {
            log.info(
                "eb_fetch account={} rawTransactions={} mapped={} dropped={}",
                accountUid, raw.size(), movements.size, raw.size() - movements.size,
            )
        } else {
            log.debug("eb_fetch account={} rawTransactions={} mapped={}", accountUid, raw.size(), movements.size)
        }
        MovementPage(movements = movements, continuationKey = body.path("continuation_key").textOrNull())
    }

    private fun mapMovement(node: JsonNode): BankMovement? {
        val amountNode = node.path("transaction_amount")
        val rawAmount = amountNode.path("amount").textOrNull() ?: return null
        val amount = rawAmount.toBigDecimalOrNull() ?: return null
        val direction = when (node.path("credit_debit_indicator").asText().uppercase()) {
            "CRDT" -> Direction.income
            "DBIT" -> Direction.expense
            // Pending (PDNG) entries from some ASPSPs (notably ING) omit credit_debit_indicator; the
            // signed transaction_amount then carries the direction (negative = debit/expense). We keep
            // the row instead of dropping it — otherwise a same-day transaction is invisible until the
            // bank books it the next business day.
            else -> if (amount.signum() < 0) Direction.expense else Direction.income
        }
        // Pending entries may also lack booking_date/value_date; transaction_date is the last resort.
        val booking = (node.path("booking_date").textOrNull()
            ?: node.path("value_date").textOrNull()
            ?: node.path("transaction_date").textOrNull())
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val counterparty = when (direction) {
            Direction.expense -> node.path("creditor").path("name").textOrNull()
            Direction.income -> node.path("debtor").path("name").textOrNull()
        } ?: node.path("creditor").path("name").textOrNull() ?: node.path("debtor").path("name").textOrNull()
        val description = node.path("remittance_information")
            .takeIf { it.isArray && it.size() > 0 }
            ?.joinToString(" ") { it.asText() }
            ?: node.path("remittance_information_unstructured").textOrNull()
        val id = node.path("entry_reference").textOrNull()
            ?: node.path("transaction_id").textOrNull()
            ?: "$booking|$rawAmount|${node.path("credit_debit_indicator").asText()}|${description ?: ""}"
        return BankMovement(
            bankMovementId = id,
            bookingDate = booking,
            valueDate = node.path("value_date").textOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            direction = direction,
            amount = amount.abs(),
            currency = amountNode.path("currency").textOrNull() ?: "EUR",
            counterparty = counterparty,
            description = description,
            reference = node.path("bank_transaction_code").path("description").textOrNull(),
        )
    }

    // --- HTTP plumbing -----------------------------------------------------------------------

    private fun get(path: String, headers: Map<String, String> = emptyMap()): JsonNode {
        var req = client.get().uri(path).header("Authorization", "Bearer ${jwt.bearer()}")
        headers.forEach { (k, v) -> req = req.header(k, v) }
        val body = req.retrieve().body(String::class.java) ?: throw BankConnectorException("Empty response for $path")
        return mapper.readTree(body)
    }

    private fun post(path: String, payload: Any): JsonNode {
        val body = client.post().uri(path).header("Authorization", "Bearer ${jwt.bearer()}")
            .body(payload).retrieve().body(String::class.java)
            ?: throw BankConnectorException("Empty response for $path")
        return mapper.readTree(body)
    }

    private fun <T> call(op: String, block: () -> T): T {
        pace()
        return try {
            block()
        } catch (ex: BankConnectorException) {
            throw ex
        } catch (ex: RestClientResponseException) {
            val error = errorOf(ex.responseBodyAsString)
            log.warn(
                "eb_call_failed op='{}' status={} code={} message='{}' detail='{}'",
                op, ex.statusCode.value(), error.code, error.message, error.detail,
            )
            if (error.code == RATE_LIMIT_CODE) throw RateLimitExceededException(RATE_LIMIT_CODE, ex)
            throw BankConnectorException(
                error.describe() ?: ex.message ?: "Enable Banking call failed",
                ex,
                error.code,
            )
        } catch (ex: Exception) {
            log.warn("eb_call_failed op='{}' error={}", op, ex.message)
            throw BankConnectorException(ex.message ?: "Enable Banking call failed", ex)
        }
    }

    /**
     * An Enable Banking error body looks like
     * `{"code": 400, "message": "Error interacting with ASPSP", "detail": "…", "error": "ASPSP_ERROR"}`:
     * `code` is the **numeric HTTP status** and the machine-readable code lives in `error`. Reading
     * `code` first (as this used to) yielded "400"/"429", so `ASPSP_RATE_LIMIT_EXCEEDED` was never
     * recognised — a rate-limited connection was treated as a hard failure instead of backing off —
     * and every provider error reached the UI as a bare status number.
     */
    private data class ProviderError(val code: String?, val message: String?, val detail: String?) {
        /** e.g. `ASPSP_ERROR: Error interacting with ASPSP — Unknown error`, for the UI and the run log. */
        fun describe(): String? {
            val head = code ?: return message
            val tail = listOfNotNull(message, detail?.takeIf { it != message }).joinToString(" — ")
            return if (tail.isBlank()) head else "$head: $tail"
        }
    }

    private fun errorOf(body: String?): ProviderError {
        if (body.isNullOrBlank()) return ProviderError(null, null, null)
        return runCatching {
            val node = mapper.readTree(body)
            ProviderError(
                // `code` only counts as the machine code when it isn't the numeric status.
                code = node.path("error").textOrNull() ?: node.path("code").takeIf { it.isTextual }?.textOrNull(),
                message = node.path("message").textOrNull(),
                detail = node.path("detail").textOrNull(),
            )
        }.getOrElse { ProviderError(null, body.take(200), null) }
    }

    @Synchronized
    private fun pace() {
        val minInterval = props.enableBanking.minRequestIntervalMs
        val wait = minInterval - (System.currentTimeMillis() - lastCallAt)
        if (wait in 1..minInterval) Thread.sleep(wait)
        lastCallAt = System.currentTimeMillis()
    }

    private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

    /** Jackson's asText() returns "null"/"" for null/missing nodes; this collapses those to null. */
    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText().ifBlank { null }
}
