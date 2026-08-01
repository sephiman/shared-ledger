package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.bank.connector.BankConnector
import com.sephilabs.sharedledger.bank.connector.BankConnectorException
import com.sephilabs.sharedledger.bank.connector.BankCrypto
import com.sephilabs.sharedledger.bank.connector.PsuContext
import com.sephilabs.sharedledger.bank.sync.BankConnectionLinked
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.user.User
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** Facade over the split bank storage (connections + accounts + auth sessions + sync runs). Usable only
 *  by households that configured their own application in Settings → Banks ([BankCredentialsService]). */
@Service
class BankService(
    private val props: AppProperties,
    private val connector: BankConnector,
    private val crypto: BankCrypto,
    private val credentials: BankCredentialsService,
    private val callbackUrl: BankCallbackUrl,
    private val connections: BankConnectionRepository,
    private val accounts: BankConnectionAccountRepository,
    private val authSessions: BankAuthSessionRepository,
    private val syncRuns: BankSyncRunRepository,
    private val events: ApplicationEventPublisher,
) {

    @Transactional(readOnly = true)
    fun config(householdId: UUID): BankConfigDto {
        val configured = credentials.findRow(householdId) != null
        return BankConfigDto(
            credentialsConfigured = configured,
            connectionCount = connections.countByHouseholdId(householdId),
            // Absolute instants so the UI can render them in the viewer's own timezone (DST-correct).
            nextSyncTimes = if (configured) upcomingSyncRuns(count = 3) else emptyList(),
        )
    }

    /** The next [count] background-sync fire times, from the configured cron + scheduler zone. */
    private fun upcomingSyncRuns(count: Int): List<Instant> {
        val cron = runCatching { CronExpression.parse(props.enableBanking.syncCron) }.getOrNull() ?: return emptyList()
        val zone = runCatching { ZoneId.of(props.scheduler.timezone) }.getOrDefault(ZoneId.of("UTC"))
        val runs = mutableListOf<Instant>()
        var cursor: ZonedDateTime = ZonedDateTime.now(zone)
        repeat(count) {
            val next = cron.next(cursor) ?: return runs
            runs.add(next.toInstant())
            cursor = next
        }
        return runs
    }

    @Transactional(readOnly = true)
    fun listAspsps(householdId: UUID, country: String): List<AspspDto> {
        val creds = credentials.require(householdId)
        return try {
            connector.listAspsps(creds, country.uppercase()).map { AspspDto(it.name, it.country, it.logoUrl) }
        } catch (ex: BankConnectorException) {
            throw AppException.badRequest("BANK_PROVIDER_ERROR", ex.message ?: "")
        }
    }

    @Transactional(readOnly = true)
    fun listConnections(householdId: UUID, by: User, role: HouseholdRole): List<BankConnectionDto> {
        val configuredAppId = credentials.findRow(householdId)?.appId
        return connections.findAllByHouseholdIdOrderByCreatedAtAsc(householdId)
            .map { it.toDto(canManage(it, by, role), configuredAppId) }
    }

    /** The status to *show*. The stored one only catches up on the next sync, so a household that just lost
     *  or changed its credentials would display a stale `active` for hours. [BankSyncService] persists the
     *  same rule. */
    private fun effectiveStatus(connection: BankConnection, configuredAppId: String?): ConnectionStatus = when {
        configuredAppId == null -> ConnectionStatus.credentials_required
        configuredAppId != connection.appId -> ConnectionStatus.credentials_mismatch
        else -> connection.status
    }

    /** True when [by] may sync, re-link, edit or delete [connection]: owners always, plus the member who
     *  linked it or whose account it is. Rows predating per-member linking have neither and stay owner-only. */
    fun canManage(connection: BankConnection, by: User, role: HouseholdRole): Boolean =
        role == HouseholdRole.owner ||
            connection.createdByUserId == by.id ||
            connection.holderUserId == by.id

    /** Resolve a connection in this household and assert the caller may manage it. */
    private fun manageable(householdId: UUID, id: UUID, by: User, role: HouseholdRole): BankConnection {
        val connection = connections.findByIdAndHouseholdId(id, householdId)
            ?: throw AppException.notFound("BANK_CONNECTION_NOT_FOUND")
        if (!canManage(connection, by, role)) throw AppException.forbidden("NOT_CONNECTION_MANAGER")
        return connection
    }

    /** Authorization-only variant, for callers that hand the work off elsewhere (e.g. "Sync now"). */
    @Transactional(readOnly = true)
    fun requireManageable(householdId: UUID, id: UUID, by: User, role: HouseholdRole) {
        manageable(householdId, id, by, role)
    }

    @Transactional
    fun startLink(householdId: UUID, request: StartLinkRequest, by: User, role: HouseholdRole): StartLinkResponse {
        val creds = credentials.require(householdId)
        // Any member may link a *new* bank of their own; re-linking an existing connection is
        // restricted to whoever may manage it.
        if (request.relinkConnectionId != null) {
            manageable(householdId, request.relinkConnectionId, by, role)
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
                creds,
                com.sephilabs.sharedledger.bank.connector.AuthStartRequest(
                    aspspName = request.aspspName,
                    country = request.country.uppercase(),
                    state = state,
                    validUntil = validUntil,
                    redirectUrl = callbackUrl.current(),
                ),
            )
        } catch (ex: BankConnectorException) {
            throw AppException.badRequest("BANK_PROVIDER_ERROR", ex.message ?: "")
        }
        return StartLinkResponse(authUrl = start.url)
    }

    @Transactional
    fun completeLink(
        householdId: UUID,
        request: CompleteLinkRequest,
        by: User,
        role: HouseholdRole,
        psu: PsuContext? = null,
    ): BankConnectionDto {
        val creds = credentials.require(householdId)
        val auth = authSessions.findById(request.state).orElse(null)
            ?: throw AppException.badRequest("BANK_AUTH_STATE_INVALID")
        if (auth.householdId != householdId) throw AppException.forbidden("BANK_AUTH_STATE_MISMATCH")
        // Now that any member can start a link, the callback must be finished by the same member who
        // started it — otherwise one member could bind another's SCA session to a connection.
        if (auth.holderUserId != by.id) throw AppException.forbidden("BANK_AUTH_STATE_MISMATCH")

        val session = try {
            connector.completeAuthorization(creds, request.code)
        } catch (ex: BankConnectorException) {
            throw AppException.badRequest("BANK_PROVIDER_ERROR", ex.message ?: "")
        }

        val connection = auth.relinkConnectionId
            // Re-check rather than trust the start-link decision: roles can change mid-flow, and the
            // bank redirect can come back days later.
            ?.let { manageable(householdId, it, by, role) }
            ?: BankConnection(
                householdId = householdId,
                aspspName = auth.aspspName,
                aspspCountry = auth.aspspCountry,
                label = auth.label ?: auth.aspspName,
                holderUserId = auth.holderUserId,
                createdByUserId = by.id,
            )
        connection.sessionIdEnc = crypto.encrypt(session.sessionId)
        // On a re-link this deliberately overwrites — that is how a "credentials changed"
        // connection is repaired.
        connection.appId = creds.appId
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
        return connection.toDto(canManage(connection, by, role), creds.appId)
    }

    @Transactional
    fun update(
        householdId: UUID,
        id: UUID,
        request: UpdateConnectionRequest,
        by: User,
        role: HouseholdRole,
    ): BankConnectionDto {
        val connection = manageable(householdId, id, by, role)
        request.label?.let { connection.label = it }
        request.ingestionEnabled?.let { connection.ingestionEnabled = it }
        request.syncFrequency?.let { connection.syncFrequency = it }
        connection.updatedByUserId = by.id
        return connection.toDto(canManage = true, configuredAppId = credentials.findRow(householdId)?.appId)
    }

    @Transactional
    fun delete(householdId: UUID, id: UUID, by: User, role: HouseholdRole) {
        val connection = manageable(householdId, id, by, role)
        // Cascades remove accounts, pending movements, and sync runs (FK ON DELETE CASCADE).
        connections.delete(connection)
    }

    private fun maskIban(iban: String?): String? {
        val trimmed = iban?.replace(" ", "") ?: return null
        if (trimmed.length <= 4) return trimmed
        return "••••${trimmed.takeLast(4)}"
    }

    private fun BankConnection.toDto(canManage: Boolean, configuredAppId: String?): BankConnectionDto {
        val lastRun = syncRuns.findFirstByConnectionIdOrderByStartedAtDesc(id)
        return BankConnectionDto(
            id = id,
            provider = provider,
            aspspName = aspspName,
            aspspCountry = aspspCountry,
            label = label,
            status = effectiveStatus(this, configuredAppId),
            consentExpiresAt = consentExpiresAt,
            lastSyncedAt = lastSyncedAt,
            ingestionEnabled = ingestionEnabled,
            syncFrequency = syncFrequency,
            accounts = accounts.findAllByConnectionId(id).map {
                BankAccountDto(id = it.id, ibanMasked = it.ibanMasked, name = it.name, currency = it.currency)
            },
            lastSyncStatus = lastRun?.status,
            lastSyncError = lastRun?.errorMessage,
            canManage = canManage,
        )
    }
}
