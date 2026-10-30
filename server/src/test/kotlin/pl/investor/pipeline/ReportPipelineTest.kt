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
import pl.investor.notification.PushLogRepository
import pl.investor.notification.PushMessage
import pl.investor.notification.PushMessageFactory
import pl.investor.notification.PushNotifier
import pl.investor.notification.ReasonWriter
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.scoring.ScoreRepository
import pl.investor.scoring.ScoringInputAssembler
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

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-21T06:15:00Z"), ZoneOffset.UTC)
    private val notifier = mockk<PushNotifier>(relaxed = true)
    private val contentClient = mockk<ReportContentClient>()
    private val biznesRadar = mockk<BiznesRadarClient>()
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
        every { biznesRadar.quarterly("ASBISC") } returns QuarterlyResultsParser().parse(fixture("biznesradar/ASBISC-pl-q.html"))
        every { biznesRadar.valuation("ASBISC") } returns ValuationParser().parse(fixture("biznesradar/ASBISC-valuation.html"))
        every { contentClient.fetch(any(), any()) } answers { content(firstArg<ListingEntry>().sourceRef, secondArg()) }
        every { reasonWriter.write(any(), any(), any()) } returns "Przychody +91% r/r. Reszta uzasadnienia."
        every { notifier.send(any()) } answers { pushLog.save(firstArg<PushMessage>(), clock.instant()) }
        refresher = CompanyDataRefresher(biznesRadar, companies, quarterResults, valuations, settings, clock)
        pipeline = ReportPipeline(
            companies, reports, CompanyResolver(companies, biznesRadar), contentClient, budget,
            ClaudeExtractor(budget, anthropic), QuoteVerifier(), extractions, revenuePeriods, quarterResults,
            refresher, assembler, reasonWriter, scores, settings, PushThreshold(settings), pushLog, notifier, PushMessageFactory(), clock,
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun fixture(path: String) =
        checkNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readText()

    private fun content(sourceRef: String, includeAttachments: Boolean): ReportContent {
        val parsed = ReportContentParser().parse(fixture(fixtureBySourceRef[sourceRef] ?: "reports/asbis-rb-23-2026.html"))
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
    fun `periodic report without verified revenue waits for data and current one is unscored`() {
        asbis()
        val empty = VerifiedExtraction(
            Extraction(ReportCategory.RESULTS, emptyList(), Outlook(OutlookTone.NONE, null), false, null, false),
            emptySet(),
        )
        val periodic = storedReport("80", ReportKind.QUARTERLY, ReportStatus.NEW)
        val current = storedReport("81", ReportKind.CURRENT, ReportStatus.NEW)
        extractions.save(periodic, empty)
        extractions.save(current, empty)

        pipeline.reprocess(periodic, allowWithoutAttachments = true)
        pipeline.reprocess(current, allowWithoutAttachments = true)

        assertEquals(ReportStatus.PENDING_DATA, statusOf("80"))
        assertEquals(ReportStatus.UNSCORED_NO_DATA, statusOf("81"))
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
}
