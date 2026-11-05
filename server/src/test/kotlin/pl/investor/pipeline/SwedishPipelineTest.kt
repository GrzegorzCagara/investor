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
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.scoring.ConsensusMetric
import pl.investor.scoring.ScoreRepository
import pl.investor.scoring.ScoringInputAssembler
import pl.investor.scoring.Warning
import pl.investor.settings.AlertFilters
import pl.investor.settings.PushThreshold
import pl.investor.monitoring.SourceHealthTracker
import pl.investor.settings.SettingRepository
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.source.avanza.AvanzaClient
import pl.investor.source.avanza.AvanzaParser
import pl.investor.source.avanza.AvanzaSearchHit
import pl.investor.source.avanza.AvanzaStock
import pl.investor.source.avanza.AvanzaStockNotFoundException
import pl.investor.source.biznesradar.BiznesRadarClient
import pl.investor.source.content.ReportContent
import pl.investor.source.content.ReportContentClient
import pl.investor.source.gpw.ListingEntry
import pl.investor.source.xtb.XtbAvailability
import pl.investor.source.xtb.XtbClient
import pl.investor.source.xtb.XtbCountry
import pl.investor.source.xtb.XtbStock
import pl.investor.source.nasdaq.NasdaqNewsParser
import pl.investor.source.nasdaq.NasdaqViewParser
import pl.investor.source.placera.ConsensusEstimate
import pl.investor.source.placera.ConsensusPreview
import pl.investor.source.placera.ConsensusPreviewRepository
import pl.investor.source.quotes.AvanzaQuotes
import pl.investor.support.PostgresTestBase
import pl.investor.support.STOCKHOLM
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwedishPipelineTest : PostgresTestBase() {

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
    @Autowired lateinit var previews: ConsensusPreviewRepository

    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-30T07:05:00Z"), ZoneOffset.UTC)
    private val month = LocalDate.parse("2026-10-01")
    private val mapper = JsonMapper.builder().build()
    private val notifier = mockk<PushNotifier>(relaxed = true)
    private val contentClient = mockk<ReportContentClient>()
    private val avanza = mockk<AvanzaClient>()
    private val xtb = mockk<XtbClient>()
    private val avanzaQuotes = mockk<AvanzaQuotes>(relaxed = true)
    private val reasonWriter = mockk<ReasonWriter>()
    private val news = NasdaqNewsParser.parse(fixture("nasdaq-first-north.json"))

    private lateinit var server: MockWebServer
    private lateinit var budget: AiBudget
    private lateinit var pipeline: ReportPipeline
    private lateinit var anthropic: AnthropicClient

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/se/$path")) { "missing fixture $path" }.readText()

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
        every { avanza.findStock(any()) } throws SourceUnavailableException(Source.AVANZA, RuntimeException("Avanza down"))
        every { avanza.findStock("Opter AB") } returns AvanzaSearchHit("1307030", "Opter", "SE", "First North Stockholm")
        every { avanza.stock("1307030") } returns AvanzaStock("1307030", "Opter", "SE0016075063", "OPTER", "SEK", "First North Stockholm")
        every { avanza.quarters("1307030") } returns AvanzaParser.quarters(fixture("avanza-analysis-opter.json"))
        every { contentClient.fetch(any(), any()) } answers { content(firstArg()) }
        every { xtb.stocks(XtbCountry.PL) } returns listOf(XtbStock("KRU", "KRUK SA"))
        every { xtb.stocks(XtbCountry.SE) } returns listOf(
            XtbStock("OPTER", "Opter AB"), XtbStock("MIPS", "Mips AB"), XtbStock("MINEST", "Minesto AB"),
            XtbStock("ORT", "Ortoma AB"), XtbStock("MCG", "MacGregor Group AB"),
        )
        every { xtb.stocks(XtbCountry.DE) } returns listOf(XtbStock("MZX", "Masterflex SE"))
        every { xtb.stocks(XtbCountry.NL) } returns listOf(XtbStock("ASML", "ASML Holding NV"))
        every { reasonWriter.write(any(), any(), any()) } returns "Przychody +14% r/r. Reszta uzasadnienia."
        every { notifier.send(any()) } answers { pushLog.save(firstArg<PushMessage>(), clock.instant()) }
        pipeline = pipelineWith(avanza)
    }

    private fun pipelineWith(avanzaClient: AvanzaClient): ReportPipeline {
        val avanzaRefresher = AvanzaCompanyRefresher(avanzaClient, avanzaQuotes, companies, quarterResults, valuations, quotes, settings, clock)
        val biznesRadar = mockk<BiznesRadarClient>()
        val refresher = CompanyDataRefresher(biznesRadar, companies, quarterResults, valuations, settings, clock, avanzaRefresher)
        return ReportPipeline(
            companies, reports, CompanyResolver(companies, biznesRadar), contentClient, budget,
            ClaudeExtractor(budget, anthropic), QuoteVerifier(), extractions, revenuePeriods, quarterResults,
            refresher, assembler, reasonWriter, scores, settings, PushThreshold(settings), AlertFilters(settings), pushLog, notifier, PushMessageFactory(),
            NewsAssessor(budget, anthropic, QuoteVerifier()), newsAssessments, newsContext, XtbFilter("DE,GPW,NC,SE", XtbAvailability(xtb, biznesRadar, companies, clock), refresher, companies), clock,
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        settings.put("PUSH_THRESHOLD", "70")
    }

    private fun entry(disclosureId: String): ListingEntry = news.single { it.disclosureId == disclosureId }.toListingEntry()

    private fun content(entry: ListingEntry): ReportContent =
        if (entry.sourceRef == OPTER) {
            NasdaqViewParser().parse(fixture("view-opter-q3-2026.html"), entry.detailUrl)
        } else {
            ReportContent("${entry.title}. Ordervärdet uppgår till cirka 173 MSEK.", emptyList(), emptyList())
        }

    private fun statusOf(sourceRef: String): ReportStatus =
        reports.findById(reportId(sourceRef)).status

    private fun reportId(sourceRef: String): Long =
        jdbc.sql("SELECT id FROM report WHERE source_ref = :ref").param("ref", sourceRef).query(Long::class.java).single()

    private fun claudeReply(payload: Map<String, Any?>) = MockResponse().setHeader("content-type", "application/json").setBody(
        mapper.writeValueAsString(
            mapOf(
                "id" to "m", "type" to "message", "role" to "assistant", "model" to "claude-haiku-5-5",
                "content" to listOf(mapOf("type" to "text", "text" to mapper.writeValueAsString(payload))),
                "stop_reason" to "end_turn", "stop_sequence" to null,
                "usage" to mapOf("input_tokens" to 1000, "output_tokens" to 100),
            ),
        ),
    )

    private fun amount(value: Int, text: String) = mapOf("valueInThousands" to value, "sourceText" to text)

    private fun enqueueOpterExtraction() = server.enqueue(
        claudeReply(
            mapOf(
                "category" to "RESULTS",
                "periods" to listOf(
                    mapOf(
                        "kind" to "QUARTER", "periodEnd" to "2026-09-30", "currency" to "SEK",
                        "revenue" to amount(27700, "27,7 MSEK"), "revenuePrevYear" to amount(24200, "24,2"),
                        "operatingProfit" to amount(10600, "10,6 MSEK"), "operatingProfitPrevYear" to amount(8200, "10,6 MSEK (8,2)"),
                        "netProfit" to amount(8500, "8,5 MSEK"), "netProfitPrevYear" to amount(6600, "8,5 MSEK (6,6)"),
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

    private fun enqueueContractNews() = server.enqueue(
        claudeReply(
            mapOf(
                "category" to "CONTRACT", "direction" to "POSITIVE", "impact" to 80, "contractValue" to null,
                "reason" to "Duże zamówienie wyraźnie zwiększa przychody spółki. Drugie zdanie.",
            ),
        ),
    )

    @Test
    fun `Swedish interim report is extracted on the SE pool, scored and pushed`() {
        enqueueOpterExtraction()

        pipeline.process(entry(OPTER))

        assertEquals(ReportStatus.SCORED, statusOf(OPTER))
        verify(exactly = 1) { notifier.send(match { it.title.startsWith("Opter (SE) · WYNIKI · ocena") }) }
        verify(exactly = 1) { contentClient.fetch(any(), true) }
        val id = reportId(OPTER)
        val warnings = checkNotNull(scores.find(id)).score.warnings
        assertTrue(Warning.MISSING_DATA in warnings)
        assertFalse(Warning.NO_AI in warnings)
        assertEquals(0, extractions.uncertainFields(id).size)
        assertTrue(aiUsage.costUsd(month, AiPool.SE).signum() > 0)
        assertEquals(0, aiUsage.costUsd(month, AiPool.GPW).signum())
        val company = companies.findById(reports.findById(id).companyId)
        assertEquals("SE0016075063", company.isin)
        assertEquals("First North Stockholm", company.listing)
        val request = server.takeRequest().body.readUtf8()
        assertTrue("Nasdaq Stockholm" in request && "27,7 MSEK" in request)
    }

    @Test
    fun `Placera consensus published before the report replaces SUR and drops the no-consensus warning`() {
        jdbc.sql("TRUNCATE consensus_preview").update()
        val opter = entry(OPTER)
        previews.save(
            ConsensusPreview(
                url = "https://www.placera.se/telegram/opter-vantas-redovisa", isin = "SE0016075063", companyName = "Opter",
                reportDate = opter.publishedAt.atZone(STOCKHOLM).toLocalDate(), currency = "SEK",
                revenue = ConsensusEstimate(BigDecimal("25000"), BigDecimal("24200"), 2), ebit = ConsensusEstimate(BigDecimal("9000"), null, 2),
                source = "Modular Finance", pastReactions = emptyList(),
                publishedAt = opter.publishedAt.minus(Duration.ofDays(2)), fetchedAt = opter.publishedAt.minus(Duration.ofDays(2)),
            ),
        )
        enqueueOpterExtraction()

        pipeline.process(opter)

        val score = checkNotNull(scores.find(reportId(OPTER))).score
        assertEquals(30.0, score.components.surprise, 1e-9)
        assertFalse(Warning.NO_CONSENSUS in score.warnings)
        assertEquals(listOf(ConsensusMetric.REVENUE, ConsensusMetric.EBIT), score.context.consensus.map { it.metric })
    }

    @Test
    fun `sv copy of an already processed en release is stored as irrelevant without AI`() {
        enqueueResultsWithoutNumbers()

        pipeline.process(entry(MINESTO_EN))
        pipeline.process(entry(MINESTO_SV))

        assertTrue(statusOf(MINESTO_EN) != ReportStatus.IRRELEVANT)
        assertEquals(ReportStatus.IRRELEVANT, statusOf(MINESTO_SV))
        assertEquals(1, server.requestCount)
        assertEquals(1L, jdbc.sql("SELECT count(DISTINCT company_id) FROM report").query(Long::class.java).single())
    }

    @Test
    fun `Swedish company that XTB does not offer is skipped by default before any AI call`() {
        every { xtb.stocks(XtbCountry.SE) } returns listOf(XtbStock("VOLVB", "Volvo AB"))

        pipeline.process(entry(OPTER))

        assertEquals(ReportStatus.SKIPPED_NOT_ON_XTB, statusOf(OPTER))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
    }

    @Test
    fun `categories outside the routing table are stored as irrelevant without content or AI`() {
        val investorNews = news.first { it.category == "Investor News" }.disclosureId

        pipeline.process(entry(investorNews))

        assertEquals(ReportStatus.IRRELEVANT, statusOf(investorNews))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
    }

    @Test
    fun `inside information with preliminary results only in the PDF gets the attachment pass`() {
        val preliminary = entry(ORTOMA).copy(title = "Ortoma estimates that sales and earnings for the third quarter will significantly exceed")
        val body = "Ortoma estimates that sales and earnings for the third quarter will significantly exceed expectations. See the attached PDF."
        val pdfUrl = "https://attachment.news.eu.nasdaq.com/a1b2c3"
        every { contentClient.fetch(preliminary, false) } returns ReportContent(body, emptyList(), listOf(pdfUrl), attachmentsArePdf = true)
        every { contentClient.fetch(preliminary, true) } returns ReportContent(
            body, emptyList(), listOf(pdfUrl), attachmentsArePdf = true,
            attachmentText = "Nettoomsättning 27,7 MSEK (24,2). Rörelseresultat 10,6 MSEK (8,2). Periodens resultat 8,5 MSEK (6,6).",
        )
        enqueueResultsWithoutNumbers()
        enqueueOpterExtraction()

        pipeline.process(preliminary)

        assertEquals(2, server.requestCount)
        verify(exactly = 1) { contentClient.fetch(preliminary, true) }
        val revenue = extractions.find(reportId(ORTOMA))?.extraction?.periods?.single()?.revenue?.valueInThousands
        assertEquals(0, BigDecimal("27700").compareTo(revenue))
        assertEquals(ReportStatus.SCORED, statusOf(ORTOMA))
    }

    @Test
    fun `inside information becomes news assessed on the SE news pool`() {
        enqueueContractNews()

        pipeline.process(entry(ORTOMA))

        val id = reportId(ORTOMA)
        assertEquals(ReportStatus.SCORED, statusOf(ORTOMA))
        assertEquals("NEWS", jdbc.sql("SELECT event_type FROM report WHERE id = :id").param("id", id).query(String::class.java).single())
        verify(exactly = 1) { notifier.send(match { it.title == "Ortoma AB (SE) · WYDARZENIE · ocena 60" }) }
        assertTrue(aiUsage.costUsd(month, AiPool.SE_NEWS).signum() > 0)
        assertEquals(0, aiUsage.costUsd(month, AiPool.NEWS).signum())
        assertTrue("Rynek: SE" in server.takeRequest().body.readUtf8())
    }

    @Test
    fun `exhausted SE pool scores the report from Avanza without AI and sends the Swedish budget push once`() {
        budget.record(AiPool.SE, 20_000_000, 0)

        pipeline.process(entry(OPTER))

        assertEquals(0, server.requestCount)
        assertEquals(ReportStatus.SCORED, statusOf(OPTER))
        assertTrue(Warning.NO_AI in checkNotNull(scores.find(reportId(OPTER))).score.warnings)
        verify(exactly = 1) {
            notifier.send(match { it.title == "Limit AI wyczerpany" && it.body == "Do końca miesiąca raporty ze Szwecji są oceniane bez AI" })
        }
        assertTrue(settings.find("AI_BUDGET_NOTIFIED_SE_2026-10") != null)
    }

    @Test
    fun `exhausted SE news pool leaves Swedish news unassessed while GPW news pool is untouched`() {
        budget.record(AiPool.SE_NEWS, 20_000_000, 0)

        pipeline.process(entry(ORTOMA))

        assertEquals(0, server.requestCount)
        assertEquals(ReportStatus.UNSCORED_NO_AI, statusOf(ORTOMA))
        verify(exactly = 1) {
            notifier.send(match { it.title == "Limit AI wyczerpany" && it.body == "Do końca miesiąca wydarzenia ze Szwecji nie są oceniane" })
        }
        assertTrue(budget.canSpend(AiPool.NEWS))
    }

    @Test
    fun `Stockholm release of a company also listed on GPW is stored as irrelevant without AI or push`() {
        val gpw = companies.save(Company(null, "ARCTIC PAPER", "PLARTPR00012", Market.GPW, "ATC", "ARCTIC", false, "ARCTIC"))
        every { avanza.findStock("Arctic Paper S.A.") } returns AvanzaSearchHit("5241", "Arctic Paper", "SE", "Stockholmsbörsen")
        every { avanza.stock("5241") } returns AvanzaStock("5241", "Arctic Paper", "PLARTPR00012", "ARP", "SEK", "Small Cap Stockholm")
        val release = ListingEntry(
            Market.SE, "Arctic Paper S.A.", null, ReportKind.QUARTERLY, "Interim report (Q1 and Q3)", "900001",
            "Arctic Paper Interim report Q3 2026", Instant.parse("2026-10-30T06:00:00Z"), "https://view.news.eu.nasdaq.com/view?id=arctic",
        )

        pipeline.process(release)

        assertEquals(ReportStatus.IRRELEVANT, statusOf("900001"))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
        verify(exactly = 0) { notifier.send(any()) }
        val swedish = companies.findById(reports.findById(reportId("900001")).companyId)
        assertEquals("PLARTPR00012", swedish.isin)
        assertTrue(swedish.id != gpw.id)
    }

    private fun interimReport(companyName: String, sourceRef: String) = ListingEntry(
        Market.SE, companyName, null, ReportKind.QUARTERLY, "Interim report (Q1 and Q3)", sourceRef,
        "$companyName Interim report Q3 2026", Instant.parse("2026-10-30T06:00:00Z"), "https://view.news.eu.nasdaq.com/view?id=$sourceRef",
    )

    private fun assertSkippedWithoutWork(sourceRef: String) {
        assertEquals(ReportStatus.IRRELEVANT, statusOf(sourceRef))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
        verify(exactly = 0) { notifier.send(any()) }
    }

    private fun withAvanzaServer(vararg responses: String, block: (AvanzaClient, MockWebServer) -> Unit) {
        val avanzaServer = MockWebServer().apply { start() }
        responses.forEach { avanzaServer.enqueue(MockResponse().setBody(if (it.endsWith(".json")) fixture(it) else it)) }
        val http = PoliteHttpClient(Clock.systemUTC(), "me@example.com", mockk<SourceHealthTracker>(relaxed = true))
        try {
            block(AvanzaClient(http, avanzaServer.url("/").toString().trimEnd('/')), avanzaServer)
        } finally {
            avanzaServer.shutdown()
        }
    }

    @Test
    fun `periodic report of a bond-only issuer without an Avanza share is irrelevant without content, AI or push`() {
        every { avanza.findStock("Swedavia AB") } throws AvanzaStockNotFoundException("Swedavia AB")

        pipeline.process(interimReport("Swedavia AB", "900101"))

        assertSkippedWithoutWork("900101")
        val companyId = reports.findById(reportId("900101")).companyId
        assertEquals("NOT_LISTED", settings.find("AVANZA_IDENTITY_TRIED_${companyId}_2026-10-30"))
    }

    @Test
    fun `report is processed as before when Avanza is unavailable`() {
        enqueueResultsWithoutNumbers()

        pipeline.process(interimReport("MacGregor Group AB", "900102"))

        assertTrue(statusOf("900102") != ReportStatus.IRRELEVANT)
        assertEquals(1, server.requestCount)
        verify(exactly = 1) { contentClient.fetch(any(), true) }
    }

    @Test
    fun `not listed is remembered for the day so later releases skip Avanza`() {
        every { avanza.findStock("Ziklo Bank AB") } throws AvanzaStockNotFoundException("Ziklo Bank AB")

        pipeline.process(interimReport("Ziklo Bank AB", "900103"))
        pipeline.process(interimReport("Ziklo Bank AB", "900104").copy(publishedAt = Instant.parse("2026-10-30T09:00:00Z")))

        assertEquals(ReportStatus.IRRELEVANT, statusOf("900103"))
        assertEquals(ReportStatus.IRRELEVANT, statusOf("900104"))
        verify(exactly = 1) { avanza.findStock("Ziklo Bank AB") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `Stockholm release of a GPW company is recognised by name without an ISIN`() {
        companies.save(Company(null, "KRUK SPÓŁKA AKCYJNA", "PLKRK0000010", Market.GPW, "KRU", "KRUK", false, "KRUK"))

        pipeline.process(interimReport("KRUK S.A.", "900105"))

        assertSkippedWithoutWork("900105")
    }

    @Test
    fun `Neem's Hill is identified through the first-word fallback and processed`() {
        val stock = fixture("avanza-stock-mips.json")
            .replace("\"orderbookId\":\"740416\"", "\"orderbookId\":\"710553\"")
            .replace("\"name\":\"Mips\"", "\"name\":\"Neem's Hill B\"")
            .replace("SE0009216278", "SE0012729366")
        withAvanzaServer(NO_HITS, "avanza-search-neem.json", stock) { client, avanzaServer ->
            enqueueResultsWithoutNumbers()

            pipelineWith(client).process(interimReport("Neem's Hill AB", "900106"))

            assertEquals(3, avanzaServer.requestCount)
            val company = companies.findById(reports.findById(reportId("900106")).companyId)
            assertEquals("710553", company.avanzaId)
            assertEquals("SE0012729366", company.isin)
            assertTrue(statusOf("900106") != ReportStatus.IRRELEVANT)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `fallback search never accepts an unrelated company, so the report is irrelevant`() {
        val neemoTech = fixture("avanza-search-neem.json").replace("Neem's Hill B (NEEMS B)", "Neemo Tech (NEEMO)")
        withAvanzaServer(NO_HITS, neemoTech) { client, avanzaServer ->
            pipelineWith(client).process(interimReport("Neem's Hill AB", "900107"))

            assertEquals(2, avanzaServer.requestCount)
            assertSkippedWithoutWork("900107")
            assertNull(companies.findById(reports.findById(reportId("900107")).companyId).avanzaId)
        }
    }

    private companion object {
        const val OPTER = "1473716"
        const val MINESTO_EN = "1473457"
        const val MINESTO_SV = "1473458"
        const val ORTOMA = "1472686"
        const val NO_HITS = """{"totalNumberOfHits":0,"hits":[],"searchQuery":"","searchFilter":{"types":["STOCK"]},"facets":{"types":[]},"pagination":{"size":5,"from":0}}"""
    }
}
