package pl.investor.history

import java.math.BigDecimal
import java.time.YearMonth

const val TRAILING_YEAR_QUARTERS = 4
private const val MONTHS_IN_THREE_QUARTERS = 9L

fun List<QuarterResult>.trailingYearRevenue(): BigDecimal? = trailingYearSum { it.revenue }

fun List<QuarterResult>.trailingYearRevenueOrZero(): BigDecimal? = trailingYearSum { it.revenue ?: BigDecimal.ZERO }

fun List<QuarterResult>.trailingYearNetProfit(): BigDecimal? = trailingYearSum { it.netProfit }

fun List<QuarterResult>.trailingYearOperatingResult(): BigDecimal? = trailingYearSum { it.operatingProfit ?: it.netProfit }

private fun List<QuarterResult>.trailingYearSum(value: (QuarterResult) -> BigDecimal?): BigDecimal? {
    val latest = sortedByDescending { it.quarterEnd }.take(TRAILING_YEAR_QUARTERS)
    if (latest.size < TRAILING_YEAR_QUARTERS || latest.any { value(it) == null }) return null
    val span = YearMonth.from(latest.last().quarterEnd).plusMonths(MONTHS_IN_THREE_QUARTERS)
    if (span != YearMonth.from(latest.first().quarterEnd)) return null
    return latest.sumOf { checkNotNull(value(it)) }
}
