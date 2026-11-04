package com.sephilabs.sharedledger.bank.syncschedule

import com.sephilabs.sharedledger.bank.BankConnection
import com.sephilabs.sharedledger.bank.BankConnectionRepository
import com.sephilabs.sharedledger.bank.BankCredentialsService
import com.sephilabs.sharedledger.bank.BankService
import com.sephilabs.sharedledger.bank.BankSyncRunRepository
import com.sephilabs.sharedledger.bank.BankTestKeys
import com.sephilabs.sharedledger.bank.connector.BankCrypto
import com.sephilabs.sharedledger.bank.sync.BankSyncScheduler
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

// The scheduler sweeps every ingesting connection in the DB, so it shares the fake connector with the other
// bank classes; it only ever ticks at a custom time no default-schedule household is due at.
@ResourceLock("fake-bank-connector")
class BankSyncScheduleIntegrationTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val credentials: BankCredentialsService,
    private val connections: BankConnectionRepository,
    private val syncRuns: BankSyncRunRepository,
    private val crypto: BankCrypto,
    private val bankService: BankService,
    private val schedules: BankSyncScheduleService,
    private val scheduler: BankSyncScheduler,
) : AuthHttpTestBase() {

    // --- API ----------------------------------------------------------------------------------

    @Test
    fun `a household without its own schedule reports the 07 00 and 19 00 UTC default`() {
        val session = sessionOf(register(newEmail("sched-default")))

        mockMvc.perform(get(path(householdOf(session))).cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.times[0]").value("07:00"))
            .andExpect(jsonPath("$.times[1]").value("19:00"))
            .andExpect(jsonPath("$.timezone").value("UTC"))
            .andExpect(jsonPath("$.isDefault").value(true))
    }

    @Test
    fun `an owner sets one or two times in their own zone, stored sorted`() {
        val session = sessionOf(register(newEmail("sched-set")))
        val household = householdOf(session)

        save(session, household, """{"times":["21:30","06:45"],"timezone":"Europe/Madrid"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.times[0]").value("06:45"))
            .andExpect(jsonPath("$.times[1]").value("21:30"))
            .andExpect(jsonPath("$.isDefault").value(false))

        save(session, household, """{"times":["08:00"],"timezone":"Europe/Madrid"}""").andExpect(status().isOk)

        mockMvc.perform(get(path(household)).cookie(session))
            .andExpect(jsonPath("$.times.length()").value(1))
            .andExpect(jsonPath("$.times[0]").value("08:00"))
            .andExpect(jsonPath("$.timezone").value("Europe/Madrid"))
    }

    @Test
    fun `invalid schedules are 400s with a reason and change nothing`() {
        val session = sessionOf(register(newEmail("sched-invalid")))
        val household = householdOf(session)
        val cases = mapOf(
            """{"times":[],"timezone":"UTC"}""" to "BANK_SYNC_TIMES_INVALID",
            """{"timezone":"UTC"}""" to "BANK_SYNC_TIMES_INVALID",
            """{"times":["07:00","12:00","19:00"],"timezone":"UTC"}""" to "BANK_SYNC_TIMES_INVALID",
            """{"times":["07:10"],"timezone":"UTC"}""" to "BANK_SYNC_TIMES_INVALID",
            """{"times":["07:00","08:45"],"timezone":"UTC"}""" to "BANK_SYNC_TIMES_TOO_CLOSE",
            """{"times":["23:00","00:30"],"timezone":"UTC"}""" to "BANK_SYNC_TIMES_TOO_CLOSE",
            """{"times":["07:00","07:00"],"timezone":"UTC"}""" to "BANK_SYNC_TIMES_TOO_CLOSE",
            """{"times":["07:00"],"timezone":"Mars/Olympus"}""" to "BANK_SYNC_TIMEZONE_INVALID",
            """{"times":["07:00"]}""" to "BANK_SYNC_TIMEZONE_INVALID",
        )

        for ((body, code) in cases) {
            save(session, household, body)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value(code))
        }

        assertThat(schedules.isCustom(household)).isFalse()
    }

    @Test
    fun `resetting returns the household to the default`() {
        val session = sessionOf(register(newEmail("sched-reset")))
        val household = householdOf(session)
        save(session, household, """{"times":["09:00"],"timezone":"UTC"}""").andExpect(status().isOk)

        mockMvc.perform(delete(path(household)).with(csrf()).cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isDefault").value(true))

        assertThat(schedules.timesFor(household)).isEqualTo(SyncTimes.DEFAULT)
    }

    @Test
    fun `a plain member can read the schedule but not change or reset it`() {
        val owner = sessionOf(register(newEmail("sched-owner")))
        val household = householdOf(owner)
        val memberEmail = newEmail("sched-member")
        val member = sessionOf(register(memberEmail))
        members.save(HouseholdMember(HouseholdMemberId(household, users.findByEmailIgnoreCase(memberEmail)!!.id), HouseholdRole.member))

        mockMvc.perform(get(path(household)).cookie(member)).andExpect(status().isOk)
        save(member, household, """{"times":["09:00"],"timezone":"UTC"}""").andExpect(status().isForbidden)
        mockMvc.perform(delete(path(household)).with(csrf()).cookie(member)).andExpect(status().isForbidden)

        assertThat(schedules.isCustom(household)).isFalse()
    }

    @Test
    fun `another household's schedule can be neither read nor changed`() {
        val victim = householdOf(sessionOf(register(newEmail("sched-victim"))))
        val intruder = sessionOf(register(newEmail("sched-intruder")))

        mockMvc.perform(get(path(victim)).cookie(intruder)).andExpect(status().isForbidden)
        save(intruder, victim, """{"times":["09:00"],"timezone":"UTC"}""").andExpect(status().isForbidden)
    }

    @Test
    fun `the bank config announces the household's own upcoming sync times`() {
        val (household, userId) = householdWithCredentials()
        schedules.update(household.id, listOf(LocalTime.of(10, 15)), "Europe/Madrid", userId)

        val next = bankService.config(household.id).nextSyncTimes

        assertThat(next).hasSize(3)
        assertThat(next).allSatisfy { assertThat(it.atZone(ZoneId.of("Europe/Madrid")).toLocalTime()).isEqualTo(LocalTime.of(10, 15)) }
    }

    // --- Scheduler ----------------------------------------------------------------------------

    @Test
    fun `a tick syncs the households due at it and leaves the others alone`() {
        val (due, userId) = householdWithCredentials()
        val (notDue, _) = householdWithCredentials()
        schedules.update(due.id, listOf(LocalTime.of(10, 15)), "Europe/Madrid", userId)
        val dueConnection = connections.save(syncableConnection(due))
        val defaultConnection = connections.save(syncableConnection(notDue))

        // Three minutes late, as a busy scheduler thread would be; still the 10:15 slot.
        val tick = ZonedDateTime.of(LocalDate.now(), LocalTime.of(10, 15), ZoneId.of("Europe/Madrid")).toInstant()
        scheduler.syncDue(tick.plusSeconds(180))

        assertThat(syncRuns.findFirstByConnectionIdOrderByStartedAtDesc(dueConnection.id)).isNotNull()
        assertThat(connections.findById(dueConnection.id).get().callsUsedToday).isPositive()
        assertThat(syncRuns.findFirstByConnectionIdOrderByStartedAtDesc(defaultConnection.id)).isNull()
    }

    // --- Fixtures -----------------------------------------------------------------------------

    /** A household with bank credentials, and the id of the user who configured them. */
    private fun householdWithCredentials(): Pair<Household, UUID> {
        val user = users.save(User(email = newEmail("sched-bank"), passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        credentials.save(household.id, APP_ID, BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)
        return household to user.id
    }

    private fun syncableConnection(household: Household) = BankConnection(
        householdId = household.id,
        aspspName = "ING",
        aspspCountry = "NL",
        appId = APP_ID,
        sessionIdEnc = crypto.encrypt("session-${household.id}"),
        consentExpiresAt = Instant.now().plusSeconds(86_400 * 30),
    )

    private fun save(session: Cookie, household: UUID, body: String): ResultActions =
        mockMvc.perform(put(path(household)).with(csrf()).cookie(session).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun path(household: UUID) = "/api/households/$household/banks/sync-schedule"

    private fun householdOf(session: Cookie): UUID = UUID.fromString(
        objectMapper.readTree(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString())

    private companion object {
        const val APP_ID = "test-app"
    }
}
