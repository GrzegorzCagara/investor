package pl.investor.pipeline

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.api.AlertRepository
import pl.investor.extraction.AiBudget
import pl.investor.extraction.AiPool
import pl.investor.extraction.AiUsageRepository
import pl.investor.extraction.ClaudeExtractor
import pl.investor.extraction.Extraction
import pl.investor.extraction.ExtractionRepository
import pl.investor.extraction.Outlook
import pl.investor.extraction.OutlookTone
import pl.investor.extraction.ReportCategory
import pl.investor.extraction.VerifiedExtraction
import pl.investor.extraction.QuoteVerifier
import pl.investor.history.QuarterResultRepository
import pl.investor.history.RevenuePeriodRepository
import pl.investor.history.ValuationRepository
import pl.investor.news.NewsAssessmentRepository
import pl.investor.news.NewsAssessor
import pl.investor.news.NewsContextAssembler
import pl.investor.news.NewsCategory
import pl.investor.news.NewsDirection
import pl.investor.notification.PushLogRepository
import pl.investor.notification.PushMessage
import pl.investor.notification.PushMessageFactory
import pl.investor.notification.PushNotifier
import pl.investor.notification.ReasonWriter
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.EventType
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.scoring.ScoreRepository
import pl.investor.scoring.ScoringInputAssembler
import pl.investor.settings.AlertFilters
import pl.investor.settings.PushThreshold
import pl.investor.settings.SettingRepository
import pl.investor.history.PeriodKind
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.source.biznesradar.BiznesRadarClient
import pl.investor.source.biznesradar.QuarterlyResultsParser
import pl.investor.source.biznesradar.SymbolNotFoundException
import pl.investor.source.biznesradar.ValuationParser
import pl.investor.source.content.ReportContent
import pl.investor.source.content.ReportContentClient
import pl.investor.source.content.ReportContentNotFoundException
import pl.investor.source.content.ReportContentParser
import pl.investor.source.gpw.ListingEntry
import pl.investor.source.xtb.XtbAvailability
import pl.investor.source.xtb.XtbClient
import pl.investor.source.xtb.XtbCountry
import pl.investor.source.xtb.XtbStock
import pl.investor.support.PostgresTestBase
import pl.investor.scoring.Warning
import pl.investor.source.biznesradar.ParsedQuarter
import pl.investor.support.WARSAW
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportPipelineTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var extractions: ExtractionRepository
    @Autowired lateinit var revenuePeriods: RevenuePeriodRepository
    @Autowired lateinit var quarterResults: QuarterResultRepository
    @Autowired lateinit var valuations: ValuationRepository
    @Autowired lateinit var assembler: ScoringInputAssembler
    @Autowired lateinit var scores: ScoreRepository
    @Autowired lateinit var settings: SettingRepository
    @Autowired lateinit var pushLog: PushLogRepository
    @Autowired lateinit var aiUsage: AiUsageRepository
    @Autowired lateinit var newsAssessments: NewsAssessmentRepository
    @Autowired lateinit var newsContext: NewsContextAssembler
    @Autowired lateinit var alerts: AlertRepository

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-21T06:15:00Z"), ZoneOffset.UTC)
    private val notifier = mockk<PushNotifier>(relaxed = true)
    private val contentClient = mockk<ReportContentClient>()
    private val biznesRadar = mockk<BiznesRadarClient>()
    private val xtb = mockk<XtbClient>()
    private val reasonWriter = mockk<ReasonWriter>()
    private val fixtureBySourceRef = mutableMapOf<String, String>()
    private val pendingAttachments = mutableSetOf<String>()

    private lateinit var server: MockWebServer
    private lateinit var budget: AiBudget
    private lateinit var refresher: CompanyDataRefresher
    private lateinit var pipeline: ReportPipeline

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company RESTART IDENTITY CASCADE").update()
        jdbc.sql("DELETE FROM ai_usage").update()
        jdbc.sql("DELETE FROM setting WHERE key <> 'PUSH_THRESHOLD'").update()
        settings.put("PUSH_THRESHOLD", "0")
        server = MockWebServer().apply { start() }
        budget = AiBudget(aiUsage, clock)
        val anthropic = AnthropicOkHttpClient.builder()
            .baseUrl(server.url("/").toString().trimEnd('/'))
            .apiKey("test-key")
            .maxRetries(0)
            .build()
        every { biznesRadar.symbolFor(any()) } returns "ASBISC"
        every { biznesRadar.mediumNameFor(any()) } returns "ASBIS"
        every { biznesRadar.tickersBySymbol() } returns mapOf("ASBISC" to "ASB")
        every { xtb.stocks(XtbCountry.PL) } returns listOf(XtbStock("ASB", "ASBISC Enterprises PLC"))
        every { xtb.stocks(XtbCountry.SE) } returns listOf(XtbStock("VOLVB", "Volvo AB"))
        every { xtb.stocks(XtbCountry.DE) } returns listOf(XtbStock("MZX", "Masterflex SE"))
        every { xtb.stocks(XtbCountry.NL) } returns listOf(XtbStock("ASML", "ASML Holding NV"))
        every { biznesRadar.quarterly("ASBISC") } returns QuarterlyResultsParser().parse(fixture("biznesradar/ASBISC-pl-q.html"))
        every { biznesRadar.valuation("ASBISC") } returns ValuationParser().parse(fixture("biznesradar/ASBISC-valuation.html"))
        every { contentClient.fetch(any(), any()) } answers { content(firstArg<ListingEntry>().sourceRef, secondArg()) }
        every { reasonWriter.write(any(), any(), any()) } returns "Przychody +91% r/r. Reszta uzasadnienia."
        every { notifier.send(any()) } answers { pushLog.save(firstArg<PushMessage>(), clock.instant()) }
        refresher = CompanyDataRefresher(biznesRadar, companies, quarterResults, valuations, settings, clock, mockk(relaxed = true))
        pipeline = ReportPipeline(
            companies, reports, CompanyResolver(companies, biznesRadar), contentClient, budget,
            ClaudeExtractor(budget, anthropic), QuoteVerifier(), extractions, revenuePeriods, quarterResults,
            refresher, assembler, reasonWriter, scores, settings, PushThreshold(settings), AlertFilters(settings), pushLog, notifier, PushMessageFactory(),
            NewsAssessor(budget, anthropic, QuoteVerifier()), newsAssessments, newsContext, XtbFilter("DE,GPW,NC,SE", XtbAvailability(xtb, biznesRadar, companies, clock), refresher, companies), clock,
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        jdbc.sql("DELETE FROM setting WHERE key IN ('DISABLED_MARKETS', 'DISABLED_EVENT_TYPES') OR key LIKE 'QUIET_HOURS_%'").update()
        settings.put("PUSH_THRESHOLD", "70")
    }

    private fun fixture(path: String) =
        checkNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readText()

    private fun content(sourceRef: String, includeAttachments: Boolean): ReportContent {
        val parsed = fixtureBySourceRef[sourceRef]?.let { ReportContentParser().parse(fixture(it)) }
            ?: ReportContentParser().parse(fixture("reports/asbis-rb-23-2026.html")).copy(attachmentUrls = listOf("https://www.gpw.pl/attachment/zal01.pdf"))
        return if (includeAttachments && sourceRef in pendingAttachments) parsed.copy(attachmentsPending = true) else parsed
    }

    private fun json(body: String) = MockResponse().setHeader("content-type", "application/json").setBody(body)

    private fun entry(
        sourceRef: String = "497297",
        title: String? = "Informacja o szacunkowych miesięcznych skonsolidowanych przychodach za sierpień 2026 r.",
        kind: ReportKind = ReportKind.CURRENT,
    ) = ListingEntry(
        market = Market.GPW,
        companyName = "ASBISC ENTERPRISES PLC",
        isin = "CY1000031710",
        kind = kind,
        number = "23/2026",
        sourceRef = sourceRef,
        title = title,
        publishedAt = Instant.parse("2026-09-21T06:09:21Z"),
        detailUrl = "https://www.gpw.pl/komunikat?geru_id=$sourceRef",
    )

    private fun statusOf(sourceRef: String): ReportStatus =
        reports.findById(jdbc.sql("SELECT id FROM report WHERE source_ref = :ref").param("ref", sourceRef).query(Long::class.java).single()).status

    private fun enqueueResultsWithoutNumbers() = server.enqueue(
        json(
            """{"id":"m","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":"{\"category\":\"RESULTS\",\"periods\":[],\"outlook\":{\"tone\":\"NONE\",\"quote\":null},\"oneOffQuarter\":false,\"oneOffQuote\":null,\"cyclicalIndustry\":false}"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":10}}"""
        )
    )

    private fun enqueueAsbisRb23() = server.enqueue(json(fixture("ai/asbis-rb-23-2026.json")))

    @Test
    fun `irrelevant current report makes no AI call`() {
        pipeline.process(entry("1", "Zwołanie Nadzwyczajnego Walnego Zgromadzenia"))

        assertEquals(ReportStatus.IRRELEVANT, statusOf("1"))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
    }

    @Test
    fun `correction is stored without AI and without push`() {
        pipeline.process(entry("2", "Korekta raportu bieżącego nr 21/2026 z dnia 24 sierpnia 2026 r."))

        assertEquals(ReportStatus.CORRECTION_SKIPPED, statusOf("2"))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { notifier.send(any()) }
    }

    @Test
    fun `same report processed twice is pushed once`() {
        enqueueAsbisRb23()

        pipeline.process(entry())
        pipeline.process(entry())

        assertEquals(1, server.requestCount)
        verify(exactly = 1) { notifier.send(match { it.title.startsWith("ASBIS (GPW) · WYNIKI · ocena") }) }
    }

    @Test
    fun `second report for the same company and period is not pushed again`() {
        enqueueAsbisRb23()
        enqueueAsbisRb23()

        pipeline.process(entry("497297"))
        pipeline.process(entry("497298"))

        assertEquals(ReportStatus.SCORED, statusOf("497298"))
        verify(exactly = 1) { notifier.send(match { it.title.startsWith("ASBIS (GPW)") }) }
    }

    @Test
    fun `ASBIS RB 23 is scored and pushed`() {
        enqueueAsbisRb23()

        pipeline.process(entry())

        assertEquals(ReportStatus.SCORED, statusOf("497297"))
        verify(exactly = 1) { notifier.send(match { it.title.startsWith("ASBIS (GPW) · WYNIKI · ocena") }) }
        val reportId = jdbc.sql("SELECT id FROM report").query(Long::class.java).single()
        assertTrue(scores.totalOf(reportId) >= 0.0)
        assertEquals(0, extractions.uncertainFields(reportId).size)
        assertEquals(
            "2026-08-31",
            jdbc.sql("SELECT period_end FROM push_log WHERE kind = 'RESULTS'").query(String::class.java).single(),
        )
    }

    @Test
    fun `score below the threshold is saved but not pushed`() {
        settings.put("PUSH_THRESHOLD", "101")
        enqueueAsbisRb23()

        pipeline.process(entry())

        assertEquals(ReportStatus.SCORED, statusOf("497297"))
        verify(exactly = 0) { notifier.send(any()) }
    }

    @Test
    fun `exhausted GPW pool marks monthly sales UNSCORED_NO_AI and pushes the budget alert once`() {
        budget.record(AiPool.GPW, 20_000_000, 0)

        pipeline.process(entry("10"))
        pipeline.process(entry("11"))

        assertEquals(ReportStatus.UNSCORED_NO_AI, statusOf("10"))
        assertEquals(ReportStatus.UNSCORED_NO_AI, statusOf("11"))
        assertEquals(0, server.requestCount)
        verify(exactly = 1) { notifier.send(match { it.title == "Limit AI wyczerpany" }) }
    }

    @Test
    fun `content fetch failure marks FAILED and does not stop the next report`() {
        every { contentClient.fetch(match { it.sourceRef == "20" }, any()) } throws ReportContentNotFoundException("https://x")
        enqueueAsbisRb23()

        pipeline.process(entry("20"))
        pipeline.process(entry("21"))

        assertEquals(ReportStatus.FAILED, statusOf("20"))
        assertEquals(ReportStatus.SCORED, statusOf("21"))
    }

    @Test
    fun `unavailable AI leaves the report pending`() {
        server.enqueue(MockResponse().setResponseCode(529).setBody("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))

        pipeline.process(entry("30"))

        assertEquals(ReportStatus.PENDING_AI, statusOf("30"))
    }

    @Test
    fun `rejected API key leaves reports pending and pushes the key alert once per day`() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")) }
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"type":"error","error":{"type":"permission_error","message":"not allowed"}}"""))

        pipeline.process(entry("31"))
        pipeline.process(entry("32"))
        pipeline.process(entry("33"))

        assertEquals(ReportStatus.PENDING_AI, statusOf("31"))
        assertEquals(ReportStatus.PENDING_AI, statusOf("32"))
        assertEquals(ReportStatus.PENDING_AI, statusOf("33"))
        verify(exactly = 1) {
            notifier.send(match { it.title == "Claude odrzuca klucz API" && it.body == "Sprawdź ANTHROPIC_API_KEY w .env i limit w Console. Raporty czekają do 2 godzin." })
        }
        assertTrue(settings.find("AI_AUTH_NOTIFIED_2026-09-21") != null)
    }

    @Test
    fun `pending attachments are held until reprocessing allows going without them`() {
        pendingAttachments += "40"
        enqueueResultsWithoutNumbers()
        enqueueAsbisRb23()

        pipeline.process(entry("40"))
        assertEquals(ReportStatus.PENDING_ATTACHMENTS, statusOf("40"))
        assertEquals(1, server.requestCount)

        val reportId = jdbc.sql("SELECT id FROM report WHERE source_ref = '40'").query(Long::class.java).single()
        pipeline.reprocess(reportId, allowWithoutAttachments = true)

        assertEquals(ReportStatus.SCORED, statusOf("40"))
    }

    @Test
    fun `current report with numbers in the text is scored and pushed without looking at attachments`() {
        enqueueAsbisRb23()

        pipeline.process(entry())

        assertEquals(ReportStatus.SCORED, statusOf("497297"))
        verify(exactly = 1) { notifier.send(match { it.title.startsWith("ASBIS (GPW) · WYNIKI · ocena") }) }
        verify(exactly = 0) { contentClient.fetch(any(), true) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `current report without numbers in the text gets a second pass with attachments`() {
        enqueueResultsWithoutNumbers()
        enqueueAsbisRb23()

        pipeline.process(entry("45"))

        assertEquals(ReportStatus.SCORED, statusOf("45"))
        assertEquals(2, server.requestCount)
        verify(exactly = 1) { contentClient.fetch(any(), true) }
        verify(exactly = 1) { contentClient.fetch(any(), false) }
    }

    @Test
    fun `retries while attachments are pending make no further body AI calls`() {
        pendingAttachments += "46"
        enqueueResultsWithoutNumbers()
        enqueueAsbisRb23()

        pipeline.process(entry("46"))
        val id = reportId("46")
        repeat(3) { pipeline.reprocess(id, allowWithoutAttachments = false) }

        assertEquals(ReportStatus.PENDING_ATTACHMENTS, statusOf("46"))
        assertEquals(1, server.requestCount)

        pipeline.reprocess(id, allowWithoutAttachments = true)

        assertEquals(ReportStatus.SCORED, statusOf("46"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `refresher fetches BiznesRadar once per day`() {
        val company = companies.save(Company(null, "ASBISC ENTERPRISES PLC", "CY1000031710", Market.GPW, null, "ASBISC", false))

        refresher.refresh(company)
        refresher.refresh(company)

        verify(exactly = 1) { biznesRadar.quarterly("ASBISC") }
        verify(exactly = 1) { biznesRadar.valuation("ASBISC") }
        assertTrue(quarterResults.publishedBefore(company.id!!, java.time.LocalDate.parse("2027-01-01"), 50).isNotEmpty())
    }

    @Test
    fun `company without BiznesRadar symbol is scored with neutral valuation`() {
        every { biznesRadar.symbolFor(any()) } answers { throw SymbolNotFoundException(firstArg<Company>()) }
        enqueueAsbisRb23()

        pipeline.process(entry())

        assertEquals(ReportStatus.SCORED, statusOf("497297"))
        verify(exactly = 0) { biznesRadar.quarterly(any()) }
        val warnings = jdbc.sql("SELECT array_to_string(warnings, ',') FROM score").query(String::class.java).single()
        assertTrue("MISSING_DATA" in warnings.split(","))
    }

    private fun pushLogCount() = jdbc.sql("SELECT count(*) FROM push_log").query(Long::class.java).single()

    private fun reportId(sourceRef: String) =
        jdbc.sql("SELECT id FROM report WHERE source_ref = :ref").param("ref", sourceRef).query(Long::class.java).single()

    private fun asbis() = companies.save(Company(null, "ASBISC ENTERPRISES PLC", "CY1000031710", Market.GPW, null, "ASBISC", false, "ASBIS"))

    private fun insertResultsPush(companyId: Long, kind: PeriodKind, periodEnd: String) {
        jdbc.sql(
            "INSERT INTO push_log (kind, title, body, sent_at, company_id, period_kind, period_end) " +
                "VALUES ('RESULTS', 't', 'b', now(), :companyId, :kind, CAST(:periodEnd AS date))"
        )
            .param("companyId", companyId).param("kind", kind.name).param("periodEnd", periodEnd).update()
    }

    @Test
    fun `failed push leaves the report PUSH_PENDING and retryPush sends it once`() {
        every { notifier.send(any()) } throws RuntimeException("fcm down")
        enqueueAsbisRb23()

        pipeline.process(entry())

        assertEquals(ReportStatus.PUSH_PENDING, statusOf("497297"))
        assertEquals(0L, pushLogCount())

        every { notifier.send(any()) } answers { pushLog.save(firstArg<PushMessage>(), clock.instant()) }
        pipeline.retryPush(reportId("497297"))

        assertEquals(ReportStatus.SCORED, statusOf("497297"))
        assertEquals(1L, pushLogCount())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `disabled market gets a score but no push`() {
        AlertFilters(settings).update(listOf(Market.GPW), emptyList())
        enqueueAsbisRb23()

        pipeline.process(entry())

        assertEquals(ReportStatus.SCORED, statusOf("497297"))
        assertTrue(scores.find(reportId("497297")) != null)
        assertEquals(0L, pushLogCount())
        verify(exactly = 0) { notifier.send(any()) }
    }

    @Test
    fun `disabled results event type gets a score but no push`() {
        AlertFilters(settings).update(emptyList(), listOf(EventType.RESULTS))
        enqueueAsbisRb23()

        pipeline.process(entry())

        assertEquals(ReportStatus.SCORED, statusOf("497297"))
        assertTrue(scores.find(reportId("497297")) != null)
        assertEquals(0L, pushLogCount())
        verify(exactly = 0) { notifier.send(any()) }
    }

    @Test
    fun `retryPush for a market disabled meanwhile ends SCORED without sending`() {
        every { notifier.send(any()) } throws RuntimeException("fcm down")
        enqueueAsbisRb23()
        pipeline.process(entry())
        assertEquals(ReportStatus.PUSH_PENDING, statusOf("497297"))

        AlertFilters(settings).update(listOf(Market.GPW), emptyList())
        every { notifier.send(any()) } answers { pushLog.save(firstArg<PushMessage>(), clock.instant()) }
        pipeline.retryPush(reportId("497297"))

        assertEquals(ReportStatus.SCORED, statusOf("497297"))
        assertEquals(0L, pushLogCount())
        verify(exactly = 1) { notifier.send(any()) }
    }

    @Test
    fun `reprocess reuses the saved extraction without calling AI`() {
        enqueueAsbisRb23()
        pipeline.process(entry())

        pipeline.reprocess(reportId("497297"), allowWithoutAttachments = true)

        assertEquals(1, server.requestCount)
        assertEquals(ReportStatus.SCORED, statusOf("497297"))
    }

    @Test
    fun `reprocess handles a report stuck in NEW`() {
        val company = asbis()
        val saved = checkNotNull(
            reports.saveIfNew(
                pl.investor.report.Report(
                    null, company.id!!, Market.GPW, ReportKind.CURRENT, "23/2026", "50", entry().title,
                    Instant.parse("2026-09-21T06:09:21Z"), clock.instant(), "https://x", ReportStatus.NEW,
                )
            )
        )
        enqueueAsbisRb23()

        pipeline.reprocess(saved.id!!, allowWithoutAttachments = false)

        assertEquals(ReportStatus.SCORED, statusOf("50"))
    }

    @Test
    fun `budget alert marker is written first so a failed send is not repeated`() {
        budget.record(AiPool.GPW, 20_000_000, 0)
        every { notifier.send(match { it.title == "Limit AI wyczerpany" }) } throws RuntimeException("fcm down")

        pipeline.process(entry("10"))
        pipeline.process(entry("11"))

        verify(exactly = 1) { notifier.send(match { it.title == "Limit AI wyczerpany" }) }
        assertEquals(ReportStatus.UNSCORED_NO_AI, statusOf("11"))
    }

    @Test
    fun `no-AI scoring of a periodic report takes the push period from the BiznesRadar quarter`() {
        budget.record(AiPool.GPW, 20_000_000, 0)
        val company = asbis()
        val sameDay = QuarterlyResultsParser().parse(fixture("biznesradar/ASBISC-pl-q.html")).first { it.publishedOn != null }
        every { biznesRadar.quarterly("ASBISC") } returns listOf(sameDay)
        val publishedAt = sameDay.publishedOn!!.atTime(10, 0).atZone(java.time.ZoneId.of("Europe/Warsaw")).toInstant()
        insertResultsPush(company.id!!, PeriodKind.QUARTER, sameDay.quarterEnd.toString())

        pipeline.process(entry("60", "Informacja o szacunkowych miesięcznych przychodach", ReportKind.QUARTERLY).copy(publishedAt = publishedAt))

        assertEquals(ReportStatus.SCORED, statusOf("60"))
        verify(exactly = 0) { notifier.send(match { it.title.startsWith("ASBIS (GPW)") }) }
    }

    @Test
    fun `a push for one period kind does not suppress another kind with the same date`() {
        val company = asbis()
        insertResultsPush(company.id!!, PeriodKind.MONTH, "2026-03-31")

        assertTrue(pushLog.resultsPushExists(company.id!!, PeriodKind.MONTH, java.time.LocalDate.parse("2026-03-31")))
        assertTrue(!pushLog.resultsPushExists(company.id!!, PeriodKind.QUARTER, java.time.LocalDate.parse("2026-03-31")))
        insertResultsPush(company.id!!, PeriodKind.QUARTER, "2026-03-31")
        assertEquals(2L, pushLogCount())
    }

    @Test
    fun `existing push for the same company kind and period suppresses a new push`() {
        val company = asbis()
        insertResultsPush(company.id!!, PeriodKind.MONTH, "2026-08-31")
        enqueueAsbisRb23()

        pipeline.process(entry())

        assertEquals(ReportStatus.SCORED, statusOf("497297"))
        verify(exactly = 0) { notifier.send(any()) }
    }

    @Test
    fun `duplicate results push for one company kind and period violates the unique index`() {
        val company = asbis()
        insertResultsPush(company.id!!, PeriodKind.QUARTER, "2026-03-31")

        kotlin.test.assertFailsWith<org.springframework.dao.DuplicateKeyException> {
            insertResultsPush(company.id!!, PeriodKind.QUARTER, "2026-03-31")
        }
    }

    @Test
    fun `BiznesRadar outage creates the company without a symbol`() {
        every { biznesRadar.symbolFor(any()) } throws SourceUnavailableException(Source.BIZNESRADAR, RuntimeException("down"))
        every { biznesRadar.mediumNameFor(any()) } throws SourceUnavailableException(Source.BIZNESRADAR, RuntimeException("down"))

        val company = CompanyResolver(companies, biznesRadar).resolve(entry())

        assertEquals(null, company.biznesradarSymbol)
        assertEquals(null, company.shortName)
    }

    @Test
    fun `company without symbol gets one symbol lookup per day and is completed when it succeeds`() {
        val company = companies.save(Company(null, "ASBISC ENTERPRISES PLC", "CY1000031710", Market.GPW, null, null, false))
        every { biznesRadar.symbolFor(any()) } throws SourceUnavailableException(Source.BIZNESRADAR, RuntimeException("down"))

        refresher.refresh(company)
        refresher.refresh(company)

        verify(exactly = 1) { biznesRadar.symbolFor(any()) }
        verify(exactly = 0) { biznesRadar.quarterly(any()) }

        jdbc.sql("DELETE FROM setting WHERE key LIKE 'BR_SYMBOL_TRIED_%'").update()
        every { biznesRadar.symbolFor(any()) } returns "ASBISC"
        refresher.refresh(company)

        verify(exactly = 1) { biznesRadar.quarterly("ASBISC") }
        assertEquals("ASBISC", companies.findById(company.id!!).biznesradarSymbol)
        assertEquals("ASBIS", companies.findById(company.id!!).shortName)
    }

    private fun storedReport(sourceRef: String, kind: ReportKind, status: ReportStatus, publishedAt: Instant = Instant.parse("2026-09-21T06:09:21Z")): Long {
        val company = companies.findById(1L)
        return checkNotNull(
            reports.saveIfNew(
                pl.investor.report.Report(
                    null, company.id!!, Market.GPW, kind, "23/2026", sourceRef, "Informacja o szacunkowych miesięcznych przychodach",
                    publishedAt, clock.instant(), "https://x", status,
                )
            )?.id
        )
    }

    @Test
    fun `parallel reprocess of the same report runs once`() {
        pendingAttachments += "70"
        enqueueResultsWithoutNumbers()
        pipeline.process(entry("70"))
        val id = reportId("70")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { contentClient.fetch(any(), any()) } answers {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            content("70", secondArg())
        }
        enqueueAsbisRb23()
        val executor = Executors.newSingleThreadExecutor()

        val first = executor.submit<Boolean> { pipeline.reprocess(id, allowWithoutAttachments = true) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val second = pipeline.reprocess(id, allowWithoutAttachments = true)
        val secondPush = pipeline.retryPush(id)
        release.countDown()

        assertTrue(first.get(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertFalse(second)
        assertFalse(secondPush)
        assertEquals(2, server.requestCount)
        assertEquals(ReportStatus.SCORED, statusOf("70"))
    }

    @Test
    fun `periodic report without verified revenue waits for data and current one is judged as news`() {
        asbis()
        val empty = VerifiedExtraction(
            Extraction(ReportCategory.RESULTS, emptyList(), Outlook(OutlookTone.NONE, null), false, null, false),
            emptySet(),
        )
        val periodic = storedReport("80", ReportKind.QUARTERLY, ReportStatus.NEW)
        val current = storedReport("81", ReportKind.CURRENT, ReportStatus.NEW)
        extractions.save(periodic, empty)
        extractions.save(current, empty)

        enqueueNews(category = NewsCategory.GUIDANCE, impact = 40)

        pipeline.reprocess(periodic, allowWithoutAttachments = true)
        pipeline.reprocess(current, allowWithoutAttachments = true)

        assertEquals(ReportStatus.PENDING_DATA, statusOf("80"))
        assertEquals("RESULTS", eventTypeOf("80"))
        assertEquals(ReportStatus.SCORED, statusOf("81"))
        assertEquals("NEWS", eventTypeOf("81"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `pending data is scored without AI once BiznesRadar has a quarter from the report day`() {
        asbis()
        val latest = QuarterlyResultsParser().parse(fixture("biznesradar/ASBISC-pl-q.html")).filter { it.publishedOn != null }.maxBy { it.publishedOn!! }
        every { biznesRadar.quarterly("ASBISC") } returns listOf(latest)
        val id = storedReport("82", ReportKind.QUARTERLY, ReportStatus.PENDING_DATA, latest.publishedOn!!.atTime(10, 0).atZone(java.time.ZoneId.of("Europe/Warsaw")).toInstant())

        assertTrue(pipeline.retryPendingData(id))

        assertEquals(ReportStatus.SCORED, statusOf("82"))
        assertEquals(0, server.requestCount)
        verify(exactly = 1) { notifier.send(match { it.title.startsWith("ASBIS (GPW)") }) }
    }

    @Test
    fun `GPW company that XTB does not offer is skipped by default before any AI call`() {
        every { xtb.stocks(XtbCountry.PL) } returns listOf(XtbStock("XTB", "XTB SA"))

        pipeline.process(entry())

        assertEquals(ReportStatus.SKIPPED_NOT_ON_XTB, statusOf("497297"))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
        verify(exactly = 0) { notifier.send(any()) }
        assertFalse(companies.findById(reports.findById(reportId("497297")).companyId).availableOnXtb)
    }

    @Test
    fun `XTB list that cannot be downloaded right after a restart keeps the report NEW for the retry job`() {
        every { xtb.stocks(XtbCountry.PL) } throws SourceUnavailableException(Source.XTB, RuntimeException("down"))

        pipeline.process(entry())

        assertEquals(ReportStatus.NEW, statusOf("497297"))
        assertEquals(0, server.requestCount)

        every { xtb.stocks(XtbCountry.PL) } returns listOf(XtbStock("ASB", "ASBISC Enterprises PLC"))
        enqueueAsbisRb23()
        pipeline.reprocess(reportId("497297"), allowWithoutAttachments = false)

        assertEquals(ReportStatus.SCORED, statusOf("497297"))
    }

    @Test
    fun `BiznesRadar outage while looking up the symbol keeps a GPW report NEW when XTB has no such name`() {
        every { xtb.stocks(XtbCountry.PL) } returns listOf(XtbStock("ASB", "ASBIS Group"))
        every { biznesRadar.symbolFor(any()) } throws SourceUnavailableException(Source.BIZNESRADAR, RuntimeException("down"))

        pipeline.process(entry())

        assertEquals(ReportStatus.NEW, statusOf("497297"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `pending data of a company no longer on XTB is skipped instead of scored, because the reason needs AI`() {
        asbis()
        val latest = QuarterlyResultsParser().parse(fixture("biznesradar/ASBISC-pl-q.html")).filter { it.publishedOn != null }.maxBy { it.publishedOn!! }
        every { biznesRadar.quarterly("ASBISC") } returns listOf(latest)
        every { xtb.stocks(XtbCountry.PL) } returns listOf(XtbStock("XTB", "XTB SA"))
        val id = storedReport("84", ReportKind.QUARTERLY, ReportStatus.PENDING_DATA, latest.publishedOn!!.atTime(10, 0).atZone(java.time.ZoneId.of("Europe/Warsaw")).toInstant())

        assertTrue(pipeline.retryPendingData(id))

        assertEquals(ReportStatus.SKIPPED_NOT_ON_XTB, statusOf("84"))
        verify(exactly = 0) { reasonWriter.write(any(), any(), any()) }
        verify(exactly = 0) { notifier.send(any()) }
    }

    @Test
    fun `pending data stays pending while BiznesRadar has nothing from the report day`() {
        asbis()
        every { biznesRadar.quarterly("ASBISC") } returns emptyList()
        val id = storedReport("83", ReportKind.QUARTERLY, ReportStatus.PENDING_DATA)

        assertTrue(pipeline.retryPendingData(id))

        assertEquals(ReportStatus.PENDING_DATA, statusOf("83"))
        verify(exactly = 0) { notifier.send(any()) }
    }

    private val strongReportDay: LocalDate = LocalDate.parse("2026-08-06")
    private val strongReportAt: Instant = strongReportDay.atTime(16, 0).atZone(WARSAW).toInstant()

    private fun strongQuarters(currentPublishedOn: LocalDate) = listOf(
        ParsedQuarter(LocalDate.parse("2024-03-31"), BigDecimal("100"), BigDecimal("10"), BigDecimal("8"), LocalDate.parse("2024-05-15")),
        ParsedQuarter(LocalDate.parse("2024-06-30"), BigDecimal("100"), BigDecimal("10"), BigDecimal("8"), LocalDate.parse("2024-08-14")),
        ParsedQuarter(LocalDate.parse("2024-09-30"), BigDecimal("100"), BigDecimal("10"), BigDecimal("8"), LocalDate.parse("2024-11-14")),
        ParsedQuarter(LocalDate.parse("2024-12-31"), BigDecimal("100"), BigDecimal("10"), BigDecimal("8"), LocalDate.parse("2025-03-01")),
        ParsedQuarter(LocalDate.parse("2025-03-31"), BigDecimal("105"), BigDecimal("10"), BigDecimal("8"), LocalDate.parse("2025-05-15")),
        ParsedQuarter(LocalDate.parse("2025-06-30"), BigDecimal("100"), BigDecimal("10"), BigDecimal("8"), LocalDate.parse("2025-08-14")),
        ParsedQuarter(LocalDate.parse("2025-09-30"), BigDecimal("110"), BigDecimal("10"), BigDecimal("8"), LocalDate.parse("2025-11-14")),
        ParsedQuarter(LocalDate.parse("2025-12-31"), BigDecimal("108"), BigDecimal("10"), BigDecimal("8"), LocalDate.parse("2026-03-01")),
        ParsedQuarter(LocalDate.parse("2026-03-31"), BigDecimal("112"), BigDecimal("10"), BigDecimal("8"), LocalDate.parse("2026-05-15")),
        ParsedQuarter(LocalDate.parse("2026-06-30"), BigDecimal("200"), BigDecimal("40"), BigDecimal("30"), currentPublishedOn),
    )

    private fun givenStrongBiznesRadarQuarter(currentPublishedOn: LocalDate = strongReportDay) {
        settings.put("PUSH_THRESHOLD", "70")
        every { biznesRadar.quarterly("ASBISC") } returns strongQuarters(currentPublishedOn)
        every { biznesRadar.valuation("ASBISC") } returns emptyList()
    }

    private fun storedExtraction(reportId: Long, tone: OutlookTone) = extractions.save(
        reportId,
        VerifiedExtraction(Extraction(ReportCategory.RESULTS, emptyList(), Outlook(tone, null), false, null, false), emptySet()),
    )

    private fun pushedPeriod(): Pair<String, String> =
        jdbc.sql("SELECT period_kind, CAST(period_end AS text) FROM push_log WHERE kind = 'RESULTS'")
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }
            .single()

    @Test
    fun `pending data retry reaches the real threshold with the BiznesRadar quarter of the report day`() {
        asbis()
        givenStrongBiznesRadarQuarter()
        val id = storedReport("84", ReportKind.QUARTERLY, ReportStatus.PENDING_DATA, strongReportAt)
        storedExtraction(id, OutlookTone.NONE)

        assertTrue(pipeline.retryPendingData(id))

        val score = checkNotNull(scores.find(id)).score
        assertEquals(77.5, score.total, 0.001)
        assertEquals(ReportStatus.SCORED, statusOf("84"))
        verify(exactly = 1) { notifier.send(match { it.title.startsWith("ASBIS (GPW) · WYNIKI · ocena") }) }
        assertEquals("QUARTER" to "2026-06-30", pushedPeriod())
    }

    @Test
    fun `no-AI scoring of a periodic report reaches the real threshold with the BiznesRadar quarter of the report day`() {
        budget.record(AiPool.GPW, 20_000_000, 0)
        asbis()
        givenStrongBiznesRadarQuarter()

        pipeline.process(entry("85", "Raport kwartalny QSr 2/2026", ReportKind.QUARTERLY).copy(publishedAt = strongReportAt))

        val score = checkNotNull(scores.find(reportId("85"))).score
        assertEquals(77.5, score.total, 0.001)
        assertTrue(Warning.NO_AI in score.warnings)
        assertEquals(ReportStatus.SCORED, statusOf("85"))
        assertEquals(0, server.requestCount)
        verify(exactly = 1) { notifier.send(match { it.title.startsWith("ASBIS (GPW) · WYNIKI · ocena") }) }
        assertEquals("QUARTER" to "2026-06-30", pushedPeriod())
    }

    @Test
    fun `BiznesRadar quarter published more than two days after the report is not used`() {
        val company = asbis()
        givenStrongBiznesRadarQuarter(currentPublishedOn = strongReportDay.plusDays(3))
        val id = storedReport("86", ReportKind.QUARTERLY, ReportStatus.PENDING_DATA, strongReportAt)
        storedExtraction(id, OutlookTone.NONE)

        assertTrue(pipeline.retryPendingData(id))

        assertEquals(null, assembler.currentQuarter(company, reports.findById(id)))
        assertEquals(ReportStatus.PENDING_DATA, statusOf("86"))
        assertEquals(null, scores.find(id))
        verify(exactly = 0) { notifier.send(any()) }
    }

    @Test
    fun `retried pending data report keeps its stored outlook`() {
        asbis()
        givenStrongBiznesRadarQuarter(currentPublishedOn = strongReportDay.plusDays(1))
        val id = storedReport("87", ReportKind.QUARTERLY, ReportStatus.PENDING_DATA, strongReportAt)
        storedExtraction(id, OutlookTone.POSITIVE)

        assertTrue(pipeline.retryPendingData(id))

        val score = checkNotNull(scores.find(id)).score
        assertEquals(10.0, score.components.outlook)
        assertFalse(Warning.NO_AI in score.warnings)
        assertEquals(82.5, score.total, 0.001)
        assertEquals("QUARTER" to "2026-06-30", pushedPeriod())
    }

    @Test
    fun `unavailable source leaves the report NEW for retry instead of FAILED`() {
        every { contentClient.fetch(any(), any()) } throws SourceUnavailableException(Source.BANKIER, RuntimeException("down"))

        pipeline.process(entry("90"))

        assertEquals(ReportStatus.NEW, statusOf("90"))
    }


    private val newsTitle = "Zawarcie istotnej umowy na dostawy stacji transformatorowych"

    private fun newsResponse(
        category: NewsCategory,
        direction: NewsDirection,
        impact: Int,
        reason: String = "Notowanie we Frankfurcie poszerza grono inwestorów. Drugie zdanie.",
        alreadyAnnounced: Boolean = false,
    ) =
        json(
            """{"id":"m","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":"{\"category\":\"${category.name}\",\"direction\":\"${direction.name}\",\"impact\":$impact,\"contractValue\":null,\"reason\":\"$reason\",\"alreadyAnnounced\":$alreadyAnnounced}"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":10}}"""
        )

    private fun enqueueNews(category: NewsCategory = NewsCategory.LISTING, direction: NewsDirection = NewsDirection.POSITIVE, impact: Int = 80) =
        server.enqueue(newsResponse(category, direction, impact))

    private fun eventTypeOf(sourceRef: String): String =
        jdbc.sql("SELECT event_type FROM report WHERE source_ref = :ref").param("ref", sourceRef).query(String::class.java).single()

    private fun newsPushes() = jdbc.sql("SELECT count(*) FROM push_log WHERE kind = 'NEWS'").query(Long::class.java).single()

    private fun listedIds() = alerts.newest(100).map { it.id }

    @Test
    fun `routine announcement stays irrelevant without AI`() {
        pipeline.process(entry("100", "Zawiadomienie w trybie art. 69 ustawy o ofercie publicznej"))

        assertEquals(ReportStatus.IRRELEVANT, statusOf("100"))
        assertEquals("RESULTS", eventTypeOf("100"))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
    }

    @Test
    fun `already announced news is stored capped at 30 with the flag, not pushed and not listed`() {
        server.enqueue(newsResponse(NewsCategory.CONTRACT, NewsDirection.POSITIVE, 82, "Rynek znał tę umowę wcześniej.", alreadyAnnounced = true))

        pipeline.process(entry("130", newsTitle))

        val id = reportId("130")
        assertEquals(ReportStatus.SCORED, statusOf("130"))
        assertEquals(30.0, checkNotNull(scores.find(id)).score.total, 0.001)
        assertEquals(true, newsAssessments.find(id)?.alreadyAnnounced)
        assertEquals(0L, newsPushes())
        assertFalse(id in listedIds())
    }

    @Test
    fun `news on a penny stock carries the penny stock warning, also after reprocessing`() {
        jdbc.sql("INSERT INTO daily_quote (instrument, date, close, volume, turnover) VALUES ('CY1000031710', DATE '2026-09-19', 0.40, 1000, 400)").update()
        try {
            enqueueNews(impact = 60)

            pipeline.process(entry("131", newsTitle))
            val id = reportId("131")
            jdbc.sql("UPDATE score SET warnings = '{}' WHERE report_id = :id").param("id", id).update()
            pipeline.reprocess(id, allowWithoutAttachments = false)

            assertEquals(setOf(Warning.PENNY_STOCK), checkNotNull(scores.find(id)).score.warnings)
            assertEquals(1, server.requestCount)
        } finally {
            jdbc.sql("DELETE FROM daily_quote WHERE instrument = 'CY1000031710' AND date = DATE '2026-09-19'").update()
        }
    }

    @Test
    fun `strong positive news is assessed, scored, pushed and listed`() {
        settings.put("PUSH_THRESHOLD", "70")
        enqueueNews(impact = 80)

        pipeline.process(entry("101", newsTitle))

        val id = reportId("101")
        assertEquals(ReportStatus.SCORED, statusOf("101"))
        assertEquals("NEWS", eventTypeOf("101"))
        assertEquals(80.0, checkNotNull(scores.find(id)).score.total, 0.001)
        assertEquals(NewsCategory.LISTING, newsAssessments.find(id)?.category)
        verify(exactly = 1) {
            notifier.send(match { it.title == "ASBIS (GPW) · WYDARZENIE · ocena 80" && it.body == "Notowanie we Frankfurcie poszerza grono inwestorów." })
        }
        assertEquals(1L, newsPushes())
        assertTrue(id in listedIds())
        verify(exactly = 1) { contentClient.fetch(any(), false) }
        val request = server.takeRequest().body.readUtf8()
        assertTrue("<komunikat>" in request && "max_tokens\":2000" in request)
        assertTrue(aiUsage.costUsd(LocalDate.parse("2026-09-01"), AiPool.NEWS).signum() > 0)
        assertEquals(0, aiUsage.costUsd(LocalDate.parse("2026-09-01"), AiPool.GPW).signum())
    }

    @Test
    fun `news below the threshold is stored and listed but not pushed`() {
        settings.put("PUSH_THRESHOLD", "70")
        enqueueNews(impact = 60)

        pipeline.process(entry("102", newsTitle))

        assertEquals(ReportStatus.SCORED, statusOf("102"))
        verify(exactly = 0) { notifier.send(any()) }
        assertTrue(reportId("102") in listedIds())
    }

    @Test
    fun `negative news is stored with zero and not listed`() {
        settings.put("PUSH_THRESHOLD", "70")
        enqueueNews(category = NewsCategory.SHARE_ISSUE, direction = NewsDirection.NEGATIVE, impact = 90)

        pipeline.process(entry("103", "Niedojście do skutku emisji akcji serii C"))

        val id = reportId("103")
        assertEquals(ReportStatus.SCORED, statusOf("103"))
        assertEquals(0.0, checkNotNull(scores.find(id)).score.total, 0.001)
        assertEquals(NewsDirection.NEGATIVE, newsAssessments.find(id)?.direction)
        verify(exactly = 0) { notifier.send(any()) }
        assertFalse(id in listedIds())
        assertEquals(null, alerts.find(id))
    }

    @Test
    fun `results title whose extraction says CONTRACT is assessed as news from the same content`() {
        server.enqueue(
            json(
                """{"id":"m","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":"{\"category\":\"CONTRACT\",\"periods\":[],\"outlook\":{\"tone\":\"NONE\",\"quote\":null},\"oneOffQuarter\":false,\"oneOffQuote\":null,\"cyclicalIndustry\":false}"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":10}}"""
            )
        )
        enqueueNews(category = NewsCategory.CONTRACT, impact = 55)

        pipeline.process(entry("104", "Zawarcie przez spółkę zależną RCEkoenergia sp. z o.o. umowy wieloletniej na sprzedaż ciepła do miasta Czechowice-Dziedzice"))

        val id = reportId("104")
        assertEquals(ReportStatus.SCORED, statusOf("104"))
        assertEquals("NEWS", eventTypeOf("104"))
        assertEquals(2, server.requestCount)
        verify(exactly = 1) { contentClient.fetch(any(), any()) }
        assertEquals(null, extractions.find(id))
        assertEquals(55.0, checkNotNull(scores.find(id)).score.total, 0.001)
    }

    @Test
    fun `unavailable AI keeps news pending and the retry goes straight to the news assessment`() {
        server.enqueue(MockResponse().setResponseCode(529).setBody("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        enqueueNews(impact = 75)

        pipeline.process(entry("105", newsTitle))
        assertEquals(ReportStatus.PENDING_AI, statusOf("105"))
        assertEquals("NEWS", eventTypeOf("105"))

        pipeline.reprocess(reportId("105"), allowWithoutAttachments = false)

        assertEquals(ReportStatus.SCORED, statusOf("105"))
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertTrue("<komunikat>" in server.takeRequest().body.readUtf8())
    }

    @Test
    fun `exhausted NEWS pool leaves news unassessed and unlisted with one budget push`() {
        budget.record(AiPool.NEWS, 20_000_000, 0)

        pipeline.process(entry("106", newsTitle))
        pipeline.process(entry("107", "Zawarcie umowy inwestycyjnej"))

        assertEquals(ReportStatus.UNSCORED_NO_AI, statusOf("106"))
        assertEquals(ReportStatus.UNSCORED_NO_AI, statusOf("107"))
        assertEquals(0, server.requestCount)
        assertFalse(reportId("106") in listedIds())
        verify(exactly = 1) { notifier.send(match { it.title == "Limit AI wyczerpany" && it.body == "Do końca miesiąca wydarzenia nie są oceniane" }) }
        assertTrue(settings.find("AI_BUDGET_NOTIFIED_NEWS_2026-09") != null)
    }

    @Test
    fun `exhausted NEWS pool does not stop results extraction`() {
        budget.record(AiPool.NEWS, 20_000_000, 0)
        enqueueAsbisRb23()

        pipeline.process(entry())

        assertEquals(ReportStatus.SCORED, statusOf("497297"))
        verify(exactly = 1) { notifier.send(match { it.title.startsWith("ASBIS (GPW) · WYNIKI · ocena") }) }
    }

    @Test
    fun `disabled NEWS pushes keep the news listed without a push`() {
        settings.put("PUSH_THRESHOLD", "70")
        AlertFilters(settings).update(emptyList(), listOf(EventType.NEWS))
        enqueueNews(impact = 90)

        pipeline.process(entry("108", newsTitle))

        assertEquals(ReportStatus.SCORED, statusOf("108"))
        verify(exactly = 0) { notifier.send(any()) }
        assertTrue(reportId("108") in listedIds())
    }

    @Test
    fun `failed news push is retried once and not repeated`() {
        settings.put("PUSH_THRESHOLD", "70")
        every { notifier.send(any()) } throws RuntimeException("fcm down")
        enqueueNews(impact = 85)

        pipeline.process(entry("109", newsTitle))
        assertEquals(ReportStatus.PUSH_PENDING, statusOf("109"))

        every { notifier.send(any()) } answers { pushLog.save(firstArg<PushMessage>(), clock.instant()) }
        pipeline.retryPush(reportId("109"))
        pipeline.retryPush(reportId("109"))

        assertEquals(ReportStatus.SCORED, statusOf("109"))
        assertEquals(1L, newsPushes())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `news prompt carries the last twelve months revenue from stored BiznesRadar quarters`() {
        enqueueNews(impact = 40)

        pipeline.process(entry("110", newsTitle))

        val quarters = QuarterlyResultsParser().parse(fixture("biznesradar/ASBISC-pl-q.html"))
            .filter { it.publishedOn != null && it.publishedOn!! <= LocalDate.parse("2026-09-21") }
            .sortedByDescending { it.quarterEnd }
            .take(4)
        val expected = quarters.sumOf { it.revenue!! }.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString()
        assertTrue("Przychody z ostatnich 12 miesięcy: $expected tys. zł" in server.takeRequest().body.readUtf8())
    }

    @Test
    fun `English correction title is skipped`() {
        pipeline.process(entry("111", "Correction of current report no 12/2026"))

        assertEquals(ReportStatus.CORRECTION_SKIPPED, statusOf("111"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `report without a title stays irrelevant`() {
        pipeline.process(entry("112", null))

        assertEquals(ReportStatus.IRRELEVANT, statusOf("112"))
        assertEquals(0, server.requestCount)
    }


    private fun enqueueExtraction(category: ReportCategory) = server.enqueue(
        json(
            """{"id":"m","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":"{\"category\":\"${category.name}\",\"periods\":[],\"outlook\":{\"tone\":\"NONE\",\"quote\":null},\"oneOffQuarter\":false,\"oneOffQuote\":null,\"cyclicalIndustry\":false}"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":10}}"""
        )
    )

    @Test
    fun `neutral news is not pushed even with threshold 0`() {
        enqueueNews(category = NewsCategory.OTHER, direction = NewsDirection.NEUTRAL, impact = 60)

        pipeline.process(entry("120", newsTitle))

        assertEquals(ReportStatus.SCORED, statusOf("120"))
        verify(exactly = 0) { notifier.send(any()) }
    }

    @Test
    fun `news below the listing floor is not pushed even when the threshold is lower`() {
        settings.put("PUSH_THRESHOLD", "30")
        enqueueNews(impact = 40)

        pipeline.process(entry("121", newsTitle))

        assertEquals(ReportStatus.SCORED, statusOf("121"))
        verify(exactly = 0) { notifier.send(any()) }
    }

    @Test
    fun `news above the listing floor and the threshold is pushed with the company id`() {
        settings.put("PUSH_THRESHOLD", "30")
        enqueueNews(impact = 55)

        pipeline.process(entry("122", newsTitle))

        verify(exactly = 1) { notifier.send(match { it.title == "ASBIS (GPW) · WYDARZENIE · ocena 55" }) }
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM push_log WHERE kind = 'NEWS' AND company_id IS NOT NULL").query(Long::class.java).single())
    }

    @Test
    fun `pending news push that no longer qualifies ends SCORED without sending`() {
        every { notifier.send(any()) } throws RuntimeException("fcm down")
        enqueueNews(impact = 80)
        pipeline.process(entry("123", newsTitle))
        assertEquals(ReportStatus.PUSH_PENDING, statusOf("123"))
        every { notifier.send(any()) } answers { pushLog.save(firstArg<PushMessage>(), clock.instant()) }
        val id = reportId("123")
        jdbc.sql("UPDATE news_assessment SET impact = 45 WHERE report_id = :id").param("id", id).update()

        pipeline.retryPush(id)

        assertEquals(ReportStatus.SCORED, statusOf("123"))
        assertEquals(0L, newsPushes())
    }

    @Test
    fun `results title with a routine announcement classified as other stays irrelevant`() {
        enqueueExtraction(ReportCategory.OTHER)

        pipeline.process(entry("124", "Informacja o transakcjach na akcjach Spółki uzyskana w trybie art. 19 MAR"))

        assertEquals(ReportStatus.IRRELEVANT, statusOf("124"))
        assertEquals("RESULTS", eventTypeOf("124"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `unavailable source during news keeps it NEW and the retry goes straight to news`() {
        every { contentClient.fetch(match { it.sourceRef == "125" }, any()) } throws SourceUnavailableException(Source.GPW_LISTING, RuntimeException("down"))

        pipeline.process(entry("125", newsTitle))

        assertEquals(ReportStatus.NEW, statusOf("125"))
        assertEquals("NEWS", eventTypeOf("125"))
        every { contentClient.fetch(any(), any()) } answers { content(firstArg<ListingEntry>().sourceRef, secondArg()) }
        enqueueNews(impact = 66)

        pipeline.reprocess(reportId("125"), allowWithoutAttachments = false)

        assertEquals(ReportStatus.SCORED, statusOf("125"))
        assertEquals(1, server.requestCount)
        assertTrue("<komunikat>" in server.takeRequest().body.readUtf8())
    }

    @Test
    fun `reprocessing assessed news makes no second AI call and pushes once`() {
        settings.put("PUSH_THRESHOLD", "70")
        enqueueNews(impact = 80)
        pipeline.process(entry("126", newsTitle))
        val id = reportId("126")
        reports.updateStatus(id, ReportStatus.NEW, clock.instant())

        pipeline.reprocess(id, allowWithoutAttachments = false)

        assertEquals(ReportStatus.SCORED, statusOf("126"))
        assertEquals(1, server.requestCount)
        assertEquals(1L, newsPushes())
        verify(exactly = 1) { notifier.send(any()) }
    }

    @Test
    fun `rejected API key in the news path keeps news pending and pushes the key alert once a day`() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")) }

        pipeline.process(entry("127", newsTitle))
        pipeline.process(entry("128", "Zawarcie umowy inwestycyjnej"))

        assertEquals(ReportStatus.PENDING_AI, statusOf("127"))
        assertEquals(ReportStatus.PENDING_AI, statusOf("128"))
        assertEquals("NEWS", eventTypeOf("127"))
        verify(exactly = 1) { notifier.send(match { it.title == "Claude odrzuca klucz API" }) }
    }

    @Test
    fun `current results report without revenue and without attachments is judged as news after one extraction`() {
        settings.put("PUSH_THRESHOLD", "70")
        fixtureBySourceRef["129"] = "reports/crj-prelim-q1-2026.html"
        enqueueExtraction(ReportCategory.RESULTS)
        enqueueNews(category = NewsCategory.GUIDANCE, impact = 75)

        pipeline.process(entry("129", "Informacja poufna na temat wyników osiągniętych przez Emitenta w III kwartale 2026 r."))

        val id = reportId("129")
        assertEquals(ReportStatus.SCORED, statusOf("129"))
        assertEquals("NEWS", eventTypeOf("129"))
        assertEquals(2, server.requestCount)
        verify(exactly = 1) { contentClient.fetch(any(), false) }
        assertEquals(75.0, checkNotNull(scores.find(id)).score.total, 0.001)
        verify(exactly = 1) { notifier.send(match { it.title == "ASBIS (GPW) · WYDARZENIE · ocena 75" }) }
        assertTrue(jdbc.sql("SELECT count(*) FROM report WHERE source_ref = '129' AND status = 'PENDING_ATTACHMENTS'").query(Long::class.java).single() == 0L)
    }

    @Test
    fun `report without a known kind and a routine English title stays irrelevant without AI or attachments`() {
        pipeline.process(entry("130", "Notification under Art. 148 b of the POSA", ReportKind.OTHER))
        pipeline.process(entry("131", "Minutes of meeting from an Extraordinary General Meeting of shareholders of \"Sopharma\" AD held on October 30, 2026", ReportKind.OTHER))

        assertEquals(ReportStatus.IRRELEVANT, statusOf("130"))
        assertEquals(ReportStatus.IRRELEVANT, statusOf("131"))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
    }

    @Test
    fun `report without a known kind and an English results title is read body first`() {
        enqueueAsbisRb23()

        pipeline.process(entry("132", "Interim consolidated financial statements for Q3 2026", ReportKind.OTHER))

        assertEquals(ReportStatus.SCORED, statusOf("132"))
        assertEquals(1, server.requestCount)
        verify(exactly = 1) { contentClient.fetch(any(), false) }
        verify(exactly = 0) { contentClient.fetch(any(), true) }
    }

    @Test
    fun `report without a known kind waiting for attachments is rerouted by its title`() {
        asbis()
        val id = checkNotNull(
            reports.saveIfNew(
                pl.investor.report.Report(
                    null, 1L, Market.GPW, ReportKind.OTHER, "85/2026", "499856", "Notification under Art. 148 b of the POSA",
                    Instant.parse("2026-11-02T14:09:24Z"), clock.instant(), "https://www.gpw.pl/komunikat?geru_id=499856", ReportStatus.PENDING_ATTACHMENTS,
                )
            )?.id
        )

        pipeline.reprocess(id, allowWithoutAttachments = false)

        assertEquals(ReportStatus.IRRELEVANT, statusOf("499856"))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
    }

    @Test
    fun `periodic report whose Bankier article has no attachment links yet waits for attachments`() {
        val http = mockk<pl.investor.source.PoliteHttpClient>()
        val locator = mockk<pl.investor.source.content.BankierReportLocator>()
        val realClient = ReportContentClient(http, ReportContentParser(), locator, pl.investor.source.content.AttachmentTextExtractor(http), pl.investor.source.nasdaq.NasdaqViewParser(), pl.investor.source.eqs.EqsReleaseParser(), pl.investor.source.euronext.EuronextReleaseParser())
        every { http.get(Source.GPW_LISTING, any()) } returns fixture("reports/asbis-ps-2026.html")
        every { locator.find(any()) } returns "https://www.bankier.pl/wiadomosc/asbis.html"
        every { http.get(Source.BANKIER, "https://www.bankier.pl/wiadomosc/asbis.html") } returns "<html><body><p>Raport półroczny</p></body></html>"
        every { contentClient.fetch(any(), any()) } answers { realClient.fetch(firstArg(), secondArg()) }

        pipeline.process(entry("134", null, ReportKind.HALF_YEARLY))

        assertEquals(ReportStatus.PENDING_ATTACHMENTS, statusOf("134"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `report without a known kind does not wait for an unsupported-only attachment page`() {
        enqueueResultsWithoutNumbers()
        enqueueNews(category = NewsCategory.GUIDANCE, impact = 40)
        every { contentClient.fetch(match { it.sourceRef == "133" }, false) } returns
            ReportContentParser().parse(fixture("reports/asbis-rb-23-2026.html")).copy(attachmentUrls = listOf("https://www.gpw.pl/attachment/zal01.doc,20261102"))

        pipeline.process(entry("133", "Interim consolidated financial statements for Q3 2026", ReportKind.OTHER))

        assertEquals(ReportStatus.SCORED, statusOf("133"))
        assertEquals("NEWS", eventTypeOf("133"))
        assertEquals(2, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), true) }
    }
}
