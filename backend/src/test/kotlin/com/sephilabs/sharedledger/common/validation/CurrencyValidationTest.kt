package com.sephilabs.sharedledger.common.validation

import com.sephilabs.sharedledger.household.HouseholdCreateRequest
import com.sephilabs.sharedledger.household.HouseholdUpdateRequest
import com.sephilabs.sharedledger.identity.auth.NewHouseholdInput
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Verifies the @ValidCurrency annotation actually fires on each DTO that takes a currency code
 * from the user. Uses a bare jakarta Validator (no Spring context) — this is the same set of
 * violations the @Valid pipeline would surface on a real HTTP request.
 */
class CurrencyValidationTest {

    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `HouseholdCreateRequest accepts valid ISO 4217 codes`() {
        listOf("EUR", "USD", "GBP", "JPY").forEach { code ->
            val errors = validator.validate(HouseholdCreateRequest(name = "X", currency = code))
            assertThat(errors).`as`("expected %s to pass", code).isEmpty()
        }
    }

    @Test
    fun `HouseholdCreateRequest accepts lowercase codes (normalized before storage)`() {
        val errors = validator.validate(HouseholdCreateRequest(name = "X", currency = "eur"))
        assertThat(errors).isEmpty()
    }

    @Test
    fun `HouseholdCreateRequest rejects non-ISO codes`() {
        listOf("ZZZ", "BTC", "AB", "EURO", "123").forEach { bogus ->
            val errors = validator.validate(HouseholdCreateRequest(name = "X", currency = bogus))
            assertThat(errors).`as`("expected %s to fail", bogus).isNotEmpty
            assertThat(errors.first().message).isEqualTo("validation.currency.invalid")
        }
    }

    @Test
    fun `HouseholdUpdateRequest allows null currency (partial update)`() {
        val errors = validator.validate(HouseholdUpdateRequest(name = null, currency = null, defaultLocale = null))
        assertThat(errors).isEmpty()
    }

    @Test
    fun `HouseholdUpdateRequest rejects bogus currency`() {
        val errors = validator.validate(HouseholdUpdateRequest(name = null, currency = "ZZZ", defaultLocale = null))
        assertThat(errors).hasSize(1)
        assertThat(errors.first().message).isEqualTo("validation.currency.invalid")
    }

    @Test
    fun `NewHouseholdInput rejects bogus currency (closes register-flow gap)`() {
        val errors = validator.validate(NewHouseholdInput(name = "X", currency = "XYZ"))
        assertThat(errors).hasSize(1)
        assertThat(errors.first().message).isEqualTo("validation.currency.invalid")
    }

    @Test
    fun `NewHouseholdInput still requires currency to be present`() {
        val errors = validator.validate(NewHouseholdInput(name = "X", currency = ""))
        // @NotBlank fires; @ValidCurrency is permissive on blank by design.
        assertThat(errors.map { it.message }).contains("validation.required")
    }
}
