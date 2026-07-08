package com.sephilabs.sharedledger.bank.connector

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.transaction.Direction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
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

    override fun listAspsps(country: String): List<Aspsp> = call {
        val body = get("/aspsps?country=$country")
        body.path("aspsps").map {
            Aspsp(
                name = it.path("name").asText(),
                country = it.path("country").textOrNull() ?: country,
                logoUrl = it.path("logo").textOrNull(),
            )
        }
    }

    override fun startAuthorization(request: AuthStartRequest): AuthStart = call {
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

    override fun completeAuthorization(code: String): AuthorizedSession = call {
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

    override fun sessionStatus(sessionId: String): ConsentStatus = call {
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
        dateFrom: LocalDate,
        continuationKey: String?,
    ): MovementPage = call {
        val query = buildString {
            append("/accounts/").append(accountUid).append("/transactions")
            append("?date_from=").append(dateFrom.format(DateTimeFormatter.ISO_LOCAL_DATE))
            if (continuationKey != null) append("&continuation_key=").append(continuationKey)
        }
        val body = get(query)
        val raw = body.path("transactions")
        val movements = raw.mapNotNull { mapMovement(it) }
        if (raw.size() != movements.size) {
            log.info(
                "eb_fetch account={} rawTransactions={} mapped={} dropped={} (unmappable: missing date or credit_debit_indicator)",
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
        val direction = when (node.path("credit_debit_indicator").asText().uppercase()) {
            "CRDT" -> Direction.income
            "DBIT" -> Direction.expense
            else -> return null
        }
        val booking = (node.path("booking_date").textOrNull() ?: node.path("value_date").textOrNull())
            ?.let { LocalDate.parse(it) } ?: return null
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
            valueDate = node.path("value_date").textOrNull()?.let { LocalDate.parse(it) },
            direction = direction,
            amount = BigDecimal(rawAmount).abs(),
            currency = amountNode.path("currency").textOrNull() ?: "EUR",
            counterparty = counterparty,
            description = description,
            reference = node.path("bank_transaction_code").path("description").textOrNull(),
        )
    }

    // --- HTTP plumbing -----------------------------------------------------------------------

    private fun get(path: String): JsonNode {
        val body = client.get().uri(path).header("Authorization", "Bearer ${jwt.bearer()}")
            .retrieve().body(String::class.java) ?: throw BankConnectorException("Empty response for $path")
        return mapper.readTree(body)
    }

    private fun post(path: String, payload: Any): JsonNode {
        val body = client.post().uri(path).header("Authorization", "Bearer ${jwt.bearer()}")
            .body(payload).retrieve().body(String::class.java)
            ?: throw BankConnectorException("Empty response for $path")
        return mapper.readTree(body)
    }

    private fun <T> call(block: () -> T): T {
        pace()
        return try {
            block()
        } catch (ex: BankConnectorException) {
            throw ex
        } catch (ex: Exception) {
            log.warn("Enable Banking call failed: {}", ex.message)
            throw BankConnectorException(ex.message ?: "Enable Banking call failed", ex)
        }
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
