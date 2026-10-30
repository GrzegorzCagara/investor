package pl.investor.scoring

import pl.investor.extraction.OutlookTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoreCalculatorTest {
    private fun allNull() = ScoringInput(
        revenueYoyPct = null, previousYoyPcts = emptyList(), sur = null, operatingProfitYoyPct = null,
        marginChangePp = null, revenueOnly = false, consecutiveStrongBefore = 0, outlook = null, pe = null,
        ttmNetProfitGrowthPct = null, ps = null, psMedian20q = null, cyclical = false, oneOff = false,
        priceChange10Pct = null, indexChange10Pct = null, lowLiquidity = false, aiUsed = true, uncertainNumbers = false,
    )

    private fun asbisLike() = allNull().copy(
        sur = 1.6, revenueYoyPct = 91.0, previousYoyPcts = listOf(89.0, 74.0, 89.0, 74.0), revenueOnly = true,
        consecutiveStrongBefore = 6, outlook = OutlookTone.POSITIVE, pe = 20.1, ttmNetProfitGrowthPct = 150.0,
        priceChange10Pct = 27.0, indexChange10Pct = 2.0,
    )

    @Test fun `growth 91 after 89 74 89 74 gets base only`() { assertEquals(16.0, growthPoints(91.0, listOf(89.0, 74.0, 89.0, 74.0)), 1e-9) }
    @Test fun `growth acceleration bonus`() { assertEquals(20.0, growthPoints(79.0, listOf(60.0, 50.0)), 1e-9) }
    @Test fun `quality revenue only is neutral`() { assertEquals(7.5, qualityPoints(91.0, null, null, revenueOnly = true), 1e-9) }
    @Test fun `quality profit growing 121 pp faster than revenue is full`() { assertEquals(15.0, qualityPoints(79.0, 200.0, null, false), 1e-9) }
    @Test fun `PEG 0,2 is full points, PEG 2 is zero`() {
        assertEquals(15.0, valuationPoints(20.1, 150.0, null, null, false), 1e-9)
        assertEquals(0.0, valuationPoints(40.0, 20.0, null, null, false), 1e-9)
    }
    @Test fun `cyclical caps valuation at 7,5`() { assertEquals(7.5, valuationPoints(5.0, 200.0, null, null, true), 1e-9) }
    @Test fun `run-up penalty only with a modest surprise`() {
        assertEquals(10.0, runUpPenalty(25.0, 2.0, 15.0), 1e-9); assertEquals(0.0, runUpPenalty(25.0, 2.0, 24.0), 1e-9)
    }
    @Test fun `series and outlook points`() {
        assertEquals(listOf(0.0, 3.0, 6.0, 10.0, 10.0), listOf(0, 1, 2, 3, 6).map { seriesPoints(it) })
        assertEquals(listOf(10.0, 5.0, 5.0, 0.0), listOf(OutlookTone.POSITIVE, OutlookTone.NONE, null, OutlookTone.NEGATIVE).map { outlookPoints(it) })
    }
    @Test fun `missing everything never reaches the threshold`() {
        val score = ScoreCalculator.score(allNull())
        assertTrue(score.total < 70.0)
        assertTrue(Warning.MISSING_DATA in score.warnings)
        assertTrue(Warning.NO_CONSENSUS in score.warnings)
    }
    @Test fun `ASBIS-like input`() {
        val score = ScoreCalculator.score(asbisLike())
        assertEquals(82.5, score.total, 1e-9)
        assertFalse(Warning.MISSING_DATA in score.warnings)
    }

    @Test fun `infinite yoy values are treated as missing`() {
        val score = ScoreCalculator.score(allNull().copy(revenueYoyPct = Double.POSITIVE_INFINITY, operatingProfitYoyPct = Double.POSITIVE_INFINITY))
        assertTrue(score.total.isFinite())
        assertTrue(Warning.MISSING_DATA in score.warnings)
    }
    @Test fun `non-finite previous yoy values are ignored`() {
        val withNan = ScoreCalculator.score(asbisLike().copy(revenueYoyPct = 100.0, previousYoyPcts = listOf(Double.NaN, 60.0, 50.0, 40.0)))
        val clean = ScoreCalculator.score(asbisLike().copy(revenueYoyPct = 100.0, previousYoyPcts = listOf(60.0, 50.0, 40.0)))
        assertEquals(20.0, withNan.components.growth, 1e-9)
        assertEquals(clean.total, withNan.total, 1e-9)
    }
}
