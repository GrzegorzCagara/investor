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
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.source.avanza.AvanzaClient
import pl.investor.source.avanza.AvanzaParser
import pl.investor.source.avanza.AvanzaStock
import pl.investor.source.avanza.AvanzaStockNotFoundException
import pl.investor.source.biznesradar.BiznesRadarClient
import pl.investor.source.content.AttachmentTextExtractor
import pl.investor.source.content.ReportContentClient
import pl.investor.source.content.ReportContentParser
import pl.investor.source.eqs.EqsReleaseParser
import pl.investor.source.euronext.EuronextIssuer
import pl.investor.source.euronext.EuronextListingParser
import pl.investor.source.euronext.EuronextNewsItem
import pl.investor.source.euronext.EuronextReleaseParser
import pl.investor.source.gpw.ListingEntry
import pl.investor.source.nasdaq.NasdaqViewParser
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

class DutchPipelineTest : PostgresTestBase() {

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

    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-05T14:00:00Z"), ZoneOffset.UTC)
    private val month = LocalDate.parse("2026-11-01")
    private val mapper = JsonMapper.builder().build()
    private val notifier = mockk<PushNotifier>(relaxed = true)
    private val http = mockk<PoliteHttpClient>()
    private val avanza = mockk<AvanzaClient>()
    private val xtb = mockk<XtbClient>()
    private val reasonWriter = mockk<ReasonWriter>()
    private val parser = EuronextReleaseParser()
    private val rows = (0..3).flatMap { EuronextListingParser.parse(fixture("euronext-listing-p$it.json")) }.associateBy { it.nid }

    private lateinit var server: MockWebServer
    private lateinit var budget: AiBudget
    private lateinit var pipeline: ReportPipeline
    private lateinit var anthropic: AnthropicClient

    private fun resource(path: String) = javaClass.getResource("/fixtures/nl/$path")

