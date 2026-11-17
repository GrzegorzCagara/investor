package pl.investor.scoring

import pl.investor.extraction.Amount
import pl.investor.extraction.ExtractedPeriod
import pl.investor.history.CompanyForecast
import pl.investor.history.PeriodKind
import pl.investor.history.QuarterResult
import java.math.BigDecimal
import java.math.MathContext
import java.time.YearMonth
import java.time.temporal.ChronoUnit

private const val COMPANY_SOURCE = "spółki"
private const val QUARTERS_IN_YEAR = 4
private const val MONTHS_IN_QUARTER = 3L
private const val MONTHS_IN_YEAR = 12L
private const val SEASONAL_YEARS = 2L
private val PRECISION = MathContext.DECIMAL64

fun CompanyForecast.paceComparisons(reported: ExtractedPeriod, history: List<QuarterResult>): List<ConsensusComparison> {
    if (reported.currency != currency) return emptyList()
    val reportedEnd = YearMonth.from(reported.periodEnd)
    val quartersDone = quartersDoneBy(reported.kind, reportedEnd) ?: return emptyList()
    val pace = Pace(currency, reported.kind, reportedEnd, quartersDone, history.associateBy { YearMonth.from(it.quarterEnd) })
    val revenueComparison = revenue?.let { pace.compare(ConsensusMetric.REVENUE, it, reported.revenue) { quarter -> quarter.revenue } }
    val profitComparison = operatingProfit?.let { pace.compare(ConsensusMetric.EBIT, it, reported.operatingProfit) { quarter -> quarter.operatingProfit } }
        ?: netProfit?.let { pace.compare(ConsensusMetric.EBIT, it, reported.netProfit) { quarter -> quarter.netProfit } }
    return listOfNotNull(revenueComparison, profitComparison)
}

private fun CompanyForecast.quartersDoneBy(kind: PeriodKind, reportedEnd: YearMonth): Int? {
    val monthsLeft = ChronoUnit.MONTHS.between(reportedEnd, YearMonth.from(periodEnd))
    if (monthsLeft !in 0L..MONTHS_IN_YEAR - MONTHS_IN_QUARTER || monthsLeft % MONTHS_IN_QUARTER != 0L) return null
    val quartersDone = QUARTERS_IN_YEAR - (monthsLeft / MONTHS_IN_QUARTER).toInt()
    return quartersDone.takeIf {
        when (kind) {
            PeriodKind.QUARTER -> true
            PeriodKind.HALF_YEAR -> it == QUARTERS_IN_YEAR / 2
            PeriodKind.YEAR -> it == QUARTERS_IN_YEAR
            PeriodKind.MONTH, PeriodKind.NINE_MONTHS -> false
        }
    }
}

private class Pace(
    val currency: String,
    val kind: PeriodKind,
    val reportedEnd: YearMonth,
    val quartersDone: Int,
    val byMonth: Map<YearMonth, QuarterResult>,
) {
    fun compare(metric: ConsensusMetric, forecast: BigDecimal, reported: Amount?, figure: (QuarterResult) -> BigDecimal?): ConsensusComparison? {
        val yearToDate = yearToDate(reported?.valueInThousands ?: return null, figure) ?: return null
        val share = seasonalShare(figure) ?: return null
        return ConsensusComparison(metric, yearToDate.divide(share, PRECISION), forecast, currency, COMPANY_SOURCE, null, companyForecast = true)
    }

    private fun yearToDate(reported: BigDecimal, figure: (QuarterResult) -> BigDecimal?): BigDecimal? =
        if (kind == PeriodKind.QUARTER) sumOfQuarters(reportedEnd.minusMonths(MONTHS_IN_QUARTER), quartersDone - 1, figure)?.add(reported) else reported

    private fun seasonalShare(figure: (QuarterResult) -> BigDecimal?): BigDecimal? {
        if (quartersDone == QUARTERS_IN_YEAR) return BigDecimal.ONE
        val shares = (1L..SEASONAL_YEARS).mapNotNull { yearsBack -> shareOfYear(reportedEnd.minusMonths(MONTHS_IN_YEAR * yearsBack), figure) }
        if (shares.isEmpty()) return null
        return shares.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(shares.size), PRECISION)
    }

    private fun shareOfYear(partEnd: YearMonth, figure: (QuarterResult) -> BigDecimal?): BigDecimal? {
        val part = sumOfQuarters(partEnd, quartersDone, figure)?.takeIf { it.signum() > 0 } ?: return null
        val yearEnd = partEnd.plusMonths(MONTHS_IN_QUARTER * (QUARTERS_IN_YEAR - quartersDone))
        val whole = sumOfQuarters(yearEnd, QUARTERS_IN_YEAR, figure)?.takeIf { it > part } ?: return null
        return part.divide(whole, PRECISION)
    }

    private fun sumOfQuarters(lastEnd: YearMonth, count: Int, figure: (QuarterResult) -> BigDecimal?): BigDecimal? =
        (0 until count)
            .map { back -> byMonth[lastEnd.minusMonths(MONTHS_IN_QUARTER * back)]?.let(figure) ?: return null }
            .fold(BigDecimal.ZERO, BigDecimal::add)
}
