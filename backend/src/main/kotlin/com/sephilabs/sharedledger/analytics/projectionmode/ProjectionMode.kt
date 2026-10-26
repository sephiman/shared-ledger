package com.sephilabs.sharedledger.analytics.projectionmode

/** How the month projection estimates a group's discretionary spend for the month in progress. The name is the
 *  wire and storage format (V041's CHECK constraint lists the same values). */
enum class ProjectionMode {
    // The group ends the month at its historical average: whatever of it is not spent yet, never below zero.
    ENVELOPE,

    // The average spread evenly over the month: only the share of the days still ahead.
    PRORATE,
    ;

    companion object {
        // Also the mode of every group with no stored row, so the table's arrival changed no number.
        val DEFAULT: ProjectionMode = ENVELOPE
    }
}
