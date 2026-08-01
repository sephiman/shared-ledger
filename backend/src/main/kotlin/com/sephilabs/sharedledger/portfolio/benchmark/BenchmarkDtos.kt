package com.sephilabs.sharedledger.portfolio.benchmark

import com.fasterxml.jackson.annotation.JsonFormat
import java.math.BigDecimal
import java.time.LocalDate

/** One selectable benchmark plus the extent of its stored data (for the selector UI). */
data class BenchmarkDto(
    val key: String,
    // Currency the closes are stored/quoted in; the series is always returned in EUR terms.
    val currency: String,
    val kind: BenchmarkKind,
    val hasData: Boolean,
    val availableFrom: LocalDate?,
    val availableTo: LocalDate?,
)

data class BenchmarkSeriesPointDto(
    val date: LocalDate,
    // Cumulative time-weighted return since the window's anchor, as a scale-4 fraction
    // (0.1234 = +12.34 %); null where the benchmark has no data yet (a leading gap that
    // must be shown as a gap, never faked).
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val twrPct: BigDecimal?,
)

/** A benchmark normalized to the window the user's TWR curve covers: 0 % at the anchor, one point per
 *  shared sample date, in EUR terms. [partial] marks a leading/trailing data gap, with
 *  [availableFrom]/[availableTo] bounding the covered sub-range. */
data class BenchmarkSeriesDto(
    val key: String,
    val currency: String,
    val points: List<BenchmarkSeriesPointDto>,
    val availableFrom: LocalDate?,
    val availableTo: LocalDate?,
    val partial: Boolean,
)

data class BenchmarkSeriesResponseDto(
    val series: List<BenchmarkSeriesDto>,
)
