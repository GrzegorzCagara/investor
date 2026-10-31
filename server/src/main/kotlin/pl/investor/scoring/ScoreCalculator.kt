package pl.investor.scoring

import pl.investor.extraction.OutlookTone
import kotlin.math.min

private const val NEUTRAL_QUALITY = 7.5
private const val NEUTRAL_VALUATION = 7.5
private const val NEUTRAL_SURPRISE = 15.0
private const val CONFIRMED_SERIES = 3

private fun unit(value: Double) = value.coerceIn(0.0, 1.0)

fun surprisePoints(sur: Double?): Double = sur?.let { 30 * unit(it / 2.0) } ?: 15.0

fun growthPoints(yoy: Double?, previous: List<Double>): Double {
    if (yoy == null) return 10.0
    val accelerating = previous.isNotEmpty() && yoy - previous.take(4).average() >= 10
    return 16 * unit(yoy / 50) + if (accelerating) 4 else 0
}

fun qualityPoints(revenueYoy: Double?, operatingYoy: Double?, marginChangePp: Double?, revenueOnly: Boolean): Double = when {
    revenueOnly -> NEUTRAL_QUALITY
    revenueYoy != null && operatingYoy != null -> 15 * unit(0.5 + (operatingYoy - revenueYoy) / 100)
    marginChangePp != null -> 15 * unit(0.5 + marginChangePp / 10)
    else -> NEUTRAL_QUALITY
}

private fun qualityIsKnown(input: ScoringInput) = input.revenueOnly ||
    (input.revenueYoyPct != null && input.operatingProfitYoyPct != null) || input.marginChangePp != null

fun seriesPoints(consecutiveStrong: Int): Double = when {
    consecutiveStrong <= 0 -> 0.0
    consecutiveStrong == 1 -> 3.0
    consecutiveStrong == 2 -> 6.0
    else -> 10.0
}

fun outlookPoints(tone: OutlookTone?): Double = when (tone) {
    OutlookTone.POSITIVE -> 10.0
    OutlookTone.NEGATIVE -> 0.0
    OutlookTone.NONE, null -> 5.0
}

private fun hasPeg(pe: Double?, growth: Double?) = pe != null && growth != null && pe > 0 && growth > 0

private fun hasPsRatio(ps: Double?, psMedian: Double?) = ps != null && psMedian != null && psMedian > 0

fun valuationPoints(pe: Double?, growth: Double?, ps: Double?, psMedian: Double?, cyclical: Boolean): Double {
    val points = when {
        hasPeg(pe, growth) -> 15 * unit((2.0 - pe!! / min(growth!!, 100.0)) / 1.5)
        hasPsRatio(ps, psMedian) -> 15 * unit(2.0 - ps!! / psMedian!!)
        else -> NEUTRAL_VALUATION
    }
    return if (cyclical) min(points, NEUTRAL_VALUATION) else points
}

fun hasRunUp(priceChangePct: Double?, indexChangePct: Double?): Boolean =
    priceChangePct != null && indexChangePct != null && priceChangePct - indexChangePct >= 15

private fun isConfirmedCheapGrowth(consecutiveStrong: Int, valuationPoints: Double) =
    consecutiveStrong >= CONFIRMED_SERIES && valuationPoints > NEUTRAL_VALUATION

fun runUpPenalty(priceChangePct: Double?, indexChangePct: Double?, surprisePoints: Double, consecutiveStrong: Int, valuationPoints: Double): Double =
    if (hasRunUp(priceChangePct, indexChangePct) && surprisePoints < 20 && !isConfirmedCheapGrowth(consecutiveStrong, valuationPoints)) 10.0 else 0.0

fun oneOffSurprisePoints(surprisePoints: Double, oneOff: Boolean): Double =
    if (oneOff) min(surprisePoints, NEUTRAL_SURPRISE) else surprisePoints

object ScoreCalculator {
    fun score(raw: ScoringInput): Score {
        val input = raw.finiteOnly()
        val components = ScoreComponents(
            surprise = oneOffSurprisePoints(surprisePoints(input.sur), input.oneOff),
            growth = growthPoints(input.revenueYoyPct, input.previousYoyPcts),
            quality = qualityPoints(input.revenueYoyPct, input.operatingProfitYoyPct, input.marginChangePp, input.revenueOnly),
            series = seriesPoints(input.consecutiveStrongBefore),
            outlook = outlookPoints(input.outlook),
            valuation = valuationPoints(input.pe, input.ttmNetProfitGrowthPct, input.ps, input.psMedian20q, input.cyclical),
        )
        val penalty = runUpPenalty(input.priceChange10Pct, input.indexChange10Pct, components.surprise, input.consecutiveStrongBefore, components.valuation)
        return Score((components.sum - penalty).coerceIn(0.0, 100.0), components, penalty, warnings(input))
    }

    private fun Double?.finiteOrNull() = takeIf { it != null && it.isFinite() }

    private fun ScoringInput.finiteOnly() = copy(
        revenueYoyPct = revenueYoyPct.finiteOrNull(),
        previousYoyPcts = previousYoyPcts.filter { it.isFinite() },
        sur = sur.finiteOrNull(),
        operatingProfitYoyPct = operatingProfitYoyPct.finiteOrNull(),
        marginChangePp = marginChangePp.finiteOrNull(),
        pe = pe.finiteOrNull(),
        ttmNetProfitGrowthPct = ttmNetProfitGrowthPct.finiteOrNull(),
        ps = ps.finiteOrNull(),
        psMedian20q = psMedian20q.finiteOrNull(),
        priceChange10Pct = priceChange10Pct.finiteOrNull(),
        indexChange10Pct = indexChange10Pct.finiteOrNull(),
    )

    private fun hasMissingData(input: ScoringInput) = input.sur == null || input.revenueYoyPct == null ||
        !qualityIsKnown(input) ||
        !(hasPeg(input.pe, input.ttmNetProfitGrowthPct) || hasPsRatio(input.ps, input.psMedian20q))

    private fun warnings(input: ScoringInput): Set<Warning> = buildSet {
        add(Warning.NO_CONSENSUS)
        if (input.oneOff) add(Warning.ONE_OFF_QUARTER)
        if (input.cyclical) add(Warning.CYCLICAL_INDUSTRY)
        if (input.lowLiquidity) add(Warning.LOW_LIQUIDITY)
        if (hasMissingData(input)) add(Warning.MISSING_DATA)
        if (!input.aiUsed) add(Warning.NO_AI)
        if (input.uncertainNumbers) add(Warning.UNCERTAIN_NUMBERS)
        if (hasRunUp(input.priceChange10Pct, input.indexChange10Pct)) add(Warning.RUN_UP)
    }
}
