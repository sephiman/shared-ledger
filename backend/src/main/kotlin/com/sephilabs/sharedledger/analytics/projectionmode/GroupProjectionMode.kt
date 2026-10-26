package com.sephilabs.sharedledger.analytics.projectionmode

import com.sephilabs.sharedledger.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.io.Serializable
import java.util.UUID

@Embeddable
data class GroupProjectionModeId(
    @Column(name = "household_id") var householdId: UUID = UUID.randomUUID(),
    @Column(name = "group_code") var groupCode: String = "",
) : Serializable

@Entity
@Table(name = "group_projection_modes")
class GroupProjectionModeEntity(
    @EmbeddedId
    var id: GroupProjectionModeId = GroupProjectionModeId(),

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    var mode: ProjectionMode = ProjectionMode.DEFAULT,
) : TimestampedEntity()

interface GroupProjectionModeRepository : JpaRepository<GroupProjectionModeEntity, GroupProjectionModeId> {
    fun findAllByIdHouseholdId(householdId: UUID): List<GroupProjectionModeEntity>
}
