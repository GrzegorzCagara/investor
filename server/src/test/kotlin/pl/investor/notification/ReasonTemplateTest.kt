package pl.investor.notification

import pl.investor.extraction.OutlookTone
import pl.investor.report.Company
import pl.investor.report.Market
import pl.investor.scoring.Score
import pl.investor.scoring.ScoreCalculator
import pl.investor.scoring.ScoringInput
import kotlin.test.Test
import kotlin.test.assertEquals

class ReasonTemplateTest {

    private val template = ReasonTemplate()
    private val asbis = Company(1, "ASBIS ENTERPRISES PLC", "CY1000031710", Market.GPW, "ASB", "ASBISC", true)

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

    private fun scoreOf(input: ScoringInput): Score = ScoreCalculator.score(input)

    @Test
    fun `reason template for ASBIS-like input`() {
        assertEquals(
            "Przychody +91% r/r; wyraźnie ponad dotychczasowy trend; 7. mocny raport z rzędu; zarząd zapowiada dalsze wzrosty; C/Z 20,1 przy wzroście zysku +150%.",
            template.render(asbis, asbisLike(), scoreOf(asbisLike())),
        )
    }

    @Test
    fun `negative surprise, operating profit and run-up penalty are described`() {
        val input = allNull().copy(
            revenueYoyPct = -12.4, sur = -0.5, operatingProfitYoyPct = -30.0, outlook = OutlookTone.NEGATIVE,
            priceChange10Pct = 27.0, indexChange10Pct = 2.0,
        )
        val score = Score(40.0, scoreOf(input).components, 10.0, emptySet())

        assertEquals(
            "Przychody -12% r/r; poniżej trendu; zysk operacyjny -30% r/r; zarząd zapowiada słabsze wyniki; kurs wzrósł o 27% w 10 sesjach przed raportem.",
            template.render(asbis, input, score),
        )
    }

    @Test
    fun `surprise between 0 and 1 is in line with the trend and a short series is not mentioned`() {
        val input = allNull().copy(revenueYoyPct = 35.0, sur = 0.4, consecutiveStrongBefore = 1)

        assertEquals("Przychody +35% r/r; zgodnie z trendem.", template.render(asbis, input, scoreOf(input)))
    }

    @Test
    fun `no data gives a neutral sentence`() {
        assertEquals("Brak wystarczających danych do uzasadnienia oceny.", template.render(asbis, allNull(), scoreOf(allNull())))
    }
}
