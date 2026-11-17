package pl.investor.pipeline

import pl.investor.extraction.Amount
import pl.investor.extraction.ExtractedPeriod
import pl.investor.extraction.Extraction
import pl.investor.history.PeriodKind
import pl.investor.history.QuarterResult
import java.math.BigDecimal
import java.time.YearMonth

private val EARLIER_QUARTERS_MONTHS_BACK = listOf(3L, 6L)
private const val MONTHS_IN_YEAR = 12L
private val YEAR_AGO_TOLERANCE = BigDecimal("0.05")

fun Extraction.withQuarterFromNineMonths(history: List<QuarterResult>, currency: String): Extraction {
    val nineMonths = periods.firstOrNull { it.kind == PeriodKind.NINE_MONTHS && it.currency == currency && it.revenue != null } ?: return this
    if (hasQuarterEndingWith(nineMonths)) return this
    return nineMonths.lastQuarter(history)?.let { copy(periods = periods + it) } ?: this
}

private fun Extraction.hasQuarterEndingWith(nineMonths: ExtractedPeriod): Boolean =
    periods.any { it.kind == PeriodKind.QUARTER && YearMonth.from(it.periodEnd) == YearMonth.from(nineMonths.periodEnd) }

private fun ExtractedPeriod.lastQuarter(history: List<QuarterResult>): ExtractedPeriod? {
    val byMonth = history.associateBy { YearMonth.from(it.quarterEnd) }
    val end = YearMonth.from(periodEnd)
    val earlier = EARLIER_QUARTERS_MONTHS_BACK.map { byMonth[end.minusMonths(it)] }
    val earlierYearAgo = EARLIER_QUARTERS_MONTHS_BACK.map { byMonth[end.minusMonths(it + MONTHS_IN_YEAR)] }
    val revenue = revenue.minusQuarters(earlier) { it.revenue }?.takeIf { it.valueInThousands.signum() > 0 } ?: return null
    val revenueYearAgo = revenuePrevYear.minusQuarters(earlierYearAgo) { it.revenue }?.takeIf { it.valueInThousands.signum() > 0 }
    if (!revenueYearAgo.agreesWith(byMonth[end.minusMonths(MONTHS_IN_YEAR)]?.revenue)) return null
    return copy(
        kind = PeriodKind.QUARTER,
        revenue = revenue,
        revenuePrevYear = revenueYearAgo,
        operatingProfit = operatingProfit.minusQuarters(earlier) { it.operatingProfit },
        operatingProfitPrevYear = operatingProfitPrevYear.minusQuarters(earlierYearAgo) { it.operatingProfit },
        netProfit = netProfit.minusQuarters(earlier) { it.netProfit },
        netProfitPrevYear = netProfitPrevYear.minusQuarters(earlierYearAgo) { it.netProfit },
        orderIntake = null,
        orderIntakePrevYear = null,
        revenueLines = emptyList(),
    )
}

private fun Amount?.minusQuarters(quarters: List<QuarterResult?>, figure: (QuarterResult) -> BigDecimal?): Amount? {
    if (this == null) return null
    val earlier = quarters.map { quarter -> quarter?.let(figure) ?: return null }
    return Amount(
        earlier.fold(valueInThousands, BigDecimal::subtract),
        "$sourceText minus wcześniejsze kwartały ${earlier.joinToString(" i ") { it.toPlainString() }}",
    )
}

private fun Amount?.agreesWith(stored: BigDecimal?): Boolean =
    this == null || stored == null || stored.signum() <= 0 || (valueInThousands - stored).abs() <= stored * YEAR_AGO_TOLERANCE
