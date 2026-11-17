package pl.investor.context

import pl.investor.extraction.ExtractedPeriod
import pl.investor.extraction.RevenueLine
import java.math.BigDecimal

private const val MIN_LINES = 2
private const val CONCENTRATED_SHARE_OF_GROWTH = 2.0 / 3
private const val MAIN_LINE_PRIOR_SHARE = 2.0 / 3

data class ConcentratedGrowth(val line: RevenueLine, val shareOfGrowthPct: Double, val restYoyPct: Double?)

fun ExtractedPeriod.concentratedRevenueGrowth(): ConcentratedGrowth? {
    val total = revenue?.valueInThousands ?: return null
    val totalYearAgo = revenuePrevYear?.valueInThousands?.takeIf { it.signum() > 0 } ?: return null
    val growth = total - totalYearAgo
    if (growth.signum() <= 0) return null
    val lines = revenueLines.filter { it.fitsWithin(total, totalYearAgo) }
    if (lines.size < MIN_LINES) return null
    val leader = lines.maxBy { it.change() }
    val leaderYearAgo = checkNotNull(leader.revenuePrevYear).valueInThousands
    val shareOfGrowth = leader.change().toDouble() / growth.toDouble()
    val priorShare = leaderYearAgo.toDouble() / totalYearAgo.toDouble()
    if (shareOfGrowth < CONCENTRATED_SHARE_OF_GROWTH || priorShare >= MAIN_LINE_PRIOR_SHARE) return null
    val restYearAgo = totalYearAgo - leaderYearAgo
    val restYoyPct = if (restYearAgo.signum() > 0) ((total - leader.revenue.valueInThousands).toDouble() / restYearAgo.toDouble() - 1) * 100 else null
    return ConcentratedGrowth(leader, shareOfGrowth * 100, restYoyPct)
}

fun RevenueLine.yoyPct(): Double? =
    revenuePrevYear?.valueInThousands?.takeIf { it.signum() > 0 }?.let { (revenue.valueInThousands.toDouble() / it.toDouble() - 1) * 100 }

private fun RevenueLine.fitsWithin(total: BigDecimal, totalYearAgo: BigDecimal): Boolean {
    val yearAgo = revenuePrevYear?.valueInThousands ?: return false
    return yearAgo.signum() > 0 && yearAgo <= totalYearAgo && revenue.valueInThousands <= total
}

private fun RevenueLine.change(): BigDecimal = revenue.valueInThousands - checkNotNull(revenuePrevYear).valueInThousands
