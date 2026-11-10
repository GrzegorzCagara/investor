package pl.investor.scoring

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import pl.investor.history.QuarterResultRepository
import pl.investor.history.QuoteRepository
import pl.investor.history.ValuationRepository
import pl.investor.pipeline.AvanzaCompanyRefresher
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportKind
import pl.investor.report.ReportStatus
import pl.investor.settings.SettingRepository
import pl.investor.source.avanza.AvanzaClient
import pl.investor.source.avanza.AvanzaParser
import pl.investor.source.avanza.AvanzaSearchHit
import pl.investor.source.quotes.AvanzaQuotes
import pl.investor.source.quotes.YahooChartClient
import pl.investor.support.PostgresTestBase
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwedishScoringInputTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var quarterResults: QuarterResultRepository
    @Autowired lateinit var valuations: ValuationRepository
    @Autowired lateinit var quotes: QuoteRepository
    @Autowired lateinit var settings: SettingRepository
    @Autowired lateinit var assembler: ScoringInputAssembler

    private val clock = Clock.fixed(Instant.parse("2026-10-21T05:35:00Z"), ZoneOffset.UTC)
    private val avanza = mockk<AvanzaClient>()
    private val avanzaQuotes = mockk<AvanzaQuotes>(relaxed = true)
    private val publishedAt = Instant.parse("2026-10-21T05:30:00Z")

    private lateinit var refresher: AvanzaCompanyRefresher

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/se/$path")) { "missing fixture $path" }.readText()

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company, daily_quote RESTART IDENTITY CASCADE").update()
        jdbc.sql("DELETE FROM setting WHERE key LIKE 'AVANZA_%'").update()
        every { avanza.findStock("Mips AB") } returns AvanzaSearchHit("740416", "Mips", "SE", "Stockholmsbörsen")
        every { avanza.stock("740416") } returns AvanzaParser.stock(fixture("avanza-stock-mips.json"))
        every { avanza.quarters("740416") } returns AvanzaParser.quarters(fixture("avanza-analysis-mips.json"))
        refresher = AvanzaCompanyRefresher(avanza, avanzaQuotes, companies, quarterResults, valuations, quotes, settings, clock, mockk(relaxed = true))
    }

    private fun mips(): Company = companies.save(Company(null, "Mips AB", null, Market.SE, null, null, false))

    private fun report(company: Company, at: Instant = publishedAt) = Report(
        id = 1, companyId = checkNotNull(company.id), market = Market.SE, kind = ReportKind.QUARTERLY, number = "Interim report (Q1 and Q3)",
        sourceRef = "1", title = "Mips interim report Q3 2026", publishedAt = at, detectedAt = at,
        detailUrl = "https://view.news.eu.nasdaq.com/view?id=x", status = ReportStatus.NEW,
    )

    private fun q3(revenuePrevYear: String) = VerifiedExtraction(
        Extraction(
            ReportCategory.RESULTS,
            listOf(
                ExtractedPeriod(
                    kind = PeriodKind.QUARTER, periodEnd = LocalDate.parse("2026-09-30"), currency = "SEK",
                    revenue = Amount(BigDecimal("215000"), "215 MSEK"), revenuePrevYear = Amount(BigDecimal(revenuePrevYear), "135 MSEK"),
                    operatingProfit = Amount(BigDecimal("60000"), "60 MSEK"), operatingProfitPrevYear = Amount(BigDecimal("44000"), "44 MSEK"),
                    netProfit = null, netProfitPrevYear = null,
                ),
            ),
            Outlook(OutlookTone.NONE, null), false, null, false,
        ),
        emptySet(),
    )

    private fun quote(isin: String, date: String, close: String, volume: Long) =
        DailyQuote(isin, LocalDate.parse(date), BigDecimal(close), volume, BigDecimal(close) * BigDecimal(volume))

    private fun refreshedMips(refresher: AvanzaCompanyRefresher = this.refresher): Company {
        val company = mips()
        quotes.save(quote("SE0009216278", "2026-06-30", "258.2", 50_000))
        generateSequence(LocalDate.parse("2026-09-21")) { it.plusDays(1) }.takeWhile { it <= LocalDate.parse("2026-10-20") }
            .filter { it.dayOfWeek.value <= 5 }
            .forEach { quotes.save(DailyQuote("SE0009216278", it, BigDecimal("407.2"), 1_000, BigDecimal("250000"))) }
        refresher.refresh(company)
        return companies.findById(checkNotNull(company.id))
    }

    @Test
    fun `refresher identifies the company on Avanza and stores quarters with report dates and valuation prices`() {
        val company = refreshedMips()

        assertEquals("740416", company.avanzaId)
        assertEquals("SE0009216278", company.isin)
        assertEquals("MIPS", company.ticker)
        assertEquals("Large Cap Stockholm", company.listing)
        assertEquals("Mips", company.shortName)
        val stored = quarterResults.publishedBefore(checkNotNull(company.id), LocalDate.parse("2026-12-31"), 20)
        assertEquals(10, stored.size)
        val q2 = stored.single { it.quarterEnd == LocalDate.parse("2026-06-30") }
        assertEquals(0, BigDecimal("232000").compareTo(q2.revenue))
        assertEquals(0, BigDecimal("68000").compareTo(q2.netProfit))
        assertEquals(LocalDate.parse("2026-07-16"), q2.publishedOn)
        val valuation = valuations.forQuarters(checkNotNull(company.id), listOf(LocalDate.parse("2026-06-30"))).single()
        assertEquals(0, BigDecimal("258.2").compareTo(valuation.price))
        assertEquals(0, BigDecimal("41.2").compareTo(valuation.pe))
        verify(exactly = 1) { avanzaQuotes.updateIndex(Market.SE) }
        verify(exactly = 1) { avanzaQuotes.updateCompany(any()) }
    }

    @Test
    fun `matching prior-year revenue lets Avanza history feed SUR and valuation`() {
        val company = refreshedMips()

        val input = assembler.assemble(company, report(company), q3("135000"))

        assertNotNull(input.sur)
        assertEquals(215.0 / 135.0 * 100 - 100, checkNotNull(input.revenueYoyPct), 0.001)
        assertEquals(41.2 * 407.2 / 258.2, checkNotNull(input.pe), 0.001)
        assertNotNull(input.ttmNetProfitGrowthPct)
        assertFalse(Warning.MISSING_DATA in ScoreCalculator.score(input).warnings)
    }

    @Test
    fun `prior-year revenue more than 5 percent off Avanza ignores the history`() {
        val company = refreshedMips()

        val input = assembler.assemble(company, report(company), q3("150000"))

        assertNull(input.sur)
        assertNull(input.pe)
        assertNull(input.ps)
        assertEquals(0, input.consecutiveStrongBefore)
        assertTrue(input.previousYoyPcts.isEmpty())
        assertNotNull(input.revenueYoyPct)
        assertTrue(Warning.MISSING_DATA in ScoreCalculator.score(input).warnings)
    }

    @Test
    fun `run-up uses OMXSPI and liquidity is judged in SEK`() {
        val company = refreshedMips()
        generateSequence(LocalDate.parse("2026-09-21")) { it.plusDays(1) }.takeWhile { it <= LocalDate.parse("2026-10-20") }
            .filter { it.dayOfWeek.value <= 5 }
            .forEach { quotes.save(DailyQuote("OMXSPI", it, BigDecimal("1100"), 0, null)) }

        val input = assembler.assemble(company, report(company), q3("135000"))

        assertEquals(0.0, checkNotNull(input.indexChange10Pct), 0.001)
        assertTrue(input.lowLiquidity)
        assertEquals("SEK", company.quoteCurrency)
        val quotedInEuro = company.copy(quoteCurrency = "EUR")
        assertFalse(assembler.assemble(quotedInEuro, report(quotedInEuro), q3("135000")).lowLiquidity)

        generateSequence(LocalDate.parse("2026-09-21")) { it.plusDays(1) }.takeWhile { it <= LocalDate.parse("2026-10-20") }
            .filter { it.dayOfWeek.value <= 5 }
            .forEach { quotes.save(DailyQuote("SE0009216278", it, BigDecimal("407.2"), 1_000, BigDecimal("300000"))) }
        assertFalse(assembler.assemble(company, report(company), q3("135000")).lowLiquidity)
    }

    @Test
    fun `report after the Stockholm close includes today's session even when quotes were refreshed in the morning`() {
        var now = Instant.parse("2026-10-21T08:00:00Z")
        val movingClock = mockk<Clock> { every { instant() } answers { now } }
        val today = LocalDate.parse("2026-10-21")
        every { avanza.dailyQuotes("740416", "SE0009216278", any(), any()) } answers {
            listOf(quote("SE0009216278", "2026-10-21", "390.0", 2_000)).filter { !it.date.isAfter(arg<LocalDate>(3)) && !it.date.isBefore(arg<LocalDate>(2)) }
        }
        val yahoo = mockk<YahooChartClient> { every { daily(any(), any(), any(), any()) } returns emptyList() }
        val liveQuotes = AvanzaQuotes(avanza, yahoo, quotes, movingClock, companies)
        val liveRefresher = AvanzaCompanyRefresher(avanza, liveQuotes, companies, quarterResults, valuations, quotes, settings, movingClock, mockk(relaxed = true))
        val company = refreshedMips(liveRefresher)
        verify(exactly = 0) { avanza.dailyQuotes(any(), any(), any(), any()) }

        now = Instant.parse("2026-10-21T15:40:00Z")
        liveRefresher.refresh(company)
        val input = assembler.assemble(company, report(company, Instant.parse("2026-10-21T15:38:00Z")), q3("135000"))

        verify(exactly = 1) { avanza.dailyQuotes("740416", "SE0009216278", today, today) }
        assertEquals(41.2 * 390.0 / 258.2, checkNotNull(input.pe), 0.001)
    }
}
