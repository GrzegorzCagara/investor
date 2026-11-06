package pl.investor.pipeline

import com.anthropic.client.AnthropicClient
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
import pl.investor.extraction.ExtractionRepository
import pl.investor.extraction.QuoteVerifier
import pl.investor.history.QuarterResultRepository
import pl.investor.history.QuoteRepository
import pl.investor.history.RevenuePeriodRepository
import pl.investor.history.ValuationRepository
import pl.investor.news.NewsAssessmentRepository
import pl.investor.news.NewsAssessor
import pl.investor.news.NewsContextAssembler
import pl.investor.notification.PushLogRepository
import pl.investor.notification.PushMessage
import pl.investor.notification.PushMessageFactory
import pl.investor.notification.PushNotifier
import pl.investor.notification.ReasonWriter
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.scoring.ScoreRepository
import pl.investor.scoring.ScoringInputAssembler
import pl.investor.scoring.Warning
import pl.investor.settings.AlertFilters
import pl.investor.settings.PushThreshold
import pl.investor.settings.SettingRepository
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.source.avanza.AvanzaClient
import pl.investor.source.avanza.AvanzaParser
import pl.investor.source.avanza.AvanzaStock
import pl.investor.source.avanza.AvanzaStockNotFoundException
import pl.investor.source.biznesradar.BiznesRadarClient
import pl.investor.source.content.ReportContent
import pl.investor.source.content.ReportContentClient
import pl.investor.source.eqs.EqsListingParser
import pl.investor.source.eqs.EqsReleaseParser
import pl.investor.source.gpw.ListingEntry
import pl.investor.source.quotes.AvanzaQuotes
import pl.investor.source.xtb.XtbAvailability
import pl.investor.source.xtb.XtbClient
import pl.investor.source.xtb.XtbCountry
import pl.investor.source.xtb.XtbStock
import pl.investor.source.consensus.ConsensusNoteFinder
import pl.investor.support.PostgresTestBase
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GermanPipelineTest : PostgresTestBase() {

    @Autowired lateinit var consensusNoteFinder: ConsensusNoteFinder
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var extractions: ExtractionRepository
    @Autowired lateinit var revenuePeriods: RevenuePeriodRepository
    @Autowired lateinit var quarterResults: QuarterResultRepository
    @Autowired lateinit var valuations: ValuationRepository
    @Autowired lateinit var quotes: QuoteRepository
    @Autowired lateinit var assembler: ScoringInputAssembler
    @Autowired lateinit var scores: ScoreRepository
    @Autowired lateinit var settings: SettingRepository
    @Autowired lateinit var pushLog: PushLogRepository
    @Autowired lateinit var aiUsage: AiUsageRepository
    @Autowired lateinit var newsAssessments: NewsAssessmentRepository
    @Autowired lateinit var newsContext: NewsContextAssembler

    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-04T13:05:00Z"), ZoneOffset.UTC)
    private val month = LocalDate.parse("2026-11-01")
    private val mapper = JsonMapper.builder().build()
    private val notifier = mockk<PushNotifier>(relaxed = true)
    private val contentClient = mockk<ReportContentClient>()
    private val avanza = mockk<AvanzaClient>()
    private val xtb = mockk<XtbClient>()
    private val reasonWriter = mockk<ReasonWriter>()
    private val releases = EqsListingParser.parse(fixture("eqs-listing-en-p1.html")).associateBy { it.newsId.substringBefore('-') }

    private lateinit var server: MockWebServer
    private lateinit var budget: AiBudget
    private lateinit var pipeline: ReportPipeline
    private lateinit var anthropic: AnthropicClient

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/de/$path")) { "missing fixture $path" }.readText()

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company, daily_quote RESTART IDENTITY CASCADE").update()
        jdbc.sql("DELETE FROM ai_usage").update()
        jdbc.sql("DELETE FROM setting WHERE key <> 'PUSH_THRESHOLD'").update()
        settings.put("PUSH_THRESHOLD", "0")
        server = MockWebServer().apply { start() }
        budget = AiBudget(aiUsage, clock)
        anthropic = AnthropicOkHttpClient.builder()
            .baseUrl(server.url("/").toString().trimEnd('/'))
            .apiKey("test-key")
            .maxRetries(0)
            .build()
        every { avanza.findStockByIsin(any(), any()) } answers { throw AvanzaStockNotFoundException(firstArg()) }
        every { avanza.findStockByIsin(MASTERFLEX_ISIN, Market.DE) } returns AvanzaStock("1748784", "Masterflex SE", MASTERFLEX_ISIN, "MZX", "EUR", null)
        every { avanza.findStockByIsin(CONTINENTAL_ISIN, Market.DE) } returns AvanzaStock("732305", "Continental AG", CONTINENTAL_ISIN, "CON", "EUR", null)
        every { avanza.findStockByIsin(BASLER_ISIN, Market.DE) } returns AvanzaStock("5468", "Basler AG", BASLER_ISIN, "BSL", "EUR", null)
        every { avanza.quarters("1748784") } returns AvanzaParser.quarters(fixture("avanza-analysis-masterflex.json"))
        every { avanza.quarters(neq("1748784")) } returns emptyList()
        every { xtb.stocks(XtbCountry.PL) } returns listOf(XtbStock("11B", "11 bit studios SA"))
        every { xtb.stocks(XtbCountry.SE) } returns listOf(XtbStock("VOLVB", "Volvo AB"))
        every { xtb.stocks(XtbCountry.DE) } returns listOf(XtbStock("MZX", "Masterflex SE"), XtbStock("CON", "Continental AG"), XtbStock("V3V", "FamiCord AG"))
        every { xtb.stocks(XtbCountry.NL) } returns listOf(XtbStock("ASML", "ASML Holding NV"))
        every { contentClient.fetch(any(), any()) } answers { content(firstArg()) }
        every { reasonWriter.write(any(), any(), any()) } returns "Przychody +5% r/r. Reszta uzasadnienia."
        every { notifier.send(any()) } answers { pushLog.save(firstArg<PushMessage>(), clock.instant()) }
        pipeline = pipelineWith(avanza)
    }

    private fun pipelineWith(avanzaClient: AvanzaClient): ReportPipeline {
        val avanzaRefresher = AvanzaCompanyRefresher(avanzaClient, mockk<AvanzaQuotes>(relaxed = true), companies, quarterResults, valuations, quotes, settings, clock)
        val biznesRadar = mockk<BiznesRadarClient>()
        val refresher = CompanyDataRefresher(biznesRadar, companies, quarterResults, valuations, settings, clock, avanzaRefresher)
        val xtbFilter = XtbFilter("DE", XtbAvailability(xtb, biznesRadar, companies, clock), refresher, companies)
        return ReportPipeline(
            companies, reports, CompanyResolver(companies, biznesRadar), contentClient, budget,
            ClaudeExtractor(budget, anthropic), QuoteVerifier(), extractions, revenuePeriods, quarterResults,
            refresher, assembler, reasonWriter, scores, settings, PushThreshold(settings), AlertFilters(settings), pushLog, notifier, PushMessageFactory(),
            NewsAssessor(budget, anthropic, QuoteVerifier()), newsAssessments, newsContext, xtbFilter, consensusNoteFinder, clock,
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        settings.put("PUSH_THRESHOLD", "70")
    }

    private fun entry(id: String): ListingEntry = checkNotNull(releases[id]) { "no release $id" }.toListingEntry()

    private fun content(entry: ListingEntry): ReportContent =
        if (entry.sourceRef.startsWith(MASTERFLEX)) {
            EqsReleaseParser().parse(fixture("eqs-masterflex-q3-2026-de.html"), entry.detailUrl)
        } else {
            ReportContent("${entry.title}. Die Gesellschaft erwartet einen Mittelzufluss von rund 12 Mio. EUR.", emptyList(), emptyList())
        }

    private fun statusOf(id: String): ReportStatus = reports.findById(reportId(id)).status

    private fun reportId(id: String): Long =
        jdbc.sql("SELECT id FROM report WHERE source_ref LIKE :ref").param("ref", "$id%").query(Long::class.java).single()

    private fun claudeReply(payload: Map<String, Any?>) = MockResponse().setHeader("content-type", "application/json").setBody(
        mapper.writeValueAsString(
            mapOf(
                "id" to "m", "type" to "message", "role" to "assistant", "model" to "claude-haiku-5-5",
                "content" to listOf(mapOf("type" to "text", "text" to mapper.writeValueAsString(payload))),
                "stop_reason" to "end_turn", "stop_sequence" to null,
                "usage" to mapOf("input_tokens" to 4000, "output_tokens" to 300),
            ),
        ),
    )

    private fun amount(value: Int, text: String) = mapOf("valueInThousands" to value, "sourceText" to text)

    private fun enqueueMasterflexExtraction() = server.enqueue(
        claudeReply(
            mapOf(
                "category" to "RESULTS",
                "periods" to listOf(
                    mapOf(
                        "kind" to "QUARTER", "periodEnd" to "2026-09-30", "currency" to "EUR",
                        "revenue" to amount(27900, "27,9 Mio. Euro (Q3/2025: 26,5 Mio. Euro)"),
                        "revenuePrevYear" to amount(26500, "27,9 Mio. Euro (Q3/2025: 26,5 Mio. Euro)"),
                        "operatingProfit" to null, "operatingProfitPrevYear" to null,
                        "netProfit" to null, "netProfitPrevYear" to null,
                        "orderIntake" to null, "orderIntakePrevYear" to null,
                    ),
                ),
                "outlook" to mapOf("tone" to "NONE", "quote" to null),
                "oneOffQuarter" to false, "oneOffQuote" to null, "cyclicalIndustry" to false,
            ),
        ),
    )

    private fun enqueueResultsWithoutNumbers() = server.enqueue(
        claudeReply(
            mapOf(
                "category" to "RESULTS", "periods" to emptyList<Any>(), "outlook" to mapOf("tone" to "NONE", "quote" to null),
                "oneOffQuarter" to false, "oneOffQuote" to null, "cyclicalIndustry" to false,
            ),
        ),
    )

    private fun enqueueGuidanceNews() = server.enqueue(
        claudeReply(
            mapOf(
                "category" to "SHARE_ISSUE", "direction" to "POSITIVE", "impact" to 80, "contractValue" to null,
                "reason" to "Emisja z prawem poboru po cenie wspiera finansowanie wzrostu. Drugie zdanie.",
            ),
        ),
    )

    @Test
    fun `German Corporate results release of a company on XTB is extracted on the DE pool, scored and pushed`() {
        enqueueMasterflexExtraction()

        pipeline.process(entry(MASTERFLEX))

        assertEquals(ReportStatus.SCORED, statusOf(MASTERFLEX))
        verify(exactly = 1) { notifier.send(match { it.title.startsWith("Masterflex SE (DE) · WYNIKI · ocena") }) }
        val id = reportId(MASTERFLEX)
        val score = checkNotNull(scores.find(id)).score
        assertTrue(Warning.NO_CONSENSUS in score.warnings)
        assertEquals(0, extractions.uncertainFields(id).size)
        assertTrue(aiUsage.costUsd(month, AiPool.DE).signum() > 0)
        assertEquals(0, aiUsage.costUsd(month, AiPool.GPW).signum())
        assertEquals(0, aiUsage.costUsd(month, AiPool.SE).signum())
        val company = companies.findById(reports.findById(id).companyId)
        assertEquals(MASTERFLEX_ISIN, company.isin)
        assertEquals("MZX", company.ticker)
        assertEquals("EUR", company.quoteCurrency)
        assertTrue(company.availableOnXtb)
        val request = server.takeRequest().body.readUtf8()
        assertTrue("Xetra" in request && "27,9 Mio. Euro" in request)
    }

    @Test
    fun `German company not on XTB is stored as skipped without content or AI`() {
        pipeline.process(entry(BASLER))

        assertEquals(ReportStatus.SKIPPED_NOT_ON_XTB, statusOf(BASLER))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
        verify(exactly = 0) { notifier.send(any()) }
    }

    @Test
    fun `Avanza outage without a name match keeps the release NEW for a retry instead of skipping it`() {
        every { avanza.findStockByIsin(BASLER_ISIN, Market.DE) } throws SourceUnavailableException(Source.AVANZA, RuntimeException("down"))

        pipeline.process(entry(BASLER))

        assertEquals(ReportStatus.NEW, statusOf(BASLER))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `Ad-hoc without results keywords becomes news assessed on the DE news pool`() {
        enqueueGuidanceNews()

        pipeline.process(entry(FAMICORD))

        val id = reportId(FAMICORD)
        assertEquals(ReportStatus.SCORED, statusOf(FAMICORD))
        assertEquals("NEWS", jdbc.sql("SELECT event_type FROM report WHERE id = :id").param("id", id).query(String::class.java).single())
        assertEquals(1, server.requestCount)
        assertTrue(aiUsage.costUsd(month, AiPool.DE_NEWS).signum() > 0)
        assertEquals(0, aiUsage.costUsd(month, AiPool.NEWS).signum())
        assertEquals(0, aiUsage.costUsd(month, AiPool.SE_NEWS).signum())
        assertTrue("Rynek: DE" in server.takeRequest().body.readUtf8())
    }

    @Test
    fun `Corporate releases without results and announcements of results are irrelevant without content or AI`() {
        pipeline.process(entry(OHB))
        pipeline.process(entry(AUTODOC))

        assertEquals(ReportStatus.IRRELEVANT, statusOf(OHB))
        assertEquals(ReportStatus.IRRELEVANT, statusOf(AUTODOC))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
    }

    @Test
    fun `Corporate release that AI finds without results figures is irrelevant and never assessed as news`() {
        enqueueResultsWithoutNumbers()

        pipeline.process(entry(CONTINENTAL))

        assertEquals(ReportStatus.IRRELEVANT, statusOf(CONTINENTAL))
        assertEquals(1, server.requestCount)
        assertEquals(0, aiUsage.costUsd(month, AiPool.DE_NEWS).signum())
    }

    @Test
    fun `reprocessing a skipped release checks XTB again`() {
        pipeline.process(entry(BASLER))
        every { xtb.stocks(XtbCountry.DE) } returns listOf(XtbStock("BSL", "Basler AG"))
        enqueueResultsWithoutNumbers()

        pipelineWith(avanza).reprocess(reportId(BASLER), allowWithoutAttachments = false)

        assertEquals(ReportStatus.IRRELEVANT, statusOf(BASLER))
        assertEquals(1, server.requestCount)
    }

    private companion object {
        const val MASTERFLEX = "1869172c"
        const val CONTINENTAL = "5103e393"
        const val BASLER = "e52ef263"
        const val FAMICORD = "c6d7e350"
        const val OHB = "d4bf9e79"
        const val AUTODOC = "c367aae3"
        const val MASTERFLEX_ISIN = "DE0005492938"
        const val CONTINENTAL_ISIN = "DE0005439004"
        const val BASLER_ISIN = "DE0005102008"
    }
}