    private fun fixture(path: String) = checkNotNull(resource(path)) { "missing fixture $path" }.readText()

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company, daily_quote RESTART IDENTITY CASCADE").update()
        jdbc.sql("DELETE FROM ai_usage").update()
        jdbc.sql("DELETE FROM setting WHERE key <> 'PUSH_THRESHOLD'").update()
        settings.put("PUSH_THRESHOLD", "0")
        server = MockWebServer().apply { start() }
        budget = AiBudget(aiUsage, clock)
        anthropic = AnthropicOkHttpClient.builder().baseUrl(server.url("/").toString().trimEnd('/')).apiKey("test-key").maxRetries(0).build()
        every { avanza.findStockByIsin(any(), any()) } answers { throw AvanzaStockNotFoundException(firstArg()) }
        every { avanza.findStockByIsin(ASML_ISIN, Market.NL) } returns AvanzaStock("732279", "ASML Holding", ASML_ISIN, "ASML", "EUR", null)
        every { avanza.findStockByIsin(BESI_ISIN, Market.NL) } returns AvanzaStock("5226", "BE Semiconductor Industries", BESI_ISIN, "BESI", "EUR", null)
        every { avanza.findStockByIsin(MT_ISIN, Market.NL) } returns AvanzaStock("1011", "ArcelorMittal", MT_ISIN, "MT", "EUR", null)
        every { avanza.findStockByIsin(BAM_ISIN, Market.NL) } returns AvanzaStock("732432", "Koninklijke BAM Groep", BAM_ISIN, "BAMNB", "EUR", null)
        every { avanza.quarters(any()) } returns emptyList()
        every { avanza.quarters("1011") } returns AvanzaParser.quarters(fixture("avanza-analysis-asml.json"))
        every { xtb.stocks(XtbCountry.PL) } returns listOf(XtbStock("11B", "11 bit studios SA"))
        every { xtb.stocks(XtbCountry.SE) } returns listOf(XtbStock("VOLVB", "Volvo AB"))
        every { xtb.stocks(XtbCountry.DE) } returns listOf(XtbStock("MZX", "Masterflex SE"))
        every { xtb.stocks(XtbCountry.NL) } returns listOf(XtbStock("ASML", "ASML Holding NV"), XtbStock("BESI", "BE Semiconductor Industries NV"), XtbStock("MT", "ArcelorMittal"), XtbStock("WKL", "Wolters Kluwer NV"), XtbStock("IMCD", "IMCD NV"))
        every { xtb.stocks(XtbCountry.FI) } returns listOf(XtbStock("NOKIA", "Nokia OYJ", "Nokia"))
        every { http.get(Source.EURONEXT, any()) } answers { fixture("node-${secondArg<String>().substringAfterLast('/')}.html") }
        every { http.getBytes(Source.EURONEXT, any(), any()) } returns checkNotNull(resource("pdf-12917844-besi-q3-2026.pdf")).readBytes()
        every { reasonWriter.write(any(), any(), any()) } returns "Przychody +114% r/r. Reszta uzasadnienia."
        every { notifier.send(any()) } answers { pushLog.save(firstArg<PushMessage>(), clock.instant()) }
        val avanzaRefresher = AvanzaCompanyRefresher(avanza, mockk<AvanzaQuotes>(relaxed = true), companies, quarterResults, valuations, quotes, settings, clock, mockk(relaxed = true))
        val biznesRadar = mockk<BiznesRadarClient>()
        val refresher = CompanyDataRefresher(biznesRadar, companies, quarterResults, valuations, settings, clock, avanzaRefresher)
        val xtbFilter = XtbFilter("DE,GPW,NC,SE,NL", XtbAvailability(xtb, biznesRadar, companies, clock), refresher, companies)
        val contentClient = ReportContentClient(http, ReportContentParser(), mockk(), AttachmentTextExtractor(http), NasdaqViewParser(), EqsReleaseParser(), parser)
        pipeline = ReportPipeline(
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

    private fun entry(nid: String, issuer: EuronextIssuer? = null): ListingEntry {
        val known = issuer ?: parser.issuer(fixture("node-$nid.html"))
        return EuronextNewsItem(checkNotNull(rows[nid]) { "no row $nid" }, checkNotNull(known.isin), checkNotNull(known.ticker), "https://live.euronext.com/en/ajax/node/company-press-release/$nid")
            .toListingEntry()
    }

    private fun statusOf(nid: String): ReportStatus = reports.findById(reportId(nid)).status

    private fun reportId(nid: String): Long =
        jdbc.sql("SELECT id FROM report WHERE source_ref = :ref").param("ref", nid).query(Long::class.java).single()

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

    private fun enqueueResults(currency: String, revenue: Map<String, Any>, net: Map<String, Any>?) = server.enqueue(
        claudeReply(
            mapOf(
                "category" to "RESULTS",
                "periods" to listOf(
                    mapOf(
                        "kind" to "QUARTER", "periodEnd" to "2026-09-30", "currency" to currency,
                        "revenue" to revenue, "revenuePrevYear" to null,
                        "operatingProfit" to null, "operatingProfitPrevYear" to null,
                        "netProfit" to net, "netProfitPrevYear" to null,
                        "orderIntake" to null, "orderIntakePrevYear" to null,
                    ),
                ),
                "outlook" to mapOf("tone" to "NONE", "quote" to null),
                "oneOffQuarter" to false, "oneOffQuote" to null, "cyclicalIndustry" to true,
            ),
        ),
    )

    private fun enqueueNews() = server.enqueue(
        claudeReply(
            mapOf(
                "category" to "ACQUISITION", "direction" to "POSITIVE", "impact" to 60, "contractValue" to null,
                "reason" to "Sprzedaż niekluczowej działalności porządkuje portfel. Drugie zdanie.",
            ),
        ),
    )

    @Test
    fun `results release of a Dutch company on XTB is extracted on the NL pool, scored and pushed`() {
        enqueueResults("EUR", amount(11_700_000, "€11.7 billion"), amount(5_000_000, "€5.0 billion"))

        pipeline.process(entry(ASML))

        assertEquals(ReportStatus.SCORED, statusOf(ASML))
        verify(exactly = 1) { notifier.send(match { it.title.startsWith("ASML Holding (NL) · WYNIKI · ocena") }) }
        val id = reportId(ASML)
        assertEquals(0, extractions.uncertainFields(id).size)
        assertTrue(Warning.NO_CONSENSUS in checkNotNull(scores.find(id)).score.warnings)
        assertTrue(aiUsage.costUsd(month, AiPool.NL).signum() > 0)
        listOf(AiPool.GPW, AiPool.SE, AiPool.DE, AiPool.NL_NEWS).forEach { assertEquals(0, aiUsage.costUsd(month, it).signum(), it.name) }
        val company = companies.findById(reports.findById(id).companyId)
        assertEquals("ASML", company.ticker)
        assertEquals("ASML", company.exchangeTicker)
        assertEquals("732279", company.avanzaId)
        assertTrue(company.availableOnXtb)
        assertTrue("Euronext Amsterdam" in server.takeRequest().body.readUtf8())
    }

    @Test
    fun `PDF-only release is extracted from its Euronext PDF`() {
        enqueueResults("EUR", amount(283_400, "€ 283.4 million"), amount(107_200, "€ 107.2 Million"))

        pipeline.process(entry(BESI))

        assertEquals(ReportStatus.SCORED, statusOf(BESI))
        assertEquals(0, extractions.uncertainFields(reportId(BESI)).size)
        val request = server.takeRequest().body.readUtf8()
        assertTrue("283.4" in request && "Załączniki PDF" in request)
        verify(exactly = 1) { http.getBytes(Source.EURONEXT, match { it.startsWith("https://live.euronext.com/sites/default/files/company_press_releases/attachments/") }, any()) }
    }

    @Test
    fun `PDF-only release whose PDF is not available yet waits instead of going to AI with an empty body`() {
        val pdf = checkNotNull(resource("pdf-12917844-besi-q3-2026.pdf")).readBytes()
        every { http.getBytes(Source.EURONEXT, any(), any()) } throws SourceUnavailableException(Source.EURONEXT, RuntimeException("503"))

        pipeline.process(entry(BESI))

        assertEquals(ReportStatus.PENDING_ATTACHMENTS, statusOf(BESI))
        assertEquals(0, server.requestCount)

        every { http.getBytes(Source.EURONEXT, any(), any()) } returns pdf
        enqueueResults("EUR", amount(283_400, "€ 283.4 million"), amount(107_200, "€ 107.2 Million"))
        pipeline.reprocess(reportId(BESI), allowWithoutAttachments = false)

        assertEquals(ReportStatus.SCORED, statusOf(BESI))
        assertTrue("283.4" in server.takeRequest().body.readUtf8())
    }

    @Test
    fun `Dutch company not on XTB is skipped without content or AI`() {
        pipeline.process(entry(BAM))

        assertEquals(ReportStatus.SKIPPED_NOT_ON_XTB, statusOf(BAM))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { http.get(Source.EURONEXT, any()) }
        verify(exactly = 0) { notifier.send(any()) }
    }

    @Test
    fun `other news of a Dutch company is assessed on the NL news pool`() {
        enqueueNews()

        pipeline.process(entry(WOLTERS_KLUWER_DIVESTMENT, EuronextIssuer("NL0000395903", "WKL")))

        assertEquals(ReportStatus.SCORED, statusOf(WOLTERS_KLUWER_DIVESTMENT))
        assertEquals(1, server.requestCount)
        assertTrue(aiUsage.costUsd(month, AiPool.NL_NEWS).signum() > 0)
        assertEquals(0, aiUsage.costUsd(month, AiPool.NEWS).signum())
        assertEquals(0, aiUsage.costUsd(month, AiPool.DE_NEWS).signum())
        assertTrue("Rynek: NL" in server.takeRequest().body.readUtf8())
    }

    @Test
    fun `announcements of results and routine notices are irrelevant without content or AI`() {
        pipeline.process(entry(IMCD_WEBCAST, EuronextIssuer("NL0010801007", "IMCD")))
        pipeline.process(entry(BESI_BUYBACK_TRANSACTIONS, EuronextIssuer(BESI_ISIN, "BESI")))

        assertEquals(ReportStatus.IRRELEVANT, statusOf(IMCD_WEBCAST))
        assertEquals(ReportStatus.IRRELEVANT, statusOf(BESI_BUYBACK_TRANSACTIONS))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { http.get(Source.EURONEXT, any()) }
    }

    @Test
    fun `USD results of a company quoted in EUR are scored without the EUR history`() {
        enqueueResults("USD", amount(16_500_000, "\$16.5 billion"), null)

        pipeline.process(entry(ARCELORMITTAL))

        assertEquals(ReportStatus.SCORED, statusOf(ARCELORMITTAL))
        assertEquals(0, extractions.uncertainFields(reportId(ARCELORMITTAL)).size)
        assertTrue(aiUsage.costUsd(month, AiPool.NL).signum() > 0)
    }

    private companion object {
        const val ASML = "12916206"
        const val BESI = "12917844"
        const val BAM = "12921499"
        const val ARCELORMITTAL = "12921550"
        const val WOLTERS_KLUWER_DIVESTMENT = "12919830"
        const val IMCD_WEBCAST = "12916502"
        const val BESI_BUYBACK_TRANSACTIONS = "12917193"
        const val ASML_ISIN = "NL0010273215"
        const val BESI_ISIN = "NL0012866412"
        const val MT_ISIN = "LU1598757687"
        const val BAM_ISIN = "NL0000337319"
    }
}
