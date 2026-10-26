package com.sephilabs.sharedledger.analytics.projectionmode

import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.common.errors.AppException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** A household's expense-group modes; a group without a stored row reads as the default. */
class GroupModes(private val stored: Map<String, ProjectionMode>) {
    fun of(groupCode: String): ProjectionMode = stored[groupCode] ?: ProjectionMode.DEFAULT
}

data class GroupProjectionModeDto(val groupCode: String, val mode: ProjectionMode)

@Service
class GroupProjectionModeService(
    private val repository: GroupProjectionModeRepository,
    private val categories: CategoryService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun modes(householdId: UUID): GroupModes =
        GroupModes(repository.findAllByIdHouseholdId(householdId).associate { it.id.groupCode to it.mode })

    @Transactional(readOnly = true)
    fun list(householdId: UUID): List<GroupProjectionModeDto> {
        val modes = modes(householdId)
        return categories.expenseGroups().sorted().map { GroupProjectionModeDto(it, modes.of(it)) }
    }

    @Transactional
    fun set(householdId: UUID, groupCode: String, mode: ProjectionMode): GroupProjectionModeDto {
        if (groupCode !in categories.expenseGroups()) throw AppException.badRequest("INVALID_PARAMETER", "groupCode")
        val id = GroupProjectionModeId(householdId, groupCode)
        val entity = repository.findById(id).orElseGet { GroupProjectionModeEntity(id) }
        val modeBefore = entity.mode
        entity.mode = mode
        repository.save(entity)
        log.info("group_projection_mode_set group={} mode={}->{}", groupCode, modeBefore, mode)
        return GroupProjectionModeDto(groupCode, mode)
    }
}
