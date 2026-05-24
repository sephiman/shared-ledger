package com.sharedledger.fire

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface FireSettingsRepository : JpaRepository<FireSettings, UUID>
