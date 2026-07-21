package com.sephilabs.sharedledger.identity.user

import com.sephilabs.sharedledger.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class User(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "email", nullable = false, unique = true)
    var email: String,

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,

    @Column(name = "locale", nullable = false, length = 2)
    var locale: String = "en",

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,

    @Column(name = "default_household_id")
    var defaultHouseholdId: UUID? = null,

    // CSV of HomePanel ids the user chose to hide on Home; empty = all visible.
    @Column(name = "hidden_home_panels", nullable = false)
    var hiddenHomePanels: String = "",

    // Which base the portfolio return percentages are shown over; see PortfolioReturnBasis.
    @Column(name = "portfolio_return_basis", nullable = false, length = 16)
    var portfolioReturnBasis: String = PortfolioReturnBasis.DEFAULT.id,
) : TimestampedEntity()
