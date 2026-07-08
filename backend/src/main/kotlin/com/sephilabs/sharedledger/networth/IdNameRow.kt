package com.sephilabs.sharedledger.networth

import java.util.UUID

/** Native-query projection for resolving an entity id to its display name (including soft-deleted rows). */
interface IdNameRow {
    val id: UUID
    val name: String
}
