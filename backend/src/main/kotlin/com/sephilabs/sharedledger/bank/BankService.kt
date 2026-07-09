package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.bank.connector.BankConnector
import com.sephilabs.sharedledger.bank.connector.BankConnectorException
import com.sephilabs.sharedledger.bank.connector.BankCrypto
import com.sephilabs.sharedledger.bank.connector.PsuContext
import com.sephilabs.sharedledger.bank.sync.BankConnectionLinked
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.identity.user.User
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Facade over the split bank storage (connections + accounts + auth sessions + sync runs). Manages
 * the link/re-link lifecycle and the config that drives UI visibility. The feature is only usable
 * when the operator configured Enable Banking via env (`props.enableBanking.configured`).
 */
@Service
class BankService(
    private val props: AppProperties,
    private val connector: BankConnector,
    private val crypto: BankCrypto,
    private val connections: BankConnectionRepository,
    private val accounts: BankConnectionAccountRepository,
    private val authSessions: BankAuthSessionRepository,
    private val syncRuns: BankSyncRunRepository,
    private val events: ApplicationEventPublisher,
) {

    @Transactional(readOnly = true)
    fun config(householdId: UUID): BankConfigDto =
        BankConfigDto(featureEnabled = props.enableBanking.configured, connectionCount = connections.countByHouseholdId(householdId))

    @Transactional(readOnly = true)
    fun listAspsps(country: String): List<AspspDto> {
        requireConfigured()
        return try {
            connector.listAspsps(country.uppercase()).map { AspspDto(it.name, it.country, it.logoUrl) }
        } catch (ex: BankConnectorException) {
            throw AppException.badRequest("BANK_PROVIDER_ERROR", ex.message ?: "")
        }
    }

    @Transactional(readOnly = true)
    fun listConnections(householdId: UUID): List<BankConnectionDto> =
        connections.findAllByHouseholdIdOrderByCreatedAtAsc(householdId).map { it.toDto() }

    @Transactional
    fun startLink(householdId: UUID, request: StartLinkRequest, by: User): StartLinkResponse {
        requireConfigured()
        if (request.relinkConnectionId != null) {
            connections.findByIdAndHouseholdId(request.relinkConnectionId, householdId)
                ?: throw AppException.notFound("BANK_CONNECTION_NOT_FOUND")
        }
        val state = UUID.randomUUID().toString().replace("-", "")
        val validUntil = Instant.now().plus(Duration.ofDays(props.enableBanking.consentValidDays))
        authSessions.save(
            BankAuthSession(
                state = state,
                householdId = householdId,
                holderUserId = by.id,
                aspspName = request.aspspName,
                aspspCountry = request.country.uppercase(),
                label = request.label,
                relinkConnectionId = request.relinkConnectionId,
            ),
        )
        val start = try {
            connector.startAuthorization(
                com.sephilabs.sharedledger.bank.connector.AuthStartRequest(
                    aspspName = request.aspspName,
                    country = request.country.uppercase(),
                    state = state,
                    validUntil = validUntil,
                ),
            )
        } catch (ex: BankConnectorException) {
            throw AppException.badRequest("BANK_PROVIDER_ERROR", ex.message ?: "")
        }
        return StartLinkResponse(authUrl = start.url)
    }

    @Transactional
    fun completeLink(householdId: UUID, request: CompleteLinkRequest, by: User, psu: PsuContext? = null): BankConnectionDto {
        requireConfigured()
        val auth = authSessions.findById(request.state).orElse(null)
            ?: throw AppException.badRequest("BANK_AUTH_STATE_INVALID")
        if (auth.householdId != householdId) throw AppException.forbidden("BANK_AUTH_STATE_MISMATCH")

        val session = try {
            connector.completeAuthorization(request.code)
        } catch (ex: BankConnectorException) {
            throw AppException.badRequest("BANK_PROVIDER_ERROR", ex.message ?: "")
        }

        val connection = auth.relinkConnectionId
            ?.let { connections.findByIdAndHouseholdId(it, householdId) }
            ?: BankConnection(
                householdId = householdId,
                aspspName = auth.aspspName,
                aspspCountry = auth.aspspCountry,
                label = auth.label ?: auth.aspspName,
                holderUserId = auth.holderUserId,
                createdByUserId = by.id,
            )
        connection.sessionIdEnc = crypto.encrypt(session.sessionId)
        connection.status = ConnectionStatus.active
        connection.consentExpiresAt = session.consentExpiresAt
        connection.updatedByUserId = by.id
        connection.callsUsedToday = 0
        connection.callsResetOn = null
        connections.save(connection)

        session.accounts.forEach { acc ->
            val existing = accounts.findByConnectionIdAndAccountUid(connection.id, acc.uid)
            if (existing == null) {
                accounts.save(
                    BankConnectionAccount(
                        connectionId = connection.id,
                        accountUid = acc.uid,
                        ibanMasked = maskIban(acc.iban),
                        name = acc.name,
                        currency = acc.currency,
                    ),
                )
            } else {
                existing.ibanMasked = maskIban(acc.iban) ?: existing.ibanMasked
                existing.name = acc.name ?: existing.name
                existing.currency = acc.currency ?: existing.currency
            }
        }

        authSessions.delete(auth)
        // Initial backfill sync runs off-thread once this transaction commits.
        events.publishEvent(BankConnectionLinked(connection.id, psu))
        return connection.toDto()
    }

    @Transactional
    fun update(householdId: UUID, id: UUID, request: UpdateConnectionRequest, by: User): BankConnectionDto {
        val connection = connections.findByIdAndHouseholdId(id, householdId)
            ?: throw AppException.notFound("BANK_CONNECTION_NOT_FOUND")
        request.label?.let { connection.label = it }
        request.ingestionEnabled?.let { connection.ingestionEnabled = it }
        request.syncFrequency?.let { connection.syncFrequency = it }
        connection.updatedByUserId = by.id
        return connection.toDto()
    }

    @Transactional
    fun delete(householdId: UUID, id: UUID) {
        val connection = connections.findByIdAndHouseholdId(id, householdId)
            ?: throw AppException.notFound("BANK_CONNECTION_NOT_FOUND")
        // Cascades remove accounts, pending movements, and sync runs (FK ON DELETE CASCADE).
        connections.delete(connection)
    }

    private fun requireConfigured() {
        if (!props.enableBanking.configured) throw AppException.badRequest("BANK_NOT_CONFIGURED")
    }

    private fun maskIban(iban: String?): String? {
        val trimmed = iban?.replace(" ", "") ?: return null
        if (trimmed.length <= 4) return trimmed
        return "••••${trimmed.takeLast(4)}"
    }

    private fun BankConnection.toDto(): BankConnectionDto {
        val lastRun = syncRuns.findFirstByConnectionIdOrderByStartedAtDesc(id)
        return BankConnectionDto(
            id = id,
            provider = provider,
            aspspName = aspspName,
            aspspCountry = aspspCountry,
            label = label,
            status = status,
            consentExpiresAt = consentExpiresAt,
            lastSyncedAt = lastSyncedAt,
            ingestionEnabled = ingestionEnabled,
            syncFrequency = syncFrequency,
            accounts = accounts.findAllByConnectionId(id).map {
                BankAccountDto(id = it.id, ibanMasked = it.ibanMasked, name = it.name, currency = it.currency)
            },
            lastSyncStatus = lastRun?.status,
            lastSyncError = lastRun?.errorMessage,
        )
    }
}
