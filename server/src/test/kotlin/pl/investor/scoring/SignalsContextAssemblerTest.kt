package pl.investor.scoring

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.extraction.Amount
import pl.investor.extraction.ExtractedPeriod
import pl.investor.extraction.Extraction
import pl.investor.extraction.Outlook
import pl.investor.extraction.OutlookTone
import pl.investor.extraction.ReportCategory
import pl.investor.extraction.VerifiedExtraction
import pl.investor.history.DailyQuote
import pl.investor.history.PeriodKind
import pl.investor.history.QuarterResult
import pl.investor.history.QuarterResultRepository
import pl.investor.history.QuoteRepository
import pl.investor.outcome.Outcome
import pl.investor.outcome.OutcomeRepository
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.source.placera.ConsensusPreviewRepository
import pl.investor.source.placera.PlaceraParser
import pl.investor.support.PostgresTestBase
import pl.investor.support.toWarsawDate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalsContextAssemblerTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var quarterResults: QuarterResultRepository
    @Autowired lateinit var quotes: QuoteRepository
    @Autowired lateinit var outcomes: OutcomeRepository
    @Autowired lateinit var scores: ScoreRepository
    @Autowired lateinit var previews: ConsensusPreviewRepository
    @Autowired lateinit var assembler: ScoringInputAssembler

    private val gpwPublishedAt = Instant.parse("2026-11-20T16:30:00Z")
    private val mipsPublishedAt = Instant.parse("2026-10-21T05:30:00Z")

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company, daily_quote, consensus_preview RESTART IDENTITY CASCADE").update()
    }

    private fun company(name: String, isin: String, market: Market) = companies.save(Company(null, name, isin, market, null, null, false))

    private var nextRef = 0

    private fun report(company: Company, publishedAt: Instant, sourceRef: String = "r-${nextRef++}", kind: ReportKind = ReportKind.QUARTERLY) = checkNotNull(
        reports.saveIfNew(
            Report(
                id = null, companyId = checkNotNull(company.id), market = company.market, kind = kind, number = "1/2026",
                sourceRef = sourceRef, title = null, publishedAt = publishedAt, detectedAt = publishedAt,
                detailUrl = "https://example.com/$sourceRef", status = ReportStatus.NEW,
            ),
        ),
    )

    private fun period(kind: PeriodKind, end: String, revenue: Long, revenuePrev: Long, currency: String = "PLN", operatingProfit: Long? = null) = ExtractedPeriod(
        kind = kind, periodEnd = LocalDate.parse(end), currency = currency,
        revenue = Amount(BigDecimal(revenue), "r"), revenuePrevYear = Amount(BigDecimal(revenuePrev), "rp"),
        operatingProfit = operatingProfit?.let { Amount(BigDecimal(it), "op") }, operatingProfitPrevYear = null,
        netProfit = null, netProfitPrevYear = null,
    )

    private fun verified(vararg periods: ExtractedPeriod) =
        VerifiedExtraction(Extraction(ReportCategory.RESULTS, periods.toList(), Outlook(OutlookTone.NONE, null), false, null, false), emptySet())

    private fun quarter(company: Company, end: String, revenue: Long, publishedOn: String) =
        quarterResults.save(QuarterResult(checkNotNull(company.id), LocalDate.parse(end), BigDecimal(revenue), null, null, LocalDate.parse(publishedOn)))

    private fun quote(isin: String, date: String, close: String) = quotes.save(DailyQuote(isin, LocalDate.parse(date), BigDecimal(close), 1000, null))

    private fun prevasLikeHistory(company: Company) {
        listOf("2024-09-30" to 100L, "2024-12-31" to 100L, "2025-03-31" to 100L, "2025-06-30" to 100L).forEach { (end, revenue) ->
            quarter(company, end, revenue, LocalDate.parse(end).plusDays(40).toString())
        }
        listOf("2025-09-30" to 95L, "2025-12-31" to 90L, "2026-03-31" to 92L, "2026-06-30" to 97L).forEach { (end, revenue) ->
            quarter(company, end, revenue, LocalDate.parse(end).plusDays(40).toString())
        }
    }

    @Test
    fun `quarter growing after three declining quarters is a trend reversal`() {
        val prevas = company("PREVAS", "PL0000000001", Market.GPW)
        prevasLikeHistory(prevas)

        val input = assembler.assemble(prevas, report(prevas, gpwPublishedAt), verified(period(PeriodKind.QUARTER, "2026-09-30", 110, 95)))

        assertTrue(input.trendReversal)
        assertTrue(Signal.TREND_REVERSAL in ScoreCalculator.score(input).signals)
    }

    @Test
    fun `missing preceding quarter, half-year and monthly primaries never get the reversal`() {
        val prevas = company("PREVAS", "PL0000000001", Market.GPW)
        prevasLikeHistory(prevas)
        val report = report(prevas, gpwPublishedAt)

        val halfYear = assembler.assemble(prevas, report, verified(period(PeriodKind.HALF_YEAR, "2026-09-30", 110, 95)))
        val monthly = assembler.assemble(prevas, report, verified(period(PeriodKind.MONTH, "2026-10-31", 40, 30)))
        jdbc.sql("DELETE FROM quarter_result WHERE quarter_end = '2025-12-31'").update()
        val gap = assembler.assemble(prevas, report, verified(period(PeriodKind.QUARTER, "2026-09-30", 110, 95)))

        assertFalse(halfYear.trendReversal)
        assertFalse(monthly.trendReversal)
        assertFalse(gap.trendReversal)
    }

    @Test
    fun `GPW past reactions come from report dates and quotes, own outcomes override them, YTD from the last close of last year`() {
        val company = company("ACME", "PL0000000002", Market.GPW)
        quarter(company, "2026-03-31", 100, "2026-05-20")
        quarter(company, "2026-06-30", 100, "2026-08-27")
        quote("PL0000000002", "2025-12-30", "50")
        quote("PL0000000002", "2026-05-19", "40")
        quote("PL0000000002", "2026-05-20", "41")
        quote("PL0000000002", "2026-05-21", "44")
        quote("PL0000000002", "2026-08-26", "60")
        quote("PL0000000002", "2026-08-28", "57")
        quote("PL0000000002", "2026-11-19", "30")
        quote("PL0000000002", "2026-11-20", "35")
        val earlier = report(company, Instant.parse("2026-08-27T15:30:00Z"), "own")
        scores.save(checkNotNull(earlier.id), ScoreCalculator.score(ScoringInputFixtures.empty()), "r", earlier.publishedAt)
        outcomes.save(checkNotNull(earlier.id), Outcome(LocalDate.parse("2026-08-28"), -7.0, null, null, null, null, null, null, null, null), earlier.publishedAt)
        jdbc.sql("UPDATE report SET status = 'SCORED' WHERE id = :id").param("id", earlier.id).update()

        val input = assembler.assemble(company, report(company, gpwPublishedAt), null)

        assertEquals(listOf(LocalDate.parse("2026-08-27"), LocalDate.parse("2026-05-20")), input.pastReactions.map { it.date })
        assertEquals(-7.0, input.pastReactions[0].pct)
        assertEquals(10.0, input.pastReactions[1].pct, 1e-9)
        assertEquals(-30.0, checkNotNull(input.ytdChangePct), 1e-9)
    }

    private fun scoredWithReaction(company: Company, publishedAt: Instant, reactionPct: Double, kind: ReportKind) {
        val own = report(company, publishedAt, kind = kind)
        scores.save(checkNotNull(own.id), ScoreCalculator.score(ScoringInputFixtures.empty()), "r", publishedAt)
        outcomes.save(checkNotNull(own.id), Outcome(publishedAt.toWarsawDate(), reactionPct, null, null, null, null, null, null, null, null), publishedAt)
        jdbc.sql("UPDATE report SET status = 'SCORED' WHERE id = :id").param("id", own.id).update()
    }

    @Test
    fun `own outcomes of periodic reports override the computed day within two days and monthly sales reports never add days`() {
        val company = company("ASBIS", "PL0000000005", Market.GPW)
        quarter(company, "2026-06-30", 100, "2026-08-06")
        quote("PL0000000005", "2026-08-05", "10")
        quote("PL0000000005", "2026-08-07", "11")
        scoredWithReaction(company, Instant.parse("2026-08-07T05:00:00Z"), -4.0, ReportKind.HALF_YEARLY)
        scoredWithReaction(company, Instant.parse("2026-09-21T06:00:00Z"), 9.0, ReportKind.CURRENT)
        scoredWithReaction(company, Instant.parse("2026-05-14T15:30:00Z"), 3.0, ReportKind.QUARTERLY)

        val input = assembler.assemble(company, report(company, gpwPublishedAt), null)

        assertEquals(listOf(PastReaction(LocalDate.parse("2026-08-06"), -4.0), PastReaction(LocalDate.parse("2026-05-14"), 3.0)), input.pastReactions)
    }

    @Test
    fun `short Placera reaction table is filled with computed report days it does not contain`() {
        val coffee = company("Coffee Stain Group AB", "SE0026599557", Market.SE)
        previews.save(PlaceraParser.article(fixture("placera-coffee-stain-q2-2026.html")).toPreview("https://placera/coffee", "SE0026599557", Instant.parse("2026-11-02T15:00:00Z")))
        quarter(coffee, "2026-06-30", 100, "2026-08-06")
        quarter(coffee, "2026-03-31", 100, "2026-02-12")
        listOf("2026-08-05" to "100", "2026-08-06" to "90", "2026-02-11" to "100", "2026-02-12" to "105").forEach { (date, close) -> quote("SE0026599557", date, close) }

        val input = assembler.assemble(coffee, report(coffee, Instant.parse("2026-11-05T07:00:00Z")), null)

        assertEquals(listOf(LocalDate.parse("2026-08-05"), LocalDate.parse("2026-05-12"), LocalDate.parse("2026-02-12")), input.pastReactions.map { it.date })
        assertEquals(listOf(-2.0, 3.8), input.pastReactions.take(2).map { it.pct })
        assertEquals(5.0, input.pastReactions[2].pct, 1e-9)
    }

    @Test
    fun `Swedish report day reaction is the close on the report day vs the previous close`() {
        val company = company("Opter AB", "SE0000000003", Market.SE)
        quarter(company, "2026-06-30", 100, "2026-07-15")
        quote("SE0000000003", "2026-07-14", "100")
        quote("SE0000000003", "2026-07-15", "112")
        quote("SE0000000003", "2026-07-16", "90")

        val input = assembler.assemble(company, report(company, mipsPublishedAt), null)

        assertEquals(listOf(LocalDate.parse("2026-07-15")), input.pastReactions.map { it.date })
        assertEquals(12.0, input.pastReactions.single().pct, 1e-9)
    }

    @Test
    fun `matching Placera preview gives consensus comparisons and Placera reactions`() {
        val mips = company("Mips AB", "SE0009216278", Market.SE)
        previews.save(PlaceraParser.article(fixture("placera-mips-q3-2026.html")).toPreview("https://placera/mips", "SE0009216278", Instant.parse("2026-10-19T12:00:00Z")))
        val q3 = verified(period(PeriodKind.QUARTER, "2026-09-30", 215_000, 135_000, "SEK", operatingProfit = 60_000))

        val input = assembler.assemble(mips, report(mips, mipsPublishedAt), q3)

        assertEquals(setOf(ConsensusMetric.REVENUE, ConsensusMetric.EBIT), input.consensus.map { it.metric }.toSet())
        assertEquals(13.5, ScoreCalculator.score(input).components.surprise, 0.05)
        assertEquals(8, input.pastReactions.size)
        assertEquals(27.8, input.pastReactions.first().pct)
        assertTrue(Signal.CONSISTENT_REACTION in ScoreCalculator.score(input).signals)
    }

    @Test
    fun `preview for another date, published after the report or in another currency is not used`() {
        val mips = company("Mips AB", "SE0009216278", Market.SE)
        previews.save(PlaceraParser.article(fixture("placera-mips-q3-2026.html")).toPreview("https://placera/mips", "SE0009216278", Instant.parse("2026-10-19T12:00:00Z")))
        val q3 = verified(period(PeriodKind.QUARTER, "2026-09-30", 215_000, 135_000, "SEK", operatingProfit = 60_000))
        val inEuro = verified(period(PeriodKind.QUARTER, "2026-09-30", 21_500, 13_500, "EUR", operatingProfit = 6_000))

        assertTrue(assembler.assemble(mips, report(mips, Instant.parse("2026-10-24T05:30:00Z")), q3).consensus.isEmpty())
        assertNull(previews.forReport("SE0009216278", LocalDate.parse("2026-10-20"), Instant.parse("2026-10-19T11:00:00Z")))
        assertTrue(assembler.assemble(mips, report(mips, mipsPublishedAt), inEuro).consensus.isEmpty())
        assertNull(assembler.assemble(mips, report(mips, mipsPublishedAt), null).ordersYoyPct)
    }

    @Test
    fun `orders y-y come from the primary period`() {
        val company = company("C-RAD", "SE0000000004", Market.SE)
        val withOrders = period(PeriodKind.QUARTER, "2026-03-31", 100, 90, "SEK")
            .copy(orderIntake = Amount(BigDecimal(150), "o"), orderIntakePrevYear = Amount(BigDecimal(100), "op"))

        val input = assembler.assemble(company, report(company, mipsPublishedAt), verified(withOrders))

        assertEquals(50.0, checkNotNull(input.ordersYoyPct), 1e-9)
        assertTrue(Signal.ORDERS_UP in ScoreCalculator.score(input).signals)
        val ordersOnlyInHalfYear = verified(period(PeriodKind.QUARTER, "2026-03-31", 100, 90, "SEK"), withOrders.copy(kind = PeriodKind.HALF_YEAR))
        assertNull(assembler.assemble(company, report(company, mipsPublishedAt), ordersOnlyInHalfYear).ordersYoyPct)
    }

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/se/$path")).readText()
}

object ScoringInputFixtures {
    fun empty() = ScoringInput(
        revenueYoyPct = null, previousYoyPcts = emptyList(), sur = null, operatingProfitYoyPct = null,
        marginChangePp = null, revenueOnly = false, consecutiveStrongBefore = 0, outlook = null, pe = null,
        ttmNetProfitGrowthPct = null, ps = null, psMedian20q = null, cyclical = false, oneOff = false,
        priceChange10Pct = null, indexChange10Pct = null, lowLiquidity = false, aiUsed = true, uncertainNumbers = false,
    )
}
