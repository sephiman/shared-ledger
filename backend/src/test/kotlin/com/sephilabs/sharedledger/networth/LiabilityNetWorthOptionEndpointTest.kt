package com.sephilabs.sharedledger.networth

import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.csv.LiabilityImportService
import com.sephilabs.sharedledger.networth.csv.NamedCsvExportService
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import com.sephilabs.sharedledger.networth.snapshot.AutoSnapshotService
import com.sephilabs.sharedledger.networth.snapshot.SnapshotFrequency
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRepository
import com.sephilabs.sharedledger.networth.snapshot.SnapshotService
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.UUID

class LiabilityNetWorthOptionEndpointTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val users: UserRepository,
    private val liabilities: LiabilityRepository,
    private val autoSnapshots: AutoSnapshotService,
    private val snapshots: SnapshotRepository,
    private val snapshotService: SnapshotService,
    private val exportService: NamedCsvExportService,
    private val liabilityImport: LiabilityImportService,
) : AuthHttpTestBase() {

    private val allAssetCodes = listOf("cash", "fund", "etfs", "stocks", "crypto", "pension")

    @Test
    fun `a new snapshot excludes a liability with countsTowardNetWorth off and includes it with the option on`() {
        val session = sessionOf(register(newEmail("liab-opt-new")))
        val household = householdOf(session)

        val countedId = createLiability(session, household, "Mortgage", countsTowardNetWorth = true)
        val excludedId = createLiability(session, household, "Credito Ele", countsTowardNetWorth = false)

        val listedLiabs = objectMapper.readTree(
            mockMvc.perform(get("/api/households/$household/liabilities").cookie(session))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString,
        )
        assertThat(listedLiabs[0]["name"].asString()).isEqualTo("Credito Ele")
        assertThat(listedLiabs[0]["countsTowardNetWorth"].asBoolean()).isFalse()
        assertThat(listedLiabs[1]["name"].asString()).isEqualTo("Mortgage")
        assertThat(listedLiabs[1]["countsTowardNetWorth"].asBoolean()).isTrue()

        val createdSnap = objectMapper.readTree(
            mockMvc.perform(
                post("/api/households/$household/snapshots").with(csrf()).cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        snapshotBody(
                            date = LocalDate.of(2026, 11, 20),
                            cash = "10000.00",
                            liabilities = mapOf(countedId to "3000.00", excludedId to "1000.00"),
                        ),
                    ),
            )
                .andExpect(status().isCreated)
                .andReturn().response.contentAsString,
        )
        assertThat(createdSnap["totalAssets"].asString()).isEqualTo("10000.00")
        assertThat(createdSnap["totalLiabilities"].asString()).isEqualTo("3000.00")
        assertThat(createdSnap["netWorth"].asString()).isEqualTo("7000.00")
        assertThat(createdSnap["liabilities"].size()).isEqualTo(2)
        assertThat(createdSnap["liabilities"][0]["liabilityName"].asString()).isEqualTo("Credito Ele")
        assertThat(createdSnap["liabilities"][0]["balance"].asString()).isEqualTo("1000.00")
        assertThat(createdSnap["liabilities"][0]["countsTowardNetWorth"].asBoolean()).isFalse()
        assertThat(createdSnap["liabilities"][1]["liabilityName"].asString()).isEqualTo("Mortgage")
        assertThat(createdSnap["liabilities"][1]["balance"].asString()).isEqualTo("3000.00")
        assertThat(createdSnap["liabilities"][1]["countsTowardNetWorth"].asBoolean()).isTrue()
    }

    @Test
    fun `toggling countsTowardNetWorth leaves every existing snapshot byte-identical`() {
        val session = sessionOf(register(newEmail("liab-opt-immut")))
        val household = householdOf(session)
        val liabId = createLiability(session, household, "Credito Ele", countsTowardNetWorth = true)

        mockMvc.perform(
            post("/api/households/$household/snapshots").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    snapshotBody(
                        date = LocalDate.of(2026, 10, 1),
                        cash = "5000.00",
                        liabilities = mapOf(liabId to "1000.00"),
                    ),
                ),
        ).andExpect(status().isCreated)

        val beforeToggleBytes = mockMvc.perform(get("/api/households/$household/snapshots").cookie(session))
            .andExpect(status().isOk)
            .andReturn().response.contentAsByteArray

        updateLiability(session, household, liabId, "Credito Ele", countsTowardNetWorth = false)

        val afterToggleOffBytes = mockMvc.perform(get("/api/households/$household/snapshots").cookie(session))
            .andExpect(status().isOk)
            .andReturn().response.contentAsByteArray

        assertThat(afterToggleOffBytes).isEqualTo(beforeToggleBytes)
    }

    @Test
    fun `a snapshot keeps its recorded counted and not-counted state after later toggles and edits`() {
        val session = sessionOf(register(newEmail("liab-opt-freeze")))
        val household = householdOf(session)
        val liabId = createLiability(session, household, "Credito Ele", countsTowardNetWorth = true)

        // Snapshot 1: liability counts.
        val snap1Id = createSnapshot(
            session,
            household,
            LocalDate.of(2026, 9, 1),
            cash = "5000.00",
            liabilities = mapOf(liabId to "1000.00"),
        )

        // Toggle OFF, take Snapshot 2: liability does not count.
        updateLiability(session, household, liabId, "Credito Ele", countsTowardNetWorth = false)
        val snap2Id = createSnapshot(
            session,
            household,
            LocalDate.of(2026, 10, 1),
            cash = "5000.00",
            liabilities = mapOf(liabId to "1000.00"),
        )

        val twoSnapshotsBeforeToggleBack = mockMvc.perform(get("/api/households/$household/snapshots").cookie(session))
            .andExpect(status().isOk)
            .andReturn().response.contentAsByteArray

        // Toggle back ON: both earlier snapshots must remain byte-identical.
        updateLiability(session, household, liabId, "Credito Ele", countsTowardNetWorth = true)

        val twoSnapshotsAfterToggleBack = mockMvc.perform(get("/api/households/$household/snapshots").cookie(session))
            .andExpect(status().isOk)
            .andReturn().response.contentAsByteArray
        assertThat(twoSnapshotsAfterToggleBack).isEqualTo(twoSnapshotsBeforeToggleBack)

        val listed = objectMapper.readTree(twoSnapshotsAfterToggleBack)
        assertThat(listed[0]["id"].asString()).isEqualTo(snap1Id.toString())
        assertThat(listed[0]["totalLiabilities"].asString()).isEqualTo("1000.00")
        assertThat(listed[0]["netWorth"].asString()).isEqualTo("4000.00")
        assertThat(listed[0]["liabilities"][0]["countsTowardNetWorth"].asBoolean()).isTrue()

        assertThat(listed[1]["id"].asString()).isEqualTo(snap2Id.toString())
        assertThat(listed[1]["totalLiabilities"].asString()).isEqualTo("0.00")
        assertThat(listed[1]["netWorth"].asString()).isEqualTo("5000.00")
        assertThat(listed[1]["liabilities"][0]["countsTowardNetWorth"].asBoolean()).isFalse()

        // Editing Snapshot 2's note must preserve its recorded countsTowardNetWorth=false even though the liability is now true.
        mockMvc.perform(
            patch("/api/households/$household/snapshots/$snap2Id").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    snapshotBody(
                        date = LocalDate.of(2026, 10, 1),
                        cash = "5000.00",
                        liabilities = mapOf(liabId to "1000.00"),
                        note = "Updated note",
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalLiabilities").value("0.00"))
            .andExpect(jsonPath("$.netWorth").value("5000.00"))
            .andExpect(jsonPath("$.liabilities[0].countsTowardNetWorth").value(false))
    }

    @Test
    fun `auto-snapshot records each liability's countsTowardNetWorth and excludes off liabilities from net worth`() {
        val session = sessionOf(register(newEmail("liab-opt-auto")))
        val household = householdOf(session)
        createLiability(
            session,
            household,
            "Credito Ele",
            countsTowardNetWorth = false,
            initialBalance = "1000.00",
            initialBalanceDate = LocalDate.of(2026, 11, 20),
        )

        val runDate = LocalDate.of(2026, 11, 20)
        assertThat(autoSnapshots.runForHousehold(household, SnapshotFrequency.daily, runDate)).isTrue()

        val snap = snapshots.findUpTo(household, runDate).first { it.snapshotDate == runDate }
        val dto = snapshotService.toDto(snap)
        assertThat(dto.totalLiabilities).isEqualByComparingTo("0.00")
        assertThat(dto.netWorth).isEqualByComparingTo("0.00")
        val row = dto.liabilities.single()
        assertThat(row.balance).isEqualByComparingTo("1000.00")
        assertThat(row.liabilityName).isEqualTo("Credito Ele")
        val json = objectMapper.readTree(objectMapper.writeValueAsString(dto))
        assertThat(json["liabilities"][0]["countsTowardNetWorth"].asBoolean()).isFalse()
    }

    @Test
    fun `export liabilities CSV includes counts_toward_net_worth and round-trips through import`() {
        val session1 = sessionOf(register(newEmail("liab-csv-src")))
        val source = householdOf(session1)
        val session2 = sessionOf(register(newEmail("liab-csv-dst")))
        val target = householdOf(session2)

        createLiability(
            session1,
            source,
            "Credito Ele",
            countsTowardNetWorth = false,
            initialBalance = "1000.00",
            initialBalanceDate = LocalDate.of(2026, 11, 20),
        )

        val csv = mockMvc.perform(get("/api/households/$source/liabilities/export.csv").cookie(session1))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(csv.lines().first()).contains("counts_toward_net_worth")
        assertThat(csv).contains("Credito Ele")
        assertThat(csv).contains("false")

        val importer = users.findByEmailIgnoreCase(
            objectMapper.readTree(me(session2).andReturn().response.contentAsString)["email"].asString(),
        )!!
        liabilityImport.execute(target, csv.byteInputStream(), importer)

        val rebuilt = liabilities.findAllByHouseholdIdOrderByNameAsc(target).single()
        assertThat(rebuilt.name).isEqualTo("Credito Ele")
        assertThat(exportService.exportLiabilities(target)).isEqualTo(csv)

        // Legacy 6-column CSV without counts_toward_net_worth still imports cleanly, defaulting to true and other_debt.
        val legacyCsv = "name;active;amortizable;charge_day;balance_date;balance\nLegacy loan;true;false;;2026-11-01;500,00\n"
        val legacyResult = liabilityImport.execute(target, legacyCsv.byteInputStream(), importer)
        assertThat(legacyResult.inserted).isEqualTo(2)
        val exportedAfterLegacy = exportService.exportLiabilities(target)
        assertThat(exportedAfterLegacy).contains("Legacy loan;other_debt;true;true;false;;2026-11-01;500,00")
    }

    private fun createLiability(
        session: Cookie,
        household: UUID,
        name: String,
        countsTowardNetWorth: Boolean,
        initialBalance: String = "1000.00",
        initialBalanceDate: LocalDate = LocalDate.now(),
    ): UUID {
        val body = """{"name":"$name","type":"other_debt","active":true,"amortizable":false,"chargeDay":null,"countsTowardNetWorth":$countsTowardNetWorth,"initialBalance":"$initialBalance","initialBalanceDate":"$initialBalanceDate"}"""
        val res = mockMvc.perform(
            post("/api/households/$household/liabilities").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(objectMapper.readTree(res)["id"].asString())
    }

    private fun updateLiability(
        session: Cookie,
        household: UUID,
        id: UUID,
        name: String,
        countsTowardNetWorth: Boolean,
    ) {
        val body = """{"name":"$name","active":true,"amortizable":false,"chargeDay":null,"countsTowardNetWorth":$countsTowardNetWorth}"""
        mockMvc.perform(
            patch("/api/households/$household/liabilities/$id").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk)
    }

    private fun createSnapshot(
        session: Cookie,
        household: UUID,
        date: LocalDate,
        cash: String,
        liabilities: Map<UUID, String>,
    ): UUID {
        val res = mockMvc.perform(
            post("/api/households/$household/snapshots").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(snapshotBody(date, cash, liabilities)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(objectMapper.readTree(res)["id"].asString())
    }

    private fun snapshotBody(
        date: LocalDate,
        cash: String,
        liabilities: Map<UUID, String>,
        note: String? = null,
    ): String {
        val assetsJson = allAssetCodes.joinToString(",") { code ->
            val v = if (code == "cash") cash else "0.00"
            """{"assetClassCode":"$code","value":"$v"}"""
        }
        val liabJson = liabilities.entries.joinToString(",") { (id, bal) ->
            """{"liabilityId":"$id","balance":"$bal"}"""
        }
        val noteJson = note?.let { "\"$it\"" } ?: "null"
        return """{"snapshotDate":"$date","note":$noteJson,"assets":[$assetsJson],"liabilities":[$liabJson],"namedAssets":[],"confirmLargeChanges":true}"""
    }

    private fun householdOf(session: Cookie): UUID =
        UUID.fromString(objectMapper.readTree(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString())
}
