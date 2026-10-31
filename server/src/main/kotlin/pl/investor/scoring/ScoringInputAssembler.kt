package pl.investor.scoring

import org.springframework.stereotype.Component
import pl.investor.extraction.Amount
import pl.investor.extraction.ExtractedPeriod
import pl.investor.extraction.Extraction
import pl.investor.extraction.VerifiedExtraction
import pl.investor.history.PeriodKind
import pl.investor.history.QuarterResult
import pl.investor.history.QuarterResultRepository
import pl.investor.history.QuoteRepository
import pl.investor.history.RevenuePeriod
import pl.investor.history.RevenuePeriodRepository
import pl.investor.history.ValuationRepository
import pl.investor.history.indexInstrument
import pl.investor.report.Company
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.support.toWarsawDate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

@Component
class ScoringInputAssembler(
    private val revenuePeriods: RevenuePeriodRepository,
    private val quarterResults: QuarterResultRepository,
    private val valuations: ValuationRepository,
    private val quotes: QuoteRepository,
) {

    fun assemble(company: Company, report: Report, verified: VerifiedExtraction?): ScoringInput {
        val extraction = verified?.extraction
        val results = quarterResults.publishedBefore(checkNotNull(company.id), report.publishedAt.toWarsawDate(), VALUATION_QUARTERS)
        val primary = extraction?.primaryPeriod() ?: currentQuarter(company, report)?.toExtractedPeriod()?.withPriorYearFrom(results)
        val trend = primary?.let { trend(company, report.publishedAt, it, results) } ?: Trend.EMPTY
        val comparable = primary?.withPriorYearFrom(results)
        val market = marketData(company, report.publishedAt)
        val valuation = valuation(company, results, market.closeRef)
        return ScoringInput(
            revenueYoyPct = comparable?.let { yoyPct(it.revenue?.valueInThousands, it.revenuePrevYear?.valueInThousands) },
            previousYoyPcts = trend.previousYoyPcts,
            sur = trend.sur,
            operatingProfitYoyPct = comparable?.let { yoyPct(it.operatingProfit?.valueInThousands, it.operatingProfitPrevYear?.valueInThousands) },
            marginChangePp = comparable?.marginChangePp(),
            revenueOnly = primary?.let { it.operatingProfit == null && it.netProfit == null } ?: false,
            consecutiveStrongBefore = trend.consecutiveStrong,
            outlook = extraction?.outlook?.tone,
            pe = valuation.pe,
            ttmNetProfitGrowthPct = valuation.ttmNetProfitGrowthPct,
            ps = valuation.ps,
            psMedian20q = valuation.psMedian20q,
            cyclical = extraction?.cyclicalIndustry ?: false,
            oneOff = extraction?.oneOffQuarter ?: false,
            priceChange10Pct = market.priceChange10Pct,
            indexChange10Pct = market.indexChange10Pct,
            lowLiquidity = market.lowLiquidity,
            aiUsed = verified != null,
            uncertainNumbers = verified?.uncertainFields?.isNotEmpty() ?: false,
        )
    }

    fun currentQuarter(company: Company, report: Report): QuarterResult? {
        val reportDate = report.publishedAt.toWarsawDate()
        return quarterResults.firstPublishedBetween(checkNotNull(company.id), reportDate, reportDate.plusDays(CURRENT_QUARTER_PUBLICATION_DAYS))
    }

    private fun QuarterResult.toExtractedPeriod() = ExtractedPeriod(
        kind = PeriodKind.QUARTER,
        periodEnd = quarterEnd,
        currency = PLN,
        revenue = revenue?.let { Amount(it, BIZNESRADAR_SOURCE) },
        revenuePrevYear = null,
        operatingProfit = operatingProfit?.let { Amount(it, BIZNESRADAR_SOURCE) },
        operatingProfitPrevYear = null,
        netProfit = netProfit?.let { Amount(it, BIZNESRADAR_SOURCE) },
        netProfitPrevYear = null,
    )

    private fun ExtractedPeriod.withPriorYearFrom(results: List<QuarterResult>): ExtractedPeriod {
        if (kind != PeriodKind.QUARTER || currency != PLN) return this
        val yearAgo = results.firstOrNull { it.quarterEnd == periodEnd.minusYears(1) } ?: return this
        return copy(
            revenuePrevYear = revenuePrevYear ?: yearAgo.revenue?.let { Amount(it, BIZNESRADAR_SOURCE) },
            operatingProfitPrevYear = operatingProfitPrevYear ?: yearAgo.operatingProfit?.let { Amount(it, BIZNESRADAR_SOURCE) },
        )
    }

    private fun ExtractedPeriod.marginChangePp(): Double? {
        val revenue = revenue?.valueInThousands
        val revenuePrev = revenuePrevYear?.valueInThousands
        val profit = operatingProfit?.valueInThousands
        val profitPrev = operatingProfitPrevYear?.valueInThousands
        if (revenue == null || revenuePrev == null || profit == null || profitPrev == null) return null
        if (revenue.signum() <= 0 || revenuePrev.signum() <= 0) return null
        return (profit.toDouble() / revenue.toDouble() - profitPrev.toDouble() / revenuePrev.toDouble()) * 100
    }

    private fun trend(company: Company, publishedAt: Instant, primary: ExtractedPeriod, results: List<QuarterResult>): Trend =
        when (primary.kind) {
            PeriodKind.MONTH -> monthlyTrend(company, publishedAt, primary)
            PeriodKind.QUARTER -> quarterlyTrend(primary, results, anchored = true, withSur = primary.currency == PLN)
            PeriodKind.HALF_YEAR, PeriodKind.YEAR -> quarterlyTrend(primary, results, anchored = false, withSur = false)
        }

    private fun monthlyTrend(company: Company, publishedAt: Instant, primary: ExtractedPeriod): Trend {
        val history = revenuePeriods.publishedBefore(checkNotNull(company.id), PeriodKind.MONTH, publishedAt, HISTORY_PERIODS)
            .filter { it.currency == primary.currency }
        val observations = history.map { Observation(it.periodEnd, yoyPct(it.revenue, it.revenuePrevYear)) }
        val changes = history.mapNotNull { it.change() }
        return Trend(
            previousYoyPcts = observations.mapNotNull { it.yoyPct },
            consecutiveStrong = observations.strongStreak(anchor = primary.periodEnd, stepMonths = 1),
            sur = surOf(primary, changes),
        )
    }

    private fun quarterlyTrend(primary: ExtractedPeriod, results: List<QuarterResult>, anchored: Boolean, withSur: Boolean): Trend {
        val byEnd = results.associateBy { it.quarterEnd }
        val history = results.take(HISTORY_PERIODS)
        val observations = history.map { Observation(it.quarterEnd, yoyPct(it.revenue, byEnd[it.quarterEnd.minusYears(1)]?.revenue)) }
        val changes = history.mapNotNull { result ->
            val current = result.revenue
            val yearAgo = byEnd[result.quarterEnd.minusYears(1)]?.revenue
            if (current != null && yearAgo != null) (current - yearAgo).toDouble() else null
        }
        return Trend(
            previousYoyPcts = observations.mapNotNull { it.yoyPct },
            consecutiveStrong = observations.strongStreak(anchor = primary.periodEnd.takeIf { anchored }, stepMonths = 3),
            sur = if (withSur) surOf(primary, changes) else null,
        )
    }

    private fun surOf(primary: ExtractedPeriod, previousChanges: List<Double>): Double? {
        val current = primary.revenue?.valueInThousands ?: return null
        val yearAgo = primary.revenuePrevYear?.valueInThousands ?: return null
        return Sur.compute(current.toDouble(), yearAgo.toDouble(), previousChanges)
    }

    private fun RevenuePeriod.change(): Double? = revenuePrevYear?.let { (revenue - it).toDouble() }

    private fun List<Observation>.strongStreak(anchor: LocalDate?, stepMonths: Long): Int {
        var expected = anchor?.let { YearMonth.from(it).minusMonths(stepMonths) }
        return takeWhile { observation ->
            val month = YearMonth.from(observation.periodEnd)
            val adjacent = expected == null || month == expected
            expected = month.minusMonths(stepMonths)
            adjacent && (observation.yoyPct ?: Double.NEGATIVE_INFINITY) >= STRONG_YOY_PCT
        }.size
    }

    private fun valuation(company: Company, results: List<QuarterResult>, closeRef: BigDecimal?): Valuation {
        val points = valuations.forQuarters(checkNotNull(company.id), results.map { it.quarterEnd }).associateBy { it.quarterEnd }
        val latest = results.firstOrNull()?.let { points[it.quarterEnd] }
        val price = latest?.price
        fun rescaled(multiple: BigDecimal?): Double? =
            if (multiple != null && price != null && closeRef != null) multiple.toDouble() * closeRef.toDouble() / price.toDouble() else null
        return Valuation(
            pe = rescaled(latest?.pe),
            ps = rescaled(latest?.ps),
            psMedian20q = points.values.mapNotNull { it.ps?.toDouble() }.median(),
            ttmNetProfitGrowthPct = ttmNetProfitGrowthPct(results),
        )
    }

    private fun ttmNetProfitGrowthPct(results: List<QuarterResult>): Double? {
        val window = results.take(TTM_QUARTERS * 2)
        val profits = window.mapNotNull { it.netProfit }
        if (window.size < TTM_QUARTERS * 2 || profits.size != window.size || !window.areConsecutiveQuarters()) return null
        val current = profits.take(TTM_QUARTERS).sumOf { it }
        val previous = profits.drop(TTM_QUARTERS).sumOf { it }
        return if (previous.signum() > 0) (current.toDouble() / previous.toDouble() - 1) * 100 else null
    }

    private fun List<QuarterResult>.areConsecutiveQuarters(): Boolean =
        zipWithNext().all { (newer, older) -> YearMonth.from(newer.quarterEnd).minusMonths(3) == YearMonth.from(older.quarterEnd) }

    private fun marketData(company: Company, publishedAt: Instant): MarketData {
        val isin = company.isin ?: return MarketData.EMPTY
        val stock = quotes.closesUpTo(isin, publishedAt.toWarsawDate(), QUOTE_WINDOW)
        if (stock.none { ReferenceSession.isClosed(it.date, publishedAt) }) return MarketData.EMPTY
        val reference = ReferenceSession.lastClosedSessionBefore(publishedAt, stock.map { it.date })
        val sessions = stock.filter { it.date <= reference }
        val latest = sessions.first()
        val tenSessionsAgo = sessions.getOrNull(PRICE_CHANGE_SESSIONS)
        val turnovers = sessions.take(LIQUIDITY_SESSIONS).mapNotNull { it.turnover }
        return MarketData(
            closeRef = latest.close,
            priceChange10Pct = tenSessionsAgo?.let { changePct(latest.close, it.close) },
            indexChange10Pct = tenSessionsAgo?.let { indexChangePct(company.market, reference, it.date) },
            lowLiquidity = turnovers.isNotEmpty() && turnovers.sumOf { it }.toDouble() / turnovers.size < LOW_LIQUIDITY_TURNOVER_PLN,
        )
    }

    private fun indexChangePct(market: Market, reference: LocalDate, from: LocalDate): Double? {
        val instrument = market.indexInstrument() ?: return null
        val closes = quotes.closesUpTo(instrument, reference, QUOTE_WINDOW).associate { it.date to it.close }
        val end = closes[reference] ?: return null
        val start = closes[from] ?: return null
        return changePct(end, start)
    }

    private fun changePct(end: BigDecimal, start: BigDecimal): Double? =
        if (start.signum() > 0) (end.toDouble() / start.toDouble() - 1) * 100 else null

    private fun yoyPct(current: BigDecimal?, yearAgo: BigDecimal?): Double? =
        if (current != null && yearAgo != null && yearAgo.signum() > 0) (current.toDouble() / yearAgo.toDouble() - 1) * 100 else null

    private fun List<Double>.median(): Double? = sorted().let { sorted ->
        when {
            sorted.isEmpty() -> null
            sorted.size % 2 == 1 -> sorted[sorted.size / 2]
            else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }
    }

    private data class Observation(val periodEnd: LocalDate, val yoyPct: Double?)

    private data class Trend(val previousYoyPcts: List<Double>, val consecutiveStrong: Int, val sur: Double?) {
        companion object {
            val EMPTY = Trend(emptyList(), 0, null)
        }
    }

    private data class Valuation(val pe: Double?, val ps: Double?, val psMedian20q: Double?, val ttmNetProfitGrowthPct: Double?)

    private data class MarketData(
        val closeRef: BigDecimal?,
        val priceChange10Pct: Double?,
        val indexChange10Pct: Double?,
        val lowLiquidity: Boolean,
    ) {
        companion object {
            val EMPTY = MarketData(null, null, null, false)
        }
    }

    private companion object {
        const val PLN = "PLN"
        const val BIZNESRADAR_SOURCE = "BiznesRadar"
        const val HISTORY_PERIODS = 12
        const val CURRENT_QUARTER_PUBLICATION_DAYS = 2L
        const val VALUATION_QUARTERS = 20
        const val TTM_QUARTERS = 4
        const val STRONG_YOY_PCT = 30.0
        const val QUOTE_WINDOW = 25
        const val PRICE_CHANGE_SESSIONS = 10
        const val LIQUIDITY_SESSIONS = 20
        const val LOW_LIQUIDITY_TURNOVER_PLN = 100_000.0
    }
}

private val PERIOD_PRIORITY = listOf(PeriodKind.MONTH, PeriodKind.QUARTER, PeriodKind.HALF_YEAR, PeriodKind.YEAR)

fun Extraction.primaryPeriod(): ExtractedPeriod? =
    PERIOD_PRIORITY.firstNotNullOfOrNull { kind -> periods.firstOrNull { it.kind == kind && it.revenue != null } }
