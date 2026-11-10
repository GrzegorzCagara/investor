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
import pl.investor.source.avanza.AvanzaSearchHit
import pl.investor.source.avanza.AvanzaStock
import pl.investor.source.avanza.AvanzaStockNotFoundException
import pl.investor.source.biznesradar.BiznesRadarClient
import pl.investor.source.consensus.ConsensusNoteFinder
import pl.investor.source.content.ReportContent
import pl.investor.source.content.ReportContentClient
import pl.investor.source.gpw.ListingEntry
import pl.investor.source.nasdaq.NasdaqNewsParser
import pl.investor.source.nasdaq.NasdaqViewParser
import pl.investor.source.quotes.AvanzaQuotes
import pl.investor.source.xtb.XtbAvailability
import pl.investor.source.xtb.XtbClient
import pl.investor.source.xtb.XtbCountry
import pl.investor.source.xtb.XtbStock
import pl.investor.support.PostgresTestBase
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FinnishPipelineTest : PostgresTestBase() {

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

    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-10T09:00:00Z"), ZoneOffset.UTC)
    private val month = LocalDate.parse("2026-11-01")
    private val mapper = JsonMapper.builder().build()
    private val notifier = mockk<PushNotifier>(relaxed = true)
    private val contentClient = mockk<ReportContentClient>()
    private val avanza = mockk<AvanzaClient>()
    private val xtb = mockk<XtbClient>()
    private val reasonWriter = mockk<ReasonWriter>()
    private val news = NasdaqNewsParser.parse(fixture("fi/nasdaq-helsinki.json"))
    private val xtbFinland = XtbClient.parse(fixture("xtb/instruments-fi.json"), XtbCountry.FI)
    private val xtbSweden = XtbClient.parse(fixture("xtb/instruments-se-2026-11-10.json"), XtbCountry.SE)

    private lateinit var server: MockWebServer
    private lateinit var budget: AiBudget
    private lateinit var pipeline: ReportPipeline
    private lateinit var anthropic: AnthropicClient

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readText()

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
        every { avanza.findShareClasses(any(), any()) } throws SourceUnavailableException(Source.AVANZA, RuntimeException("Avanza down"))
        every { avanza.findShareClasses("Kempower Oyj", Market.FI) } returns listOf(AvanzaSearchHit("1319400", "Kempower", "FI", "Helsingforsbörsen"))
        every { avanza.stock("1319400") } returns AvanzaParser.stock(fixture("fi/avanza-stock-kempower.json"))
        every { avanza.quarters("1319400") } returns AvanzaParser.quarters(fixture("fi/avanza-analysis-kempower.json"))
        every { avanza.findShareClasses("Raisio", Market.FI) } returns
            AvanzaClient.matches("Raisio", AvanzaParser.searchHits(fixture("fi/avanza-search-raisio.json")), Market.FI)
        every { avanza.stock("52801") } returns AvanzaParser.stock(fixture("fi/avanza-stock-raisio-k.json"))
        every { avanza.stock("52802") } returns AvanzaParser.stock(fixture("fi/avanza-stock-raisio-vaihto.json"))
        every { avanza.quarters(match { it in setOf("52801", "52802") }) } returns emptyList()
        every { avanza.findShareClasses("Valmet Corporation", Market.FI) } returns listOf(AvanzaSearchHit("700", "Valmet", "FI", "Helsingforsbörsen"))
        every { avanza.stock("700") } returns AvanzaStock("700", "Valmet", "FI4000074984", "VALMT", "EUR", "Large Cap Helsinki")
        every { avanza.quarters("700") } returns emptyList()
        every { contentClient.fetch(any(), any()) } answers { content(firstArg()) }
        every { xtb.stocks(XtbCountry.PL) } returns listOf(XtbStock("KRU", "KRUK SA"))
        every { xtb.stocks(XtbCountry.SE) } returns xtbSweden
        every { xtb.stocks(XtbCountry.DE) } returns listOf(XtbStock("MZX", "Masterflex SE"))
        every { xtb.stocks(XtbCountry.NL) } returns listOf(XtbStock("ASML", "ASML Holding NV"))
        every { xtb.stocks(XtbCountry.FI) } returns xtbFinland
        every { reasonWriter.write(any(), any(), any()) } returns "Przychody -10% r/r. Reszta uzasadnienia."
        every { notifier.send(any()) } answers { pushLog.save(firstArg<PushMessage>(), clock.instant()) }
        pipeline = pipelineWith()
    }

    private fun pipelineWith(): ReportPipeline {
        val biznesRadar = mockk<BiznesRadarClient>()
        val availability = XtbAvailability(xtb, biznesRadar, companies, clock)
        val avanzaRefresher = AvanzaCompanyRefresher(avanza, mockk<AvanzaQuotes>(relaxed = true), companies, quarterResults, valuations, quotes, settings, clock, availability)
        val refresher = CompanyDataRefresher(biznesRadar, companies, quarterResults, valuations, settings, clock, avanzaRefresher)
        return ReportPipeline(
            companies, reports, CompanyResolver(companies, biznesRadar), contentClient, budget,
            ClaudeExtractor(budget, anthropic), QuoteVerifier(), extractions, revenuePeriods, quarterResults,
            refresher, assembler, reasonWriter, scores, settings, PushThreshold(settings), AlertFilters(settings), pushLog, notifier, PushMessageFactory(),
            NewsAssessor(budget, anthropic, QuoteVerifier()), newsAssessments, newsContext,
            XtbFilter("DE,GPW,NC,SE,NL,FI", availability, refresher, companies), consensusNoteFinder, clock,
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        settings.put("PUSH_THRESHOLD", "70")
    }

    private fun entry(disclosureId: String): ListingEntry = news.single { it.disclosureId == disclosureId }.toListingEntry(Market.FI)

    private fun content(entry: ListingEntry): ReportContent =
        javaClass.getResource("/fixtures/fi/view-${entry.sourceRef}.html")
            ?.let { NasdaqViewParser().parse(it.readText(), entry.detailUrl).copy(attachmentUrls = emptyList()) }
            ?: ReportContent("${entry.title}.", emptyList(), emptyList())

    private fun statusOf(sourceRef: String): ReportStatus = reports.findById(reportId(sourceRef)).status

    private fun reportId(sourceRef: String): Long =
        jdbc.sql("SELECT id FROM report WHERE source_ref = :ref").param("ref", sourceRef).query(Long::class.java).single()

    private fun companyOf(sourceRef: String): Company = companies.findById(reports.findById(reportId(sourceRef)).companyId)

    private fun eventTypeOf(sourceRef: String): String =
        jdbc.sql("SELECT event_type FROM report WHERE id = :id").param("id", reportId(sourceRef)).query(String::class.java).single()

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

    private fun enqueueResults(vararg fields: Pair<String, Any?>) = server.enqueue(
        claudeReply(
            mapOf(
                "category" to "RESULTS",
                "periods" to listOf(
                    mapOf(
                        "kind" to "QUARTER", "periodEnd" to "2026-09-30", "currency" to "EUR",
                        "revenue" to null, "revenuePrevYear" to null, "operatingProfit" to null, "operatingProfitPrevYear" to null,
                        "netProfit" to null, "netProfitPrevYear" to null, "orderIntake" to null, "orderIntakePrevYear" to null,
                    ) + fields,
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

    private fun enqueueNews() = server.enqueue(
        claudeReply(
            mapOf(
                "category" to "CONTRACT", "direction" to "POSITIVE", "impact" to 80, "contractValue" to null,
                "reason" to "Komunikat wyraźnie zmienia perspektywy spółki. Drugie zdanie.",
            ),
        ),
    )

    private fun interimReport(market: Market, companyName: String, sourceRef: String) = ListingEntry(
        market, companyName, null, ReportKind.QUARTERLY, "Interim report (Q1 and Q3)", sourceRef,
        "$companyName Interim report Q3 2026", Instant.parse("2026-11-04T06:00:00Z"), "https://view.news.eu.nasdaq.com/view?id=$sourceRef",
    )

    @Test
    fun `Finnish interim report of a company on XTB is extracted on the FI pool, verified, scored and pushed`() {
        enqueueResults(
            "revenue" to amount(66200, "EUR 66.2 million (EUR 73.7 million)"),
            "revenuePrevYear" to amount(73700, "EUR 66.2 million (EUR 73.7 million)"),
            "operatingProfit" to amount(-1700, "EUR -1.7 million (EUR 0.2 million)"),
            "operatingProfitPrevYear" to amount(200, "EUR -1.7 million (EUR 0.2 million)"),
            "orderIntake" to amount(60800, "EUR 60.8 million"),
        )

        pipeline.process(entry(KEMPOWER_INTERIM))

        assertEquals(ReportStatus.SCORED, statusOf(KEMPOWER_INTERIM))
        verify(exactly = 1) { notifier.send(match { it.title.startsWith("Kempower (FI) · WYNIKI · ocena") }) }
        val id = reportId(KEMPOWER_INTERIM)
        assertEquals(0, extractions.uncertainFields(id).size)
        val warnings = checkNotNull(scores.find(id)).score.warnings
        assertTrue(Warning.NO_CONSENSUS in warnings)
        assertFalse(Warning.NO_AI in warnings)
        assertTrue(aiUsage.costUsd(month, AiPool.FI).signum() > 0)
        assertEquals(0, aiUsage.costUsd(month, AiPool.SE).signum())
        val company = companyOf(KEMPOWER_INTERIM)
        assertEquals("FI4000513593", company.isin)
        assertEquals("KEMPOWR", company.ticker)
        assertEquals("EUR", company.quoteCurrency)
        assertEquals("Mid Cap Helsinki", company.listing)
        assertTrue(company.availableOnXtb)
        val request = server.takeRequest().body.readUtf8()
        assertTrue("Nasdaq Helsinki" in request && "EUR 66.2 million" in request)
    }

    @Test
    fun `Finnish copy of an English release is irrelevant and a company missing from XTB is skipped before any AI call`() {
        pipeline.process(entry(RAISIO_EN))
        pipeline.process(entry(RAISIO_FI))

        assertEquals(ReportStatus.SKIPPED_NOT_ON_XTB, statusOf(RAISIO_EN))
        assertEquals(ReportStatus.IRRELEVANT, statusOf(RAISIO_FI))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
        assertEquals(1L, jdbc.sql("SELECT count(DISTINCT company_id) FROM report").query(Long::class.java).single())
    }

    @Test
    fun `share class outside the prelist is chosen when XTB offers neither class`() {
        pipeline.process(entry(RAISIO_EN))

        val raisio = companyOf(RAISIO_EN)
        assertEquals("52802", raisio.avanzaId)
        assertEquals("RAIVV", raisio.ticker)
        assertEquals("FI0009002943", raisio.isin)
    }

    @Test
    fun `share class offered on XTB Finland wins over the main list class`() {
        every { xtb.stocks(XtbCountry.FI) } returns xtbFinland + XtbStock("RAIKV", "Raisio Oyj - class K")
        enqueueResultsWithoutNumbers()

        pipeline.process(entry(RAISIO_EN))

        val raisio = companyOf(RAISIO_EN)
        assertEquals("52801", raisio.avanzaId)
        assertEquals("RAIKV", raisio.ticker)
        assertTrue(raisio.availableOnXtb)
    }

    @Test
    fun `profit warning with a preliminary quarter table is scored as results on the FI pool`() {
        every { xtb.stocks(XtbCountry.FI) } returns xtbFinland + XtbStock("RAIVV", "Raisio Oyj")
        enqueueResults("revenue" to amount(57800, "57.8"), "revenuePrevYear" to amount(56700, "56.7"))

        pipeline.process(entry(RAISIO_WARNING))

        assertEquals(ReportStatus.SCORED, statusOf(RAISIO_WARNING))
        assertEquals("RESULTS", eventTypeOf(RAISIO_WARNING))
        val revenue = extractions.find(reportId(RAISIO_WARNING))?.extraction?.periods?.single()?.revenue?.valueInThousands
        assertEquals(0, BigDecimal("57800").compareTo(revenue))
        assertTrue(aiUsage.costUsd(month, AiPool.FI).signum() > 0)
        assertEquals(0, aiUsage.costUsd(month, AiPool.FI_NEWS).signum())
    }

    @Test
    fun `guidance-only profit warning without a primary period becomes news on the FI news pool`() {
        every { contentClient.fetch(any(), any()) } returns ReportContent("Kempower lowers its outlook for 2026.", emptyList(), emptyList())
        enqueueResultsWithoutNumbers()
        enqueueNews()

        pipeline.process(entry(KEMPOWER_WARNING))

        assertEquals(ReportStatus.SCORED, statusOf(KEMPOWER_WARNING))
        assertEquals("NEWS", eventTypeOf(KEMPOWER_WARNING))
        assertEquals(2, server.requestCount)
        assertTrue(aiUsage.costUsd(month, AiPool.FI).signum() > 0)
        assertTrue(aiUsage.costUsd(month, AiPool.FI_NEWS).signum() > 0)
    }

    @Test
    fun `inside information that is not about results is assessed as news on the FI news pool`() {
        enqueueNews()

        pipeline.process(entry(VALMET_INSIDE_INFORMATION))

        assertEquals(ReportStatus.SCORED, statusOf(VALMET_INSIDE_INFORMATION))
        assertEquals("NEWS", eventTypeOf(VALMET_INSIDE_INFORMATION))
        verify(exactly = 1) { notifier.send(match { "(FI) · WYDARZENIE · ocena" in it.title }) }
        assertTrue(aiUsage.costUsd(month, AiPool.FI_NEWS).signum() > 0)
        assertEquals(0, aiUsage.costUsd(month, AiPool.NEWS).signum())
        assertEquals(0, aiUsage.costUsd(month, AiPool.SE_NEWS).signum())
        assertTrue("Rynek: FI" in server.takeRequest().body.readUtf8())
    }

    @Test
    fun `categories outside the routing table are irrelevant without content or AI`() {
        val managers = news.first { it.category == "Managers' Transactions" && it.market == "Main Market, Helsinki" }.disclosureId

        pipeline.process(entry(managers))

        assertEquals(ReportStatus.IRRELEVANT, statusOf(managers))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
    }

    @Test
    fun `exhausted FI pool scores without AI and sends the Finnish budget push once`() {
        budget.record(AiPool.FI, 20_000_000, 0)

        pipeline.process(entry(KEMPOWER_INTERIM))

        assertEquals(0, server.requestCount)
        verify(exactly = 1) {
            notifier.send(match { it.title == "Limit AI wyczerpany" && it.body == "Do końca miesiąca raporty z Finlandii są oceniane bez AI" })
        }
        assertNotNull(settings.find("AI_BUDGET_NOTIFIED_FI_2026-11"))
        assertTrue(budget.canSpend(AiPool.SE))
    }

    @Test
    fun `Stockholm-labelled Finnish issuer offered only on XTB Finland passes the XTB gate as a Swedish company`() {
        every { avanza.findStock("Sampo") } returns AvanzaSearchHit("1501882", "Sampo A", "SE", "Stockholmsbörsen")
        every { avanza.stock("1501882") } returns AvanzaStock("1501882", "Sampo A", "FI4000552500", "SAMPO SEK", "SEK", "Large Cap Stockholm")
        every { avanza.quarters("1501882") } returns emptyList()
        enqueueResultsWithoutNumbers()

        pipeline.process(interimReport(Market.SE, "Sampo", "900201"))

        assertTrue(statusOf("900201") != ReportStatus.SKIPPED_NOT_ON_XTB)
        assertEquals(1, server.requestCount)
        assertTrue(aiUsage.costUsd(month, AiPool.SE).signum() > 0)
        assertTrue(companyOf("900201").availableOnXtb)
    }

    @Test
    fun `Stockholm-labelled issuer offered on neither XTB list is skipped`() {
        every { xtb.stocks(XtbCountry.FI) } returns xtbFinland.filterNot { it.ticker == "SAMPO" }
        every { avanza.findStock("Sampo") } returns AvanzaSearchHit("1501882", "Sampo A", "SE", "Stockholmsbörsen")
        every { avanza.stock("1501882") } returns AvanzaStock("1501882", "Sampo A", "FI4000552500", "SAMPO SEK", "SEK", "Large Cap Stockholm")
        every { avanza.quarters("1501882") } returns emptyList()

        pipeline.process(interimReport(Market.SE, "Sampo", "900202"))

        assertEquals(ReportStatus.SKIPPED_NOT_ON_XTB, statusOf("900202"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `Helsinki release of a company already followed as Swedish is irrelevant without AI`() {
        companies.save(Company(null, "Nokia Oyj", "FI0009000681", Market.SE, "NOKIA SEK", null, false))
        every { avanza.findShareClasses("Nokia Oyj", Market.FI) } returns listOf(AvanzaSearchHit("52784", "Nokia", "FI", "Helsingforsbörsen"))
        every { avanza.stock("52784") } returns AvanzaStock("52784", "Nokia", "FI0009000681", "NOKIA", "EUR", "Large Cap Helsinki")

        pipeline.process(interimReport(Market.FI, "Nokia Oyj", "900203"))

        assertEquals(ReportStatus.IRRELEVANT, statusOf("900203"))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
    }

    @Test
    fun `Stockholm release of a company already followed as Finnish is irrelevant and only the Finnish company feeds quotes`() {
        val finnish = companies.save(Company(null, "Nokia", "FI0009000681", Market.FI, "NOKIA", null, false, avanzaId = "52784"))
        every { avanza.findStock("Nokia Oyj") } returns AvanzaSearchHit("82987", "Nokia", "SE", "Stockholmsbörsen")
        every { avanza.stock("82987") } returns AvanzaStock("82987", "Nokia", "FI0009000681", "NOKIA SEK", "SEK", "Large Cap Stockholm")

        pipeline.process(interimReport(Market.SE, "Nokia Oyj", "900206"))

        assertEquals(ReportStatus.IRRELEVANT, statusOf("900206"))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
        assertQuotesFedOnlyBy(owner = finnish, rejected = companyOf("900206"))
    }

    @Test
    fun `Helsinki release rejected because the issuer is followed as Swedish leaves the quote series to the Swedish company`() {
        val swedish = companies.save(Company(null, "Sampo", "FI4000552500", Market.SE, "SAMPO SEK", null, false, avanzaId = "1501882"))
        every { avanza.findShareClasses("Sampo Oyj", Market.FI) } returns listOf(AvanzaSearchHit("52810", "Sampo A", "FI", "Helsingforsbörsen"))
        every { avanza.stock("52810") } returns AvanzaStock("52810", "Sampo A", "FI4000552500", "SAMPO", "EUR", "Large Cap Helsinki")

        pipeline.process(interimReport(Market.FI, "Sampo Oyj", "900207"))

        assertEquals(ReportStatus.IRRELEVANT, statusOf("900207"))
        assertQuotesFedOnlyBy(owner = swedish, rejected = companyOf("900207"))
    }

    private fun assertQuotesFedOnlyBy(owner: Company, rejected: Company) {
        val quotesAvanza = mockk<AvanzaClient>()
        every { quotesAvanza.dailyQuotes(any(), any(), any(), any()) } returns emptyList()
        val avanzaQuotes = AvanzaQuotes(quotesAvanza, mockk(relaxed = true), quotes, clock, companies)
        assertNotNull(rejected.avanzaId)

        avanzaQuotes.updateCompany(rejected)
        avanzaQuotes.updateCompany(owner)

        verify(exactly = 0) { quotesAvanza.dailyQuotes(checkNotNull(rejected.avanzaId), any(), any(), any()) }
        verify(exactly = 1) { quotesAvanza.dailyQuotes(checkNotNull(owner.avanzaId), checkNotNull(owner.isin), any(), any()) }
    }

    @Test
    fun `XTB outage during identification is retried later instead of becoming not listed or a stored share class`() {
        every { xtb.stocks(XtbCountry.FI) } throws SourceUnavailableException(Source.XTB, RuntimeException("XTB down"))
        every { avanza.findShareClasses("Nokian Tyres plc", Market.FI) } throws AvanzaStockNotFoundException("Nokian Tyres plc")

        pipeline.process(interimReport(Market.FI, "Nokian Tyres plc", "900208"))
        pipeline.process(entry(RAISIO_EN))

        listOf("900208", RAISIO_EN).forEach { sourceRef ->
            val company = companyOf(sourceRef)
            assertEquals(ReportStatus.NEW, statusOf(sourceRef))
            assertTrue(settings.find("AVANZA_IDENTITY_TRIED_${company.id}_2026-11-10").orEmpty().startsWith("UNAVAILABLE_AT_"), sourceRef)
            assertEquals(null, company.avanzaId)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `company whose Nasdaq name Avanza does not know is identified through its XTB Finland ticker`() {
        every { avanza.findShareClasses("Nokian Tyres plc", Market.FI) } throws AvanzaStockNotFoundException("Nokian Tyres plc")
        every { avanza.findStockByTicker("TYRES", Market.FI) } returns AvanzaStock("52785", "Nokian Renkaat", "FI0009005318", "TYRES", "EUR", "Large Cap Helsinki")
        every { avanza.quarters("52785") } returns emptyList()
        enqueueResultsWithoutNumbers()

        pipeline.process(interimReport(Market.FI, "Nokian Tyres plc", "900205"))

        val nokianTyres = companyOf("900205")
        assertEquals("52785", nokianTyres.avanzaId)
        assertEquals("TYRES", nokianTyres.ticker)
        assertTrue(statusOf("900205") != ReportStatus.IRRELEVANT)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `bond-only Finnish issuer without an Avanza share is irrelevant`() {
        every { avanza.findShareClasses("Kuntarahoitus Oyj", Market.FI) } throws AvanzaStockNotFoundException("Kuntarahoitus Oyj")

        pipeline.process(interimReport(Market.FI, "Kuntarahoitus Oyj", "900204"))

        assertEquals(ReportStatus.IRRELEVANT, statusOf("900204"))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contentClient.fetch(any(), any()) }
    }

    private companion object {
        const val KEMPOWER_INTERIM = "1474755"
        const val KEMPOWER_WARNING = "1474694"
        const val RAISIO_EN = "1476137"
        const val RAISIO_FI = "1476133"
        const val RAISIO_WARNING = "1475515"
        const val VALMET_INSIDE_INFORMATION = "1476055"
    }
}
