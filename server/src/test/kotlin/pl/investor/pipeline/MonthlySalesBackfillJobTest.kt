package pl.investor.pipeline

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.investor.extraction.AiBudget
import pl.investor.extraction.AiPool
import pl.investor.extraction.AiUsageRepository
import pl.investor.extraction.ClaudeExtractor
import pl.investor.extraction.QuoteVerifier
import pl.investor.history.RevenuePeriodRepository
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.source.biznesradar.BiznesRadarClient
import pl.investor.source.content.ReportContentClient
import pl.investor.source.content.ReportContentParser
import pl.investor.source.gpw.EspiListingClient
import pl.investor.source.gpw.EspiListingParser
import pl.investor.source.gpw.ListingEntry
import pl.investor.source.gpw.ListingPage
import pl.investor.support.PostgresTestBase
import pl.investor.support.WARSAW
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonthlySalesBackfillJobTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var revenuePeriods: RevenuePeriodRepository
    @Autowired lateinit var aiUsage: AiUsageRepository
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC)
    private val today: LocalDate = LocalDate.of(2026, 9, 1)
    private val listing = mockk<EspiListingClient>()
    private val contentClient = mockk<ReportContentClient>()
    private val biznesRadar = mockk<BiznesRadarClient>()
    private val emptyPage = ListingPage(emptyList(), 0, emptyList())
    private lateinit var server: MockWebServer
    private lateinit var job: MonthlySalesBackfillJob

    private val monthlyReportFixtures = mapOf(
        "473946" to "2025-09", "475584" to "2025-10", "477234" to "2025-11", "479118" to "2025-12",
        "480594" to "2026-01", "482189" to "2026-02", "484128" to "2026-03", "486464" to "2026-04",
        "489193" to "2026-05", "491914" to "2026-06", "494055" to "2026-07", "495649" to "2026-08",
    )

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company RESTART IDENTITY CASCADE").update()
        jdbc.sql("DELETE FROM ai_usage").update()
        server = MockWebServer().apply { start() }
        every { biznesRadar.symbolFor(any()) } returns "ASBISC"
        every { biznesRadar.mediumNameFor(any()) } returns "ASBIS"
        every { listing.page(any(), any(), any()) } returns emptyPage
        every { contentClient.fetch(any(), any()) } answers {
            val month = checkNotNull(monthlyReportFixtures[firstArg<ListingEntry>().sourceRef])
            ReportContentParser().parse(fixture("reports/asbis-rb-sales-$month.html"))
        }
        job = jobWith(revenuePeriods)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun jobWith(periods: RevenuePeriodRepository, budget: AiBudget = AiBudget(aiUsage, clock)) = MonthlySalesBackfillJob(
        listing, CompanyResolver(companies, biznesRadar), companies, reports, contentClient,
        ClaudeExtractor(budget, AnthropicOkHttpClient.builder().baseUrl(server.url("/").toString().trimEnd('/')).apiKey("test-key").maxRetries(0).build()),
        QuoteVerifier(), periods, TransactionTemplate(transactionManager), clock,
    )

    private fun fixture(path: String) =
        checkNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readText()

    private fun enqueueAi(month: String) =
        server.enqueue(MockResponse().setHeader("content-type", "application/json").setBody(fixture("ai/asbis-rb-sales-$month.json")))

    private fun asbisMonthlySales(since: LocalDate): List<ListingEntry> =
        EspiListingParser().parse(fixture("gpw/backfill-asbis.html"), Market.GPW).entries
            .filter { it.isMonthlySales() && it.publishedAt.atZone(WARSAW).toLocalDate() in since..today.minusDays(1) }

    private fun entry(market: Market, name: String, isin: String?, sourceRef: String, date: LocalDate) = ListingEntry(
        market = market,
        companyName = name,
        isin = isin,
        kind = ReportKind.CURRENT,
        number = "1/2026",
        sourceRef = sourceRef,
        title = "Informacja o szacunkowych miesięcznych przychodach ze sprzedaży",
        publishedAt = date.atTime(8, 0).atZone(WARSAW).toInstant(),
        detailUrl = "https://example.com/$sourceRef",
    )

    private fun listingReturns(vararg entries: ListingEntry) {
        every { listing.page(any(), any(), any()) } answers {
            val market = firstArg<Market>()
            val date = secondArg<LocalDate>()
            val found = entries.filter { it.market == market && it.publishedAt.atZone(WARSAW).toLocalDate() == date }
            if (thirdArg<Int>() == 0 && found.isNotEmpty()) ListingPage(found, found.size, emptyList()) else emptyPage
        }
    }

    private data class StoredPeriod(val periodEnd: String, val publishedAt: Instant, val reportId: Long?)

    private fun stored(): List<StoredPeriod> =
        jdbc.sql("SELECT period_end, published_at, report_id FROM revenue_period ORDER BY period_end")
            .query { rs: ResultSet, _ ->
                StoredPeriod(rs.getString("period_end"), rs.getTimestamp("published_at").toInstant(), rs.getObject("report_id") as Long?)
            }
            .list()

    @Test
    fun `backfill stores 12 ASBIS months with original publication dates`() {
        monthlyReportFixtures.values.forEach(::enqueueAi)
        val entries = asbisMonthlySales(since = LocalDate.of(2025, 9, 1))

        val storedMonths = job.backfill(entries)

        val periods = stored()
        assertEquals(12, storedMonths)
        assertEquals(12, periods.size)
        assertEquals("2025-08-31", periods.first().periodEnd)
        assertEquals(Instant.parse("2025-09-22T06:09:10Z"), periods.first().publishedAt)
        assertEquals("2026-07-31", periods.last().periodEnd)
        assertEquals(Instant.parse("2026-08-21T06:15:21Z"), periods.last().publishedAt)
        assertTrue(periods.all { it.reportId != null })
        assertEquals(12L, jdbc.sql("SELECT count(*) FROM report WHERE status = 'BACKFILLED'").query(Long::class.java).single())
        assertTrue(jdbc.sql("SELECT publishes_monthly_sales FROM company").query(Boolean::class.java).single())
        val requestsAfterFirstRun = server.requestCount
        assertEquals(0, job.backfill(entries))
        assertEquals(requestsAfterFirstRun, server.requestCount)
        assertEquals(0, jdbc.sql("SELECT count(*) FROM score").query(Long::class.java).single())
        assertEquals(0, jdbc.sql("SELECT count(*) FROM push_log").query(Long::class.java).single())
    }

    @Test
    fun `failed period write leaves no BACKFILLED row`() {
        enqueueAi("2026-08")
        val failing = spyk(revenuePeriods)
        every { failing.save(any()) } throws IllegalStateException("disk full")

        assertEquals(0, jobWith(failing).backfill(asbisMonthlySales(since = LocalDate.of(2026, 8, 1))))

        assertEquals(0L, jdbc.sql("SELECT count(*) FROM report WHERE status = 'BACKFILLED'").query(Long::class.java).single())
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM revenue_period").query(Long::class.java).single())
    }

    @Test
    fun `runAll backfills NewConnect entries with the NC market`() {
        enqueueAi("2026-08")
        listingReturns(entry(Market.NC, "ASBISC ENTERPRISES PLC", "CY1000031710", "495649", LocalDate.of(2026, 8, 21)))

        assertEquals(1, job.runAll(months = 1))

        assertEquals("NC", jdbc.sql("SELECT market FROM report WHERE source_ref = '495649'").query(String::class.java).single())
        assertEquals("BACKFILLED", jdbc.sql("SELECT status FROM report WHERE source_ref = '495649'").query(String::class.java).single())
        assertTrue(stored().isNotEmpty())
        enqueueAi("2026-08")
        val requestsAfterFirstRun = server.requestCount
        job.runAll(months = 1)
        assertEquals(requestsAfterFirstRun, server.requestCount)
    }

    @Test
    fun `runAll keeps companies with substring names but different ISINs apart`() {
        val bio = entry(Market.GPW, "BIO", "PLBIO0000001", "1", LocalDate.of(2026, 8, 10))
        val bioton = entry(Market.GPW, "BIOTON", "PLBIOTN00001", "2", LocalDate.of(2026, 8, 11))
        val biotonLater = entry(Market.GPW, "BIOTON", "PLBIOTN00001", "3", LocalDate.of(2026, 8, 12))
        listingReturns(bio, bioton, biotonLater)
        val spied = spyk(job)
        every { spied.backfill(any()) } returns 0

        assertEquals(2, spied.runAll(months = 1))

        verify(exactly = 1) { spied.backfill(listOf(bio)) }
        verify(exactly = 1) { spied.backfill(listOf(bioton, biotonLater)) }
        verify(exactly = 2) { spied.backfill(any()) }
    }

    @Test
    fun `runAll ends yesterday so reports of today stay with the live poller`() {
        listingReturns(
            entry(Market.GPW, "ASBISC ENTERPRISES PLC", "CY1000031710", "495649", today),
            entry(Market.NC, "NC COMPANY", "PLNC00000001", "9", today),
        )
        val spied = spyk(job)
        every { spied.backfill(any()) } returns 0

        assertEquals(0, spied.runAll(months = 1))

        verify(exactly = 0) { listing.page(any(), today, any()) }
        verify(exactly = 1) { listing.page(Market.GPW, today.minusDays(1), 0) }
        verify(exactly = 1) { listing.page(Market.NC, today.minusDays(1), 0) }
        verify(exactly = 0) { spied.backfill(any()) }
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM report").query(Long::class.java).single())
    }

    @Test
    fun `exhausted AI budget stops the remaining companies`() {
        val budget = AiBudget(aiUsage, clock).apply { record(AiPool.GPW, 20_000_000, 0) }
        listingReturns(
            entry(Market.GPW, "ASBISC ENTERPRISES PLC", "CY1000031710", "495649", LocalDate.of(2026, 8, 21)),
            entry(Market.NC, "OTHER SA", "PLOTHER00001", "494055", LocalDate.of(2026, 8, 22)),
        )
        val spied = spyk(jobWith(revenuePeriods, budget))

        spied.runAll(months = 1)

        verify(exactly = 1) { spied.backfill(any()) }
        assertEquals(0, server.requestCount)
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM report").query(Long::class.java).single())
    }

    @Test
    fun `rejected API key stops the remaining companies`() {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("content-type", "application/json")
            .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        listingReturns(
            entry(Market.GPW, "ASBISC ENTERPRISES PLC", "CY1000031710", "494055", LocalDate.of(2026, 8, 21)),
            entry(Market.GPW, "ASBISC ENTERPRISES PLC", "CY1000031710", "495649", LocalDate.of(2026, 8, 22)),
            entry(Market.NC, "OTHER SA", "PLOTHER00001", "491914", LocalDate.of(2026, 8, 23)),
        )
        val spied = spyk(job)

        spied.runAll(months = 1)

        verify(exactly = 1) { spied.backfill(any()) }
        assertEquals(1, server.requestCount)
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM report").query(Long::class.java).single())
    }
}
