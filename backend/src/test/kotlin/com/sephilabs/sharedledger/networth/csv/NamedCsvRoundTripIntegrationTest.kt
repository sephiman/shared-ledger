package com.sephilabs.sharedledger.networth.csv

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.asset.Asset
import com.sephilabs.sharedledger.networth.asset.AssetRepository
import com.sephilabs.sharedledger.networth.asset.AssetType
import com.sephilabs.sharedledger.networth.asset.AssetValueEntry
import com.sephilabs.sharedledger.networth.asset.AssetValueEntryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/** Assets CSV round-trips: an export re-imports into another household faithfully and is idempotent. */
class NamedCsvRoundTripIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val assets: AssetRepository,
    private val assetValues: AssetValueEntryRepository,
    private val exportService: NamedCsvExportService,
    private val importService: AssetImportService,
) : IntegrationTestBase() {

    @Test
    fun `asset export re-imports faithfully and is idempotent`() {
        val user = users.save(User(email = "cx${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val source = households.save(Household(name = "Src", currency = "EUR", defaultLocale = "en"))
        val target = households.save(Household(name = "Dst", currency = "EUR", defaultLocale = "en"))

        val house = assets.save(Asset(householdId = source.id, name = "House", type = AssetType.property, createdByUserId = user.id, updatedByUserId = user.id))
        assetValues.save(AssetValueEntry(assetId = house.id, valueDate = LocalDate.of(2026, 1, 15), value = BigDecimal("450000.00"), createdByUserId = user.id, updatedByUserId = user.id))
        assetValues.save(AssetValueEntry(assetId = house.id, valueDate = LocalDate.of(2026, 6, 20), value = BigDecimal("500000.00"), createdByUserId = user.id, updatedByUserId = user.id))

        val csv = exportService.exportAssets(source.id)

        val first = importService.execute(target.id, csv.byteInputStream(), user)
        assertThat(first.inserted).isEqualTo(3) // 1 asset + 2 value entries

        val rebuilt = assets.findAllByHouseholdIdOrderByNameAsc(target.id).single()
        assertThat(rebuilt.name).isEqualTo("House")
        assertThat(rebuilt.type).isEqualTo(AssetType.property)
        assertThat(assetValues.findAllByAssetIdOrderByValueDateDescCreatedAtDesc(rebuilt.id)).hasSize(2)

        // Re-import the same export: nothing new is created.
        val second = importService.execute(target.id, csv.byteInputStream(), user)
        assertThat(second.inserted).isEqualTo(0)
        assertThat(second.skipped).isEqualTo(2)
        assertThat(assetValues.findAllByAssetIdOrderByValueDateDescCreatedAtDesc(rebuilt.id)).hasSize(2)
    }
}
