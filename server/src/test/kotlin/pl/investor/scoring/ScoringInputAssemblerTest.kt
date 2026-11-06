package pl.investor.scoring

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import io.mockk.every
import io.mockk.mockk
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.extraction.AiBudget
import pl.investor.extraction.AiPool
import pl.investor.extraction.Amount
import pl.investor.extraction.CashFlow
import pl.investor.extraction.ClaudeExtractor
import pl.investor.extraction.ExtractedPeriod
import pl.investor.extraction.Extraction
import pl.investor.extraction.Outlook
import pl.investor.extraction.OutlookTone
import pl.investor.extraction.QuoteVerifier
import pl.investor.extraction.ReportCategory
import pl.investor.extraction.VerifiedExtraction
import pl.investor.history.DailyQuote
import pl.investor.history.PeriodKind
import pl.investor.history.QuarterResult
import pl.investor.history.QuarterResultRepository
import pl.investor.history.QuoteRepository
import pl.investor.history.RevenuePeriod
import pl.investor.history.RevenuePeriodRepository
import pl.investor.history.ValuationPoint
import pl.investor.history.ValuationRepository
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.source.consensus.ConsensusNote
import pl.investor.source.consensus.ConsensusRepository
import pl.investor.source.consensus.ConsensusRow
import pl.investor.source.biznesradar.QuarterlyResultsParser
import pl.investor.source.biznesradar.ValuationParser
import pl.investor.source.content.ReportContentParser
import pl.investor.source.quotes.GpwArchiveParser
import pl.investor.support.PostgresTestBase
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoringInputAssemblerTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var quarterResults: QuarterResultRepository
    @Autowired lateinit var valuations: ValuationRepository
    @Autowired lateinit var revenuePeriods: RevenuePeriodRepository
    @Autowired lateinit var quotes: QuoteRepository
    @Autowired lateinit var assembler: ScoringInputAssembler
    @Autowired lateinit var archiveParser: GpwArchiveParser
    @Autowired lateinit var quarterlyParser: QuarterlyResultsParser
    @Autowired lateinit var valuationParser: ValuationParser
    @Autowired lateinit var quoteVerifier: QuoteVerifier
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var consensusNotes: ConsensusRepository

    private lateinit var asbis: Company

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company, daily_quote RESTART IDENTITY CASCADE").update()
        asbis = companies.save(Company(null, "ASBIS", ASBIS_ISIN, Market.GPW, "ASB", "ASBISC", true))
    }

    private fun resource(path: String) = checkNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }

    private fun text(path: String) = resource(path).readText()

    private fun bytes(path: String) = resource(path).readBytes()

    private fun rb23(): Report = Report(
        id = 1, companyId = asbis.id!!, market = Market.GPW, kind = ReportKind.CURRENT, number = "23/2026",
        sourceRef = "497297", title = "Sprzedaż", publishedAt = Instant.parse("2026-09-21T06:09:21Z"),
        detectedAt = Instant.parse("2026-09-21T06:10:00Z"), detailUrl = "https://www.gpw.pl/komunikat?geru_id=497297",
        status = ReportStatus.NEW,
    )

    private fun extract(aiFixture: String, htmlFixture: String): VerifiedExtraction {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setHeader("content-type", "application/json").setBody(text("ai/$aiFixture")))
            val budget = mockk<AiBudget>(relaxed = true).also { every { it.canSpend(any()) } returns true }
            val client = AnthropicOkHttpClient.builder()
                .baseUrl(server.url("/").toString().trimEnd('/')).apiKey("test-key").maxRetries(0).build()
            val content = ReportContentParser().parse(text("reports/$htmlFixture"))
            return quoteVerifier.verify(ClaudeExtractor(budget, client).extract(content, "ASBIS", AiPool.GPW), content.text)
        } finally {
            server.shutdown()
        }
    }

    private fun loadBiznesRadar() {
        quarterlyParser.parse(text("biznesradar/ASBISC-pl-q.html")).forEach {
            quarterResults.save(QuarterResult(asbis.id!!, it.quarterEnd, it.revenue, it.operatingProfit, it.netProfit, it.publishedOn))
        }
        valuationParser.parse(text("biznesradar/ASBISC-valuation.html")).forEach {
            valuations.save(ValuationPoint(asbis.id!!, it.quarterEnd, it.price, it.shares?.toLong(), it.priceToEarnings, it.priceToRevenue))
        }
    }

    private fun loadQuotes() {
        generateSequence(LocalDate.of(2026, 8, 1)) { it.plusDays(1) }.takeWhile { it <= LocalDate.of(2026, 9, 21) }.forEach { date ->
            javaClass.getResource("/fixtures/quotes/gpw-$date.xls")?.readBytes()?.let { xls ->
                archiveParser.parse(xls, date).singleOrNull { it.instrument == ASBIS_ISIN }?.let(quotes::save)
                archiveParser.parseIndex(bytes("index/gpw-indices-$date.xls"), date, "WIG")?.let(quotes::save)
            }
        }
    }

    private fun loadMonthlySales() {
        (2025..2026).flatMap { year -> (1..12).map { month -> "$year-${month.toString().padStart(2, '0')}" } }
            .filter { javaClass.getResource("/fixtures/ai/asbis-rb-sales-$it.json") != null }
            .forEach { publicationMonth ->
                val period = extract("asbis-rb-sales-$publicationMonth.json", "asbis-rb-sales-$publicationMonth.html").extraction.periods.single()
                revenuePeriods.save(
                    RevenuePeriod(
                        asbis.id!!, PeriodKind.MONTH, period.periodEnd, period.currency, period.revenue!!.valueInThousands.stripTrailingZeros(),
                        period.revenuePrevYear?.valueInThousands, Instant.parse("$publicationMonth-20T06:15:00Z"), null,
                    )
                )
            }
    }

    private fun loadAsbis() {
        loadBiznesRadar()
        loadQuotes()
        loadMonthlySales()
    }

    @Test
    fun `ASBIS RB 23 valuation uses 2026 Q2 scaled by the 18 September close`() {
        loadAsbis()

        val input = assembler.assemble(asbis, rb23(), extract("asbis-rb-23-2026.json", "asbis-rb-23-2026.html"))

        assertEquals(12.00 * 164.20 / 98.00, input.pe!!, 0.01)
        assertTrue(input.revenueOnly)
        assertEquals(91.0, input.revenueYoyPct!!, 1.0)
        assertTrue(input.aiUsed)
        assertFalse(input.uncertainNumbers)
        assertTrue(input.consecutiveStrongBefore >= 2)
    }

    @Test
    fun `later data is invisible`() {
        loadAsbis()
        val extraction = extract("asbis-rb-23-2026.json", "asbis-rb-23-2026.html")
        val before = assembler.assemble(asbis, rb23(), extraction)

        quarterResults.save(QuarterResult(asbis.id!!, LocalDate.of(2026, 9, 30), BigDecimal(900000), BigDecimal(30000), BigDecimal(20000), LocalDate.of(2026, 11, 5)))
        valuations.save(ValuationPoint(asbis.id!!, LocalDate.of(2026, 9, 30), BigDecimal("300.00"), 55_000_000, BigDecimal("40.00"), BigDecimal("1.50")))
        revenuePeriods.save(RevenuePeriod(asbis.id!!, PeriodKind.MONTH, LocalDate.of(2026, 9, 30), "USD", BigDecimal(100), BigDecimal(900), Instant.parse("2026-10-20T06:15:00Z"), null))
        quotes.save(DailyQuote(ASBIS_ISIN, LocalDate.of(2026, 9, 21), BigDecimal("999.00"), 1, BigDecimal("1")))
        quotes.save(DailyQuote("WIG", LocalDate.of(2026, 9, 21), BigDecimal("999.00"), 1, BigDecimal("1")))
        quotes.save(DailyQuote(ASBIS_ISIN, LocalDate.of(2026, 9, 22), BigDecimal("999.00"), 1, BigDecimal("1")))

        assertEquals(before, assembler.assemble(asbis, rb23(), extraction))
    }

    @Test
    fun `mixed currencies never feed SUR`() {
        (0 until 12).forEach { i ->
            val periodEnd = LocalDate.of(2025, 8, 31).plusMonths(i.toLong()).withDayOfMonth(1).let { it.withDayOfMonth(it.lengthOfMonth()) }
            revenuePeriods.save(
                RevenuePeriod(
                    asbis.id!!, PeriodKind.MONTH, periodEnd, "PLN", BigDecimal(1000 + 90 * i * i), BigDecimal(800 + 20 * i),
                    Instant.parse("2025-09-20T06:00:00Z").plusSeconds(i * 86_400L * 30), null,
                )
            )
        }
        fun monthly(currency: String) = VerifiedExtraction(
            Extraction(
                ReportCategory.RESULTS,
                listOf(
                    ExtractedPeriod(
                        PeriodKind.MONTH, LocalDate.of(2026, 8, 31), currency, Amount(BigDecimal(2500), "2500"), Amount(BigDecimal(1000), "1000"),
                        null, null, null, null,
                    )
                ),
                Outlook(OutlookTone.NONE, null), false, null, false,
            ),
            emptySet(),
        )

        val usd = assembler.assemble(asbis, rb23(), monthly("USD"))
        val pln = assembler.assemble(asbis, rb23(), monthly("PLN"))

        assertNull(usd.sur)
        assertTrue(usd.previousYoyPcts.isEmpty())
        assertNotNull(pln.sur)
    }

    private fun synthetic(market: Market = Market.GPW) =
        companies.save(Company(null, "SYN", "PLSYN0000012", market, "SYN", null, false))

    private fun reportFor(company: Company) = rb23().copy(companyId = company.id!!)

    private fun reset() {
        jdbc.sql("TRUNCATE company, daily_quote RESTART IDENTITY CASCADE").update()
    }

    private fun periodOf(kind: PeriodKind, currency: String, end: String, revenue: Long, prev: Long?) = VerifiedExtraction(
        Extraction(
            ReportCategory.RESULTS,
            listOf(
                ExtractedPeriod(
                    kind, LocalDate.parse(end), currency, Amount(BigDecimal(revenue), "$revenue"), prev?.let { Amount(BigDecimal(it), "$it") },
                    null, null, null, null,
                )
            ),
            Outlook(OutlookTone.NONE, null), false, null, false,
        ),
        emptySet(),
    )

    private fun saveQuarters(company: Company, count: Int, netProfit: (Int) -> Long = { 10 }) {
        (0 until count).forEach { i ->
            val end = LocalDate.of(2026, 6, 30).minusMonths(3L * i).let { it.withDayOfMonth(it.lengthOfMonth()) }
            quarterResults.save(
                QuarterResult(
                    company.id!!, end, BigDecimal(1000 + 50 * (count - i) + ((count - i) % 3) * 40), BigDecimal(20),
                    BigDecimal(netProfit(i)), end.plusDays(40),
                )
            )
        }
    }

    private fun saveSessions(company: Company, index: String, turnover: Long) {
        generateSequence(LocalDate.of(2026, 9, 18)) { it.minusDays(1) }
            .filter { it.dayOfWeek.value <= 5 }
            .take(25)
            .forEachIndexed { i, date ->
                quotes.save(DailyQuote(company.isin!!, date, BigDecimal(110 - i), 1000, BigDecimal(turnover)))
                quotes.save(DailyQuote(index, date, BigDecimal(1020 - 2 * i), 1, BigDecimal(1)))
            }
    }

    private fun saveMonth(company: Company, end: String, revenue: Long, prev: Long?) {
        revenuePeriods.save(
            RevenuePeriod(
                company.id!!, PeriodKind.MONTH, LocalDate.parse(end), "USD", BigDecimal(revenue), prev?.let { BigDecimal(it) },
                Instant.parse("2026-08-20T06:00:00Z"), null,
            )
        )
    }

    @Test
    fun `quarterly SUR needs a PLN extraction and a quarterly primary period`() {
        val company = synthetic()
        saveQuarters(company, 12)

        val pln = assembler.assemble(company, reportFor(company), periodOf(PeriodKind.QUARTER, "PLN", "2026-09-30", 1900, 1200))
        val usd = assembler.assemble(company, reportFor(company), periodOf(PeriodKind.QUARTER, "USD", "2026-09-30", 1900, 1200))
        val half = assembler.assemble(company, reportFor(company), periodOf(PeriodKind.HALF_YEAR, "PLN", "2026-09-30", 1900, 1200))

        assertNotNull(pln.sur)
        assertNull(usd.sur)
        assertNull(half.sur)
        assertTrue(usd.previousYoyPcts.isNotEmpty())
        assertTrue(half.previousYoyPcts.isNotEmpty())
    }

    @Test
    fun `PLN quarter without a prior year in the extraction takes it from BiznesRadar`() {
        val company = synthetic()
        saveQuarters(company, 12)

        val input = assembler.assemble(company, reportFor(company), periodOf(PeriodKind.QUARTER, "PLN", "2026-06-30", 1900, null))

        assertEquals((1900.0 / 1480.0 - 1) * 100, input.revenueYoyPct!!, 1e-6)
    }

    @Test
    fun `USD quarter without a prior year is not completed from BiznesRadar`() {
        val company = synthetic()
        saveQuarters(company, 12)

        val input = assembler.assemble(company, reportFor(company), periodOf(PeriodKind.QUARTER, "USD", "2026-06-30", 1900, null))

        assertNull(input.revenueYoyPct)
    }

    @Test
    fun `liquidity threshold is 100000 zloty average turnover`() {
        val low = synthetic().also { saveSessions(it, "WIG", 99_999) }
        assertTrue(assembler.assemble(low, reportFor(low), null).lowLiquidity)

        reset()
        val enough = synthetic().also { saveSessions(it, "WIG", 100_000) }
        assertFalse(assembler.assemble(enough, reportFor(enough), null).lowLiquidity)
    }

    @Test
    fun `price and index change over 10 sessions use WIG for GPW and NCINDEX for NC`() {
        val gpw = synthetic(Market.GPW).also { saveSessions(it, "WIG", 500_000) }
        val gpwInput = assembler.assemble(gpw, reportFor(gpw), null)
        assertEquals((110.0 / 100.0 - 1) * 100, gpwInput.priceChange10Pct!!, 1e-9)
        assertEquals((1020.0 / 1000.0 - 1) * 100, gpwInput.indexChange10Pct!!, 1e-9)

        reset()
        val nc = synthetic(Market.NC).also { saveSessions(it, "NCINDEX", 500_000) }
        assertEquals((1020.0 / 1000.0 - 1) * 100, assembler.assemble(nc, reportFor(nc), null).indexChange10Pct!!, 1e-9)

        reset()
        val wrongIndex = synthetic(Market.NC).also { saveSessions(it, "WIG", 500_000) }
        assertNull(assembler.assemble(wrongIndex, reportFor(wrongIndex), null).indexChange10Pct)
    }

    @Test
    fun `TTM net profit growth needs 8 quarters and a positive prior sum`() {
        val growing = synthetic()
        saveQuarters(growing, 8) { i -> if (i < 4) 30 else 10 }
        assertEquals(200.0, assembler.assemble(growing, reportFor(growing), null).ttmNetProfitGrowthPct!!, 1e-9)

        reset()
        val lossBefore = synthetic()
        saveQuarters(lossBefore, 8) { i -> if (i < 4) 30 else -10 }
        assertNull(assembler.assemble(lossBefore, reportFor(lossBefore), null).ttmNetProfitGrowthPct)

        reset()
        val short = synthetic()
        saveQuarters(short, 7)
        assertNull(assembler.assemble(short, reportFor(short), null).ttmNetProfitGrowthPct)
    }

    @Test
    fun `monthly streak stops at a gap month`() {
        val company = synthetic()
        saveMonth(company, "2026-07-31", 200, 100)
        saveMonth(company, "2026-06-30", 200, 100)
        saveMonth(company, "2026-04-30", 200, 100)

        val input = assembler.assemble(company, reportFor(company), periodOf(PeriodKind.MONTH, "USD", "2026-08-31", 604, 316))

        assertEquals(2, input.consecutiveStrongBefore)
    }

    @Test
    fun `monthly streak stops at a missing previous year`() {
        val company = synthetic()
        saveMonth(company, "2026-07-31", 200, 100)
        saveMonth(company, "2026-06-30", 200, null)
        saveMonth(company, "2026-05-31", 200, 100)

        val input = assembler.assemble(company, reportFor(company), periodOf(PeriodKind.MONTH, "USD", "2026-08-31", 604, 316))

        assertEquals(1, input.consecutiveStrongBefore)
    }

    private fun saveTrailingYear(company: Company, revenue: Long, operatingProfit: Long?, netProfit: Long) =
        listOf("2026-06-30", "2026-03-31", "2025-12-31", "2025-09-30").map(LocalDate::parse).forEach { end ->
            quarterResults.save(QuarterResult(company.id!!, end, BigDecimal(revenue), operatingProfit?.let(::BigDecimal), BigDecimal(netProfit), end.plusDays(40)))
        }

    private fun withOperatingProfit(verified: VerifiedExtraction, operatingProfit: Long) = verified.copy(
        extraction = verified.extraction.copy(
            periods = verified.extraction.periods.map { it.copy(operatingProfit = Amount(BigDecimal(operatingProfit), "$operatingProfit")) },
        ),
    )

    @Test
    fun `operating loss larger than revenue over the trailing year marks an early-stage company`() {
        val early = synthetic().also { saveTrailingYear(it, revenue = 100, operatingProfit = -500, netProfit = -480) }
        assertTrue(assembler.assemble(early, reportFor(early), null).earlyStage)

        reset()
        val lossMaking = synthetic().also { saveTrailingYear(it, revenue = 1000, operatingProfit = -200, netProfit = -250) }
        assertFalse(assembler.assemble(lossMaking, reportFor(lossMaking), null).earlyStage)
    }

    @Test
    fun `net loss stands in for a missing operating result in the trailing year`() {
        val company = synthetic().also { saveTrailingYear(it, revenue = 100, operatingProfit = null, netProfit = -480) }

        assertTrue(assembler.assemble(company, reportFor(company), null).earlyStage)
    }

    @Test
    fun `without a trailing year the reported period decides the early stage`() {
        val company = synthetic()
        val quarter = periodOf(PeriodKind.QUARTER, "PLN", "2026-09-30", 300, 40)

        assertTrue(assembler.assemble(company, reportFor(company), withOperatingProfit(quarter, -900)).earlyStage)
        assertFalse(assembler.assemble(company, reportFor(company), withOperatingProfit(quarter, -100)).earlyStage)
        assertFalse(assembler.assemble(company, reportFor(company), quarter).earlyStage)
    }

    @Test
    fun `missing revenue counts as none for a loss-making company`() {
        val company = synthetic()
        listOf("2026-06-30", "2026-03-31", "2025-12-31", "2025-09-30").map(LocalDate::parse).forEach { end ->
            quarterResults.save(QuarterResult(company.id!!, end, null, BigDecimal(-500), BigDecimal(-480), end.plusDays(40)))
        }

        assertTrue(assembler.assemble(company, reportFor(company), null).earlyStage)
    }

    @Test
    fun `early stage needs both the trailing year and the reported quarter to show the loss above revenue`() {
        val company = synthetic().also { saveTrailingYear(it, revenue = 100, operatingProfit = -500, netProfit = -480) }

        val stillEarly = withOperatingProfit(periodOf(PeriodKind.QUARTER, "PLN", "2026-09-30", 300, 40), -900)
        val breakout = withOperatingProfit(periodOf(PeriodKind.QUARTER, "PLN", "2026-09-30", 2000, 40), -300)

        assertTrue(assembler.assemble(company, reportFor(company), stillEarly).earlyStage)
        assertFalse(assembler.assemble(company, reportFor(company), breakout).earlyStage)
    }

    @Test
    fun `trailing year decides over a loss-making reported quarter`() {
        val company = synthetic().also { saveTrailingYear(it, revenue = 1000, operatingProfit = 50, netProfit = 40) }
        val quarter = withOperatingProfit(periodOf(PeriodKind.QUARTER, "PLN", "2026-09-30", 300, 40), -900)

        assertFalse(assembler.assemble(company, reportFor(company), quarter).earlyStage)
    }

    @Test
    fun `close below 1 in the market currency marks a penny stock`() {
        val penny = synthetic()
        quotes.save(DailyQuote(penny.isin!!, LocalDate.of(2026, 9, 18), BigDecimal("0.45"), 1000, BigDecimal(450)))
        assertTrue(assembler.assemble(penny, reportFor(penny), null).pennyStock)

        reset()
        val euro = companies.save(Company(null, "EUR", "PLSYN0000012", Market.GPW, "EUR", null, false, quoteCurrency = "EUR"))
        quotes.save(DailyQuote(euro.isin!!, LocalDate.of(2026, 9, 18), BigDecimal("0.45"), 1000, BigDecimal(450)))
        assertFalse(assembler.assemble(euro, reportFor(euro), null).pennyStock)

        reset()
        val regular = synthetic().also { saveSessions(it, "WIG", 500_000) }
        assertFalse(assembler.assemble(regular, reportFor(regular), null).pennyStock)
    }

    private fun quarterlyReport(company: Company) = reportFor(company).copy(publishedAt = Instant.parse("2026-11-05T06:00:00Z"))

    private fun saveRevenue(company: Company, kind: PeriodKind, end: String, publishedAt: String) {
        revenuePeriods.save(
            RevenuePeriod(company.id!!, kind, LocalDate.parse(end), "USD", BigDecimal(500), BigDecimal(300), Instant.parse(publishedAt), null)
        )
    }

    private val q3 = periodOf(PeriodKind.QUARTER, "PLN", "2026-09-30", 2000, 900)

    @Test
    fun `revenue of all three months published before the quarterly report is already known`() {
        val company = synthetic()
        saveRevenue(company, PeriodKind.MONTH, "2026-07-31", "2026-08-20T06:00:00Z")
        saveRevenue(company, PeriodKind.MONTH, "2026-08-31", "2026-09-21T06:00:00Z")
        assertFalse(assembler.assemble(company, quarterlyReport(company), q3).revenueAlreadyKnown)

        saveRevenue(company, PeriodKind.MONTH, "2026-09-30", "2026-11-06T06:00:00Z")
        assertFalse(assembler.assemble(company, quarterlyReport(company), q3).revenueAlreadyKnown)

        reset()
        val again = synthetic()
        saveRevenue(again, PeriodKind.MONTH, "2026-07-31", "2026-08-20T06:00:00Z")
        saveRevenue(again, PeriodKind.MONTH, "2026-08-31", "2026-09-21T06:00:00Z")
        saveRevenue(again, PeriodKind.MONTH, "2026-09-30", "2026-10-20T06:00:00Z")
        assertTrue(assembler.assemble(again, quarterlyReport(again), q3).revenueAlreadyKnown)
    }

    @Test
    fun `an earlier revenue estimate for the quarter makes it already known, the report's own revenue does not`() {
        val company = synthetic()
        saveRevenue(company, PeriodKind.QUARTER, "2026-09-30", "2026-11-05T06:00:00Z")
        assertFalse(assembler.assemble(company, quarterlyReport(company), q3).revenueAlreadyKnown)

        saveRevenue(company, PeriodKind.QUARTER, "2026-09-30", "2026-10-08T06:00:00Z")
        assertTrue(assembler.assemble(company, quarterlyReport(company), q3).revenueAlreadyKnown)
    }

    @Test
    fun `a monthly sales report is never marked as already known`() {
        val company = synthetic()
        saveRevenue(company, PeriodKind.MONTH, "2026-09-30", "2026-10-20T06:00:00Z")

        val september = periodOf(PeriodKind.MONTH, "USD", "2026-09-30", 500, 300)

        assertFalse(assembler.assemble(company, quarterlyReport(company), september).revenueAlreadyKnown)
    }

    @Test
    fun `positive net profit with negative operating cash flow is profit without cash`() {
        val company = synthetic()
        fun withCashFlow(operating: Long, net: Long) =
            q3.copy(extraction = q3.extraction.copy(cashFlow = CashFlow(Amount(BigDecimal(operating), "$operating"), Amount(BigDecimal(net), "$net"))))

        assertTrue(assembler.assemble(company, quarterlyReport(company), withCashFlow(-46412, 51800)).profitWithoutCash)
        assertFalse(assembler.assemble(company, quarterlyReport(company), withCashFlow(46412, 51800)).profitWithoutCash)
        assertFalse(assembler.assemble(company, quarterlyReport(company), withCashFlow(-46412, -1000)).profitWithoutCash)
        assertFalse(assembler.assemble(company, quarterlyReport(company), q3).profitWithoutCash)
    }

    private fun savedReportWithNote(company: Company, vararg rows: ConsensusRow): Report {
        val report = checkNotNull(reports.saveIfNew(quarterlyReport(company).copy(id = null)))
        consensusNotes.save(checkNotNull(report.id), ConsensusNote("https://www.bankier.pl/wiadomosc/x-1.html", report.publishedAt, rows.toList()), report.publishedAt)
        return report
    }

    private val orlenRows = arrayOf(
        ConsensusRow("Przychody", BigDecimal("80140"), BigDecimal("85025"), -5.7),
        ConsensusRow("EBITDA", BigDecimal("13000"), BigDecimal("14000"), -7.1),
        ConsensusRow("EBITDA LIFO skor.", BigDecimal("14779"), BigDecimal("15316"), -3.5),
        ConsensusRow("EBIT", BigDecimal("9000"), BigDecimal("9500"), -5.3),
        ConsensusRow("zysk netto j.d.", BigDecimal("5610"), BigDecimal("7480"), -25.0),
    )

    @Test
    fun `GPW consensus note becomes PAP comparisons in thousands with the adjusted EBITDA as profit`() {
        val company = synthetic()
        val report = savedReportWithNote(company, ConsensusRow("marża EBITDA LIFO", BigDecimal("18.4"), BigDecimal("18.0"), 0.4), *orlenRows)

        assertEquals(
            listOf(
                ConsensusComparison(ConsensusMetric.REVENUE, BigDecimal("80140000"), BigDecimal("85025000"), "PLN", "PAP Biznes", null),
                ConsensusComparison(ConsensusMetric.EBIT, BigDecimal("14779000"), BigDecimal("15316000"), "PLN", "PAP Biznes", null, adjustedForOneOffs = true),
            ),
            assembler.assemble(company, report, q3).consensus,
        )
    }

    @Test
    fun `profit falls back from plain EBITDA to operating profit to net profit`() {
        fun profitOf(vararg rows: ConsensusRow): BigDecimal? {
            reset()
            val company = synthetic()
            return assembler.assemble(company, savedReportWithNote(company, *rows), q3).consensus.firstOrNull { it.metric == ConsensusMetric.EBIT }?.actual
        }

        assertEquals(BigDecimal("13000000"), profitOf(orlenRows[1], orlenRows[3], orlenRows[4]))
        assertEquals(BigDecimal("9000000"), profitOf(ConsensusRow("Zysk operacyjny", BigDecimal("9000"), BigDecimal("9500"), -5.3), orlenRows[4]))
        assertEquals(BigDecimal("5610000"), profitOf(orlenRows[0], orlenRows[4]))
        assertNull(profitOf(orlenRows[0], ConsensusRow("marża EBIT", BigDecimal("11"), BigDecimal("12"), -1.0)))
    }

    @Test
    fun `adjusted EBITDA comparison is flagged as free of one-offs and plain EBITDA is not`() {
        val company = synthetic()
        val adjusted = assembler.assemble(company, savedReportWithNote(company, *orlenRows), q3).consensus.single { it.metric == ConsensusMetric.EBIT }
        reset()
        val other = synthetic()
        val plain = assembler.assemble(other, savedReportWithNote(other, orlenRows[1]), q3).consensus.single { it.metric == ConsensusMetric.EBIT }

        assertTrue(adjusted.adjustedForOneOffs)
        assertFalse(plain.adjustedForOneOffs)
    }

    @Test
    fun `profit row with a consensus below one million is skipped for the next row in priority`() {
        val company = synthetic()
        val tinyAdjusted = ConsensusRow("EBITDA skorygowana", BigDecimal("1.2"), BigDecimal("0.5"), 140.0)
        val report = savedReportWithNote(company, tinyAdjusted, orlenRows[1])

        val profit = assembler.assemble(company, report, q3).consensus.single { it.metric == ConsensusMetric.EBIT }

        assertEquals(BigDecimal("13000000"), profit.actual)
        assertFalse(profit.adjustedForOneOffs)
    }

    @Test
    fun `GPW monthly sales report ignores a consensus note`() {
        val company = synthetic()
        val report = savedReportWithNote(company, *orlenRows)

        assertTrue(assembler.assemble(company, report, periodOf(PeriodKind.MONTH, "PLN", "2026-10-31", 500, 300)).consensus.isEmpty())
    }

    private companion object {
        const val ASBIS_ISIN = "CY1000031710"
    }
}
