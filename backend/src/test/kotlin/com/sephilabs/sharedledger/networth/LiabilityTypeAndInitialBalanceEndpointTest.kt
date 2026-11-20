package com.sephilabs.sharedledger.networth

import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.csv.LiabilityImportService
import com.sephilabs.sharedledger.networth.csv.NamedCsvExportService
import com.sephilabs.sharedledger.networth.liability.LiabilityBalanceEntryRepository
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
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

class LiabilityTypeAndInitialBalanceEndpointTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val users: UserRepository,
    private val liabilities: LiabilityRepository,
    private val balances: LiabilityBalanceEntryRepository,
    private val exportService: NamedCsvExportService,
    private val liabilityImport: LiabilityImportService,
) : AuthHttpTestBase() {

    @Test
    fun `creating a liability without loans stores the first value with the given date`() {
        val session = sessionOf(register(newEmail("liab-init-simple")))
        val household = householdOf(session)
        val asOf = LocalDate.now().minusDays(3)

        val res = mockMvc.perform(
            post("/api/households/$household/liabilities").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Credito Ele",
                      "type": "other_debt",
                      "active": true,
                      "countsTowardNetWorth": true,
                      "amortizable": false,
                      "initialBalance": "1000.00",
                      "initialBalanceDate": "$asOf"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Credito Ele"))
            .andExpect(jsonPath("$.type").value("other_debt"))
            .andExpect(jsonPath("$.latestBalance").value("1000.00"))
            .andExpect(jsonPath("$.latestBalanceDate").value(asOf.toString()))
            .andReturn().response.contentAsString

        val id = UUID.fromString(objectMapper.readTree(res)["id"].asString())

        mockMvc.perform(get("/api/households/$household/liabilities/$id/values").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].balance").value("1000.00"))
            .andExpect(jsonPath("$[0].balanceDate").value(asOf.toString()))

        // Future date is rejected with 400 VALIDATION_FAILED and persists no liability.
        val tomorrow = LocalDate.now().plusDays(1)
        mockMvc.perform(
            post("/api/households/$household/liabilities").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Future debt",
                      "type": "other_debt",
                      "active": true,
                      "countsTowardNetWorth": true,
                      "amortizable": false,
                      "initialBalance": "250.00",
                      "initialBalanceDate": "$tomorrow"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fields.initialBalanceDate").exists())

        assertThat(liabilities.findAllByHouseholdIdOrderByNameAsc(household)).hasSize(1)
    }

    @Test
    fun `creating a liability with loans stores no manual value and rejects manual balance entries`() {
        val session = sessionOf(register(newEmail("liab-init-amort")))
        val household = householdOf(session)

        val res = mockMvc.perform(
            post("/api/households/$household/liabilities").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Hipoteca casa",
                      "type": "mortgage",
                      "active": true,
                      "countsTowardNetWorth": true,
                      "amortizable": true,
                      "chargeDay": 5
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.type").value("mortgage"))
            .andExpect(jsonPath("$.amortizable").value(true))
            .andExpect(jsonPath("$.latestBalance").isEmpty)
            .andReturn().response.contentAsString

        val id = UUID.fromString(objectMapper.readTree(res)["id"].asString())
        assertThat(balances.findAllByLiabilityIdOrderByBalanceDateDescCreatedAtDesc(id)).isEmpty()

        // Supplying initialBalance when amortizable=true is rejected so both sources never coexist.
        mockMvc.perform(
            post("/api/households/$household/liabilities").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Conflicting loan",
                      "type": "mortgage",
                      "active": true,
                      "countsTowardNetWorth": true,
                      "amortizable": true,
                      "chargeDay": 5,
                      "initialBalance": "150000.00"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fields.initialBalance").exists())

        // Adding a manual value to an amortizable liability is also rejected.
        mockMvc.perform(
            post("/api/households/$household/liabilities/$id/values").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"balanceDate":"${LocalDate.now()}","balance":"150000.00"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `amount is required and must be positive only when loans are off`() {
        val session = sessionOf(register(newEmail("liab-init-val")))
        val household = householdOf(session)

        // Missing initialBalance with amortizable=false -> 400 VALIDATION_FAILED
        mockMvc.perform(
            post("/api/households/$household/liabilities").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"No amount","type":"other_debt","active":true,"amortizable":false}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fields.initialBalance").exists())

        // Zero initialBalance -> 400 VALIDATION_FAILED
        mockMvc.perform(
            post("/api/households/$household/liabilities").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Zero amount","type":"other_debt","active":true,"amortizable":false,"initialBalance":"0.00"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fields.initialBalance").exists())

        // Negative initialBalance -> 400 VALIDATION_FAILED
        mockMvc.perform(
            post("/api/households/$household/liabilities").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Neg amount","type":"other_debt","active":true,"amortizable":false,"initialBalance":"-10.00"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fields.initialBalance").exists())

        assertThat(liabilities.findAllByHouseholdIdOrderByNameAsc(household)).isEmpty()
    }

    @Test
    fun `type is persisted, editable, defaulted to other_debt on migration, and included in CSV export and import`() {
        val session1 = sessionOf(register(newEmail("liab-type-src")))
        val source = householdOf(session1)
        val session2 = sessionOf(register(newEmail("liab-type-dst")))
        val target = householdOf(session2)

        val userId = UUID.fromString(objectMapper.readTree(me(session1).andReturn().response.contentAsString)["id"].asString())

        // Verify the DB column default directly (as existing rows receive on migration): omitting `type` defaults to 'other_debt'.
        val insertedId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO liabilities (id, household_id, name, created_by_user_id, updated_by_user_id) VALUES (?, ?, ?, ?, ?)",
            insertedId,
            source,
            "Migrated Mortgage Name",
            userId,
            userId,
        )
        val defaultType = jdbc.queryForObject("SELECT type FROM liabilities WHERE id = ?", String::class.java, insertedId)
        assertThat(defaultType).isEqualTo("other_debt")

        // Edit the liability's type to mortgage.
        mockMvc.perform(
            patch("/api/households/$source/liabilities/$insertedId").with(csrf()).cookie(session1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Migrated Mortgage Name",
                      "type": "mortgage",
                      "active": true,
                      "countsTowardNetWorth": true,
                      "amortizable": false
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("mortgage"))

        val csv = mockMvc.perform(get("/api/households/$source/liabilities/export.csv").cookie(session1))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(csv.lines().first()).contains("type")
        assertThat(csv).contains("mortgage")

        val importer = users.findByEmailIgnoreCase(
            objectMapper.readTree(me(session2).andReturn().response.contentAsString)["email"].asString(),
        )!!
        liabilityImport.execute(target, csv.byteInputStream(), importer)

        assertThat(exportService.exportLiabilities(target)).isEqualTo(csv)
    }

    @Test
    fun `create transaction is atomic so a failure saving the initial value leaves no liability`() {
        val session = sessionOf(register(newEmail("liab-atomic")))
        val household = householdOf(session)

        // 14 integer digits overflows NUMERIC(15,2) on the second INSERT (liability_balance_entries) after the liability row flushes.
        mockMvc.perform(
            post("/api/households/$household/liabilities").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Atomic Rollback Debt",
                      "type": "other_debt",
                      "active": true,
                      "countsTowardNetWorth": true,
                      "amortizable": false,
                      "initialBalance": "99999999999999.00",
                      "initialBalanceDate": "${LocalDate.now()}"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().is4xxClientError)

        assertThat(liabilities.findAllByHouseholdIdOrderByNameAsc(household)).isEmpty()
    }

    private fun householdOf(session: Cookie): UUID =
        UUID.fromString(objectMapper.readTree(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString())
}
