package pl.investor.scoring

import pl.investor.extraction.OutlookTone
import java.math.BigDecimal
import kotlin.math.min

private const val NEUTRAL_QUALITY = 7.5
private const val NEUTRAL_VALUATION = 7.5
private const val NEUTRAL_SURPRISE = 15.0
private const val NEUTRAL_GROWTH = 10.0
private const val CONFIRMED_SERIES = 3
private const val ORDERS_SIGNIFICANT_PCT = 20.0
private const val LOW_EXPECTATIONS_YTD_PCT = -30.0
private const val RECENT_REACTIONS = 3
private const val RECENT_NEGATIVE_REACTIONS = 2
private const val REACTION_HISTORY = 8
private const val CONSISTENT_REACTIONS = 6
private val MIN_EBIT_CONSENSUS = BigDecimal(1000)

private fun unit(value: Double) = value.coerceIn(0.0, 1.0)

fun surprisePoints(sur: Double?): Double = sur?.let { 30 * unit(it / 2.0) } ?: 15.0

fun consensusSurprisePoints(ebitSurprisePct: Double?, revenueSurprisePct: Double?): Double =
    30 * unit(0.4 + (ebitSurprisePct ?: 0.0) / 50 + (revenueSurprisePct ?: 0.0) / 25)

fun growthPoints(yoy: Double?, previous: List<Double>, reversal: Boolean = false): Double {
    if (yoy == null) return 10.0
    val accelerating = previous.isNotEmpty() && yoy - previous.take(4).average() >= 10
    return 16 * unit(yoy / 50) + if (accelerating || reversal) 4 else 0
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

fun outlookPoints(tone: OutlookTone?, ordersYoyPct: Double? = null): Double = when {
    tone == OutlookTone.POSITIVE -> 10.0
    tone == OutlookTone.NEGATIVE -> 0.0
    ordersYoyPct != null && ordersYoyPct >= ORDERS_SIGNIFICANT_PCT -> 10.0
    ordersYoyPct != null && ordersYoyPct <= -ORDERS_SIGNIFICANT_PCT -> 0.0
    else -> 5.0
}

fun ebitSurprisePct(comparison: ConsensusComparison?): Double? =
    comparison?.takeIf { it.consensus.abs() >= MIN_EBIT_CONSENSUS }?.differencePct()?.coerceIn(-100.0, 100.0)

fun revenueSurprisePct(comparison: ConsensusComparison?): Double? =
    comparison?.takeIf { it.consensus.signum() > 0 }?.differencePct()?.coerceIn(-50.0, 50.0)

fun signals(input: ScoringInput): Set<Signal> = buildSet {
    if (input.trendReversal) add(Signal.TREND_REVERSAL)
    if (input.ordersYoyPct != null && input.ordersYoyPct >= ORDERS_SIGNIFICANT_PCT) add(Signal.ORDERS_UP)
    if (hasLowExpectations(input.ytdChangePct, input.pastReactions)) add(Signal.LOW_EXPECTATIONS)
    if (input.pastReactions.take(REACTION_HISTORY).count { it.pct > 0 } >= CONSISTENT_REACTIONS) add(Signal.CONSISTENT_REACTION)
}

private fun hasLowExpectations(ytdChangePct: Double?, pastReactions: List<PastReaction>) =
    ytdChangePct != null && ytdChangePct <= LOW_EXPECTATIONS_YTD_PCT &&
        pastReactions.take(RECENT_REACTIONS).count { it.pct < 0 } >= RECENT_NEGATIVE_REACTIONS

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

private fun Double.atMostNeutralWhen(capped: Boolean, neutral: Double): Double = if (capped) min(this, neutral) else this

object ScoreCalculator {
    fun score(raw: ScoringInput): Score {
        val input = raw.finiteOnly()
        val consensusSurprise = input.consensusSurprisePoints()
        val ownTrendSurprise = surprisePoints(input.sur).atMostNeutralWhen(input.earlyStage || input.revenueAlreadyKnown || input.cyclical, NEUTRAL_SURPRISE)
        val components = ScoreComponents(
            surprise = consensusSurprise?.let { oneOffSurprisePoints(it, input.oneOff && !input.hasAdjustedProfitConsensus()) }
                ?: oneOffSurprisePoints(ownTrendSurprise, input.oneOff),
            growth = growthPoints(input.revenueYoyPct, input.previousYoyPcts, input.trendReversal).atMostNeutralWhen(input.earlyStage, NEUTRAL_GROWTH),
            quality = qualityPoints(input.revenueYoyPct, input.operatingProfitYoyPct, input.marginChangePp, input.revenueOnly)
                .atMostNeutralWhen(input.earlyStage || input.profitWithoutCash, NEUTRAL_QUALITY),
            series = seriesPoints(input.consecutiveStrongBefore),
            outlook = outlookPoints(input.outlook, input.ordersYoyPct),
            valuation = valuationPoints(input.pe, input.ttmNetProfitGrowthPct, input.ps, input.psMedian20q, input.cyclical),
        )
        val penalty = runUpPenalty(input.priceChange10Pct, input.indexChange10Pct, components.surprise, input.consecutiveStrongBefore, components.valuation)
        return Score(
            total = (components.sum - penalty).coerceIn(0.0, 100.0),
            components = components,
            penalty = penalty,
            warnings = warnings(input, hasConsensus = consensusSurprise != null),
            signals = signals(input),
            context = ScoreContext(input.ytdChangePct, input.ordersYoyPct, input.pastReactions, input.consensus),
        )
    }

    private fun ScoringInput.hasAdjustedProfitConsensus(): Boolean =
        consensus.firstOrNull { it.metric == ConsensusMetric.EBIT }?.takeIf { ebitSurprisePct(it) != null }?.adjustedForOneOffs == true

    private fun ScoringInput.consensusSurprisePoints(): Double? {
        val ebit = ebitSurprisePct(consensus.firstOrNull { it.metric == ConsensusMetric.EBIT })
        val revenue = revenueSurprisePct(consensus.firstOrNull { it.metric == ConsensusMetric.REVENUE }).takeUnless { cyclical }
        return if (ebit == null && revenue == null) null else consensusSurprisePoints(ebit, revenue)
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
        ordersYoyPct = ordersYoyPct.finiteOrNull(),
        ytdChangePct = ytdChangePct.finiteOrNull(),
        pastReactions = pastReactions.filter { it.pct.isFinite() },
    )

    private fun hasMissingData(input: ScoringInput, hasConsensus: Boolean) = (input.sur == null && !hasConsensus) || input.revenueYoyPct == null ||
        !qualityIsKnown(input) ||
        !(hasPeg(input.pe, input.ttmNetProfitGrowthPct) || hasPsRatio(input.ps, input.psMedian20q))

    private fun warnings(input: ScoringInput, hasConsensus: Boolean): Set<Warning> = buildSet {
        if (!hasConsensus) add(Warning.NO_CONSENSUS)
        if (input.oneOff) add(Warning.ONE_OFF_QUARTER)
        if (input.cyclical) add(Warning.CYCLICAL_INDUSTRY)
        if (input.lowLiquidity) add(Warning.LOW_LIQUIDITY)
        if (hasMissingData(input, hasConsensus)) add(Warning.MISSING_DATA)
        if (!input.aiUsed) add(Warning.NO_AI)
        if (input.uncertainNumbers) add(Warning.UNCERTAIN_NUMBERS)
        if (hasRunUp(input.priceChange10Pct, input.indexChange10Pct)) add(Warning.RUN_UP)
        if (input.earlyStage) add(Warning.EARLY_STAGE)
        if (input.pennyStock) add(Warning.PENNY_STOCK)
        if (input.revenueAlreadyKnown && !hasConsensus) add(Warning.REVENUE_ALREADY_KNOWN)
        if (input.profitWithoutCash) add(Warning.PROFIT_WITHOUT_CASH)
    }
}
