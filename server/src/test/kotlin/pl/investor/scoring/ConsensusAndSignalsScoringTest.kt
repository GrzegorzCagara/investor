package pl.investor.scoring

import pl.investor.extraction.OutlookTone
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsensusAndSignalsScoringTest {

    private fun allNull() = ScoringInput(
        revenueYoyPct = null, previousYoyPcts = emptyList(), sur = null, operatingProfitYoyPct = null,
        marginChangePp = null, revenueOnly = false, consecutiveStrongBefore = 0, outlook = null, pe = null,
        ttmNetProfitGrowthPct = null, ps = null, psMedian20q = null, cyclical = false, oneOff = false,
        priceChange10Pct = null, indexChange10Pct = null, lowLiquidity = false, aiUsed = true, uncertainNumbers = false,
    )

    private fun comparison(metric: ConsensusMetric, actualMsek: String, consensusMsek: String) =
        ConsensusComparison(metric, BigDecimal(actualMsek) * THOUSAND, BigDecimal(consensusMsek) * THOUSAND, "SEK", "FactSet", 6)

    private fun consensus(ebit: Pair<String, String>?, revenue: Pair<String, String>?) = listOfNotNull(
        ebit?.let { comparison(ConsensusMetric.EBIT, it.first, it.second) },
        revenue?.let { comparison(ConsensusMetric.REVENUE, it.first, it.second) },
    )

    private fun surpriseOf(input: ScoringInput) = ScoreCalculator.score(input).components.surprise

    private fun reactions(vararg pcts: Double) =
        pcts.mapIndexed { index, pct -> PastReaction(LocalDate.of(2026, 7, 16).minusMonths(3L * index), pct) }

    @Test
    fun `Mips Q2 2026 beat on EBIT and sales gives the full surprise`() {
        val input = allNull().copy(consensus = consensus("95" to "77", "232" to "214"))

        assertEquals(30.0, surpriseOf(input), 1e-9)
    }

    @Test
    fun `Mips Q3 2026 in line with consensus gives 13,5`() {
        val input = allNull().copy(consensus = consensus("60" to "58", "215" to "216"))

        assertEquals(13.5, surpriseOf(input), 0.05)
    }

    @Test
    fun `a miss on EBIT and sales gives zero`() {
        val input = allNull().copy(consensus = consensus("80" to "100", "97" to "100"))

        assertEquals(0.0, surpriseOf(input), 1e-9)
    }

    @Test
    fun `negative EBIT consensus measures the beat against its absolute value`() {
        val smallerLoss = allNull().copy(consensus = consensus("-5" to "-10", null))
        val biggerLoss = allNull().copy(consensus = consensus("-15" to "-10", null))

        assertEquals(50.0, ebitSurprisePct(smallerLoss.consensus.single()))
        assertEquals(30.0, surpriseOf(smallerLoss), 1e-9)
        assertEquals(0.0, surpriseOf(biggerLoss), 1e-9)
    }

    @Test
    fun `surprise percentages are clamped and a tiny EBIT consensus is ignored`() {
        assertEquals(100.0, ebitSurprisePct(comparison(ConsensusMetric.EBIT, "500", "10")))
        assertEquals(-50.0, revenueSurprisePct(comparison(ConsensusMetric.REVENUE, "10", "100")))
        assertEquals(null, ebitSurprisePct(comparison(ConsensusMetric.EBIT, "5", "0.5")))
    }

    @Test
    fun `consensus replaces SUR, drops the no-consensus warning and keeps the one-off cap`() {
        val withConsensus = allNull().copy(sur = -3.0, consensus = consensus("95" to "77", "232" to "214"))
        val oneOff = withConsensus.copy(oneOff = true)

        val score = ScoreCalculator.score(withConsensus)

        assertEquals(30.0, score.components.surprise, 1e-9)
        assertFalse(Warning.NO_CONSENSUS in score.warnings)
        assertEquals(15.0, surpriseOf(oneOff), 1e-9)
        assertEquals(withConsensus.consensus, score.context.consensus)
    }

    @Test
    fun `only a useless consensus falls back to SUR with the no-consensus warning`() {
        val input = allNull().copy(sur = 2.0, consensus = consensus("5" to "0.5", null))

        val score = ScoreCalculator.score(input)

        assertEquals(30.0, score.components.surprise, 1e-9)
        assertTrue(Warning.NO_CONSENSUS in score.warnings)
    }

    @Test
    fun `trend reversal adds the growth bonus and the signal`() {
        val input = allNull().copy(revenueYoyPct = 2.0, previousYoyPcts = listOf(-1.0, -2.0, -1.0), trendReversal = true)

        val score = ScoreCalculator.score(input)

        assertEquals(16 * 0.04 + 4, score.components.growth, 1e-9)
        assertEquals(setOf(Signal.TREND_REVERSAL), score.signals)
        assertEquals(16 * 0.04, growthPoints(2.0, listOf(-1.0, -2.0, -1.0)), 1e-9)
    }

    @Test
    fun `orders up, down and missing drive the outlook component when management gives no outlook`() {
        assertEquals(10.0, outlookPoints(OutlookTone.NONE, 25.0))
        assertEquals(0.0, outlookPoints(OutlookTone.NONE, -20.0))
        assertEquals(5.0, outlookPoints(OutlookTone.NONE, 19.9))
        assertEquals(5.0, outlookPoints(OutlookTone.NONE, null))
        assertEquals(5.0, outlookPoints(null, null))
        assertEquals(10.0, outlookPoints(OutlookTone.POSITIVE, -40.0))
        assertEquals(0.0, outlookPoints(OutlookTone.NEGATIVE, 40.0))
    }

    @Test
    fun `orders up by 20 percent is a signal, less is not`() {
        assertEquals(setOf(Signal.ORDERS_UP), ScoreCalculator.score(allNull().copy(ordersYoyPct = 20.0)).signals)
        assertEquals(emptySet(), ScoreCalculator.score(allNull().copy(ordersYoyPct = 19.0)).signals)
    }

    @Test
    fun `Nelly-like stock down 66 percent YTD with two of three recent negative reactions has low expectations`() {
        val input = allNull().copy(ytdChangePct = -66.0, pastReactions = reactions(-8.0, 4.0, -12.0, 9.0))

        val score = ScoreCalculator.score(input)

        assertEquals(setOf(Signal.LOW_EXPECTATIONS), score.signals)
        assertEquals(-66.0, score.context.ytdChangePct)
        assertEquals(emptySet(), ScoreCalculator.score(input.copy(ytdChangePct = -29.0)).signals)
        assertEquals(emptySet(), ScoreCalculator.score(input.copy(pastReactions = reactions(-8.0, 4.0, 12.0))).signals)
    }

    @Test
    fun `MedCap-like six of eight positive reactions is a consistent reaction`() {
        val mips = allNull().copy(pastReactions = reactions(27.8, 8.7, -5.6, 11.2, 2.2, -7.7, 3.3, 6.6))
        val fiveOfEight = allNull().copy(pastReactions = reactions(27.8, 8.7, -5.6, 11.2, -2.2, -7.7, 3.3, 6.6))
        val olderNinth = allNull().copy(pastReactions = reactions(27.8, 8.7, -5.6, 11.2, -2.2, -7.7, 3.3, 6.6, 4.0))

        assertEquals(setOf(Signal.CONSISTENT_REACTION), ScoreCalculator.score(mips).signals)
        assertEquals(emptySet(), ScoreCalculator.score(fiveOfEight).signals)
        assertEquals(emptySet(), ScoreCalculator.score(olderNinth).signals)
    }

    private companion object {
        val THOUSAND = BigDecimal(1000)
    }
}
