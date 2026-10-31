package pl.investor.outcome

import java.time.LocalDate

data class Outcome(
    val baseDate: LocalDate,
    val reactionPct: Double?,
    val reactionIndexPct: Double?,
    val volumeMultiple: Double?,
    val return5Pct: Double?,
    val index5Pct: Double?,
    val return20Pct: Double?,
    val index20Pct: Double?,
    val return60Pct: Double?,
    val index60Pct: Double?,
)
