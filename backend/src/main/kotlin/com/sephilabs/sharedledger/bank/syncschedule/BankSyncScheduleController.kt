package com.sephilabs.sharedledger.bank.syncschedule

import com.sephilabs.sharedledger.household.RequireHouseholdOwner
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// Times as "HH:mm": what an <input type="time"> takes, and seconds never mean anything here.
data class BankSyncScheduleDto(val times: List<String>, val timezone: String, val isDefault: Boolean)

data class BankSyncScheduleRequest(val times: List<LocalTime> = emptyList(), val timezone: String = "")

@RestController
@RequestMapping("/api/households/{householdId}/banks/sync-schedule")
class BankSyncScheduleController(
    private val schedules: BankSyncScheduleService,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun get(@PathVariable householdId: UUID): BankSyncScheduleDto =
        schedules.timesFor(householdId).toDto(isDefault = !schedules.isCustom(householdId))

    @RequireHouseholdOwner
    @PutMapping
    fun update(@PathVariable householdId: UUID, @RequestBody body: BankSyncScheduleRequest): BankSyncScheduleDto =
        schedules.update(householdId, body.times, body.timezone, currentUser.requireUser().id)
            .toDto(isDefault = false)

    @RequireHouseholdOwner
    @DeleteMapping
    fun reset(@PathVariable householdId: UUID): BankSyncScheduleDto {
        schedules.resetToDefault(householdId)
        return SyncTimes.DEFAULT.toDto(isDefault = true)
    }

    private fun SyncTimes.toDto(isDefault: Boolean) =
        BankSyncScheduleDto(times = times.sorted().map { it.format(HH_MM) }, timezone = zone.id, isDefault = isDefault)

    private companion object {
        val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
