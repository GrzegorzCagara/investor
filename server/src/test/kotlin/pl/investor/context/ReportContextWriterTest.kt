package pl.investor.context

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.EmptyResultDataAccessException
import pl.investor.extraction.AiBudget
import pl.investor.extraction.AiPool
import pl.investor.extraction.ExtractionRepository
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.EventType
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.scoring.Score
import pl.investor.scoring.ScoreComponents
import pl.investor.scoring.ScoreRepository
import pl.investor.scoring.StoredScore
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.source.consensus.BankierHeadline
import pl.investor.source.consensus.ConsensusClient
import pl.investor.source.consensus.ConsensusRepository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class ReportContextWriterTest {

    private val now = Instant.parse("2026-11-06T08:00:00Z")
    private val publishedAt = Instant.parse("2026-11-05T18:45:00Z")
    private val reports = mockk<ReportRepository>()
    private val companies = mockk<CompanyRepository>()
    private val extractions = mockk<ExtractionRepository>()
    private val scores = mockk<ScoreRepository>()
    private val consensusNotes = mockk<ConsensusRepository>()
    private val consensusClient = mockk<ConsensusClient>()
    private val contexts = mockk<ReportContextRepository>(relaxed = true)
    private val mapper = jacksonObjectMapper()
    private lateinit var server: MockWebServer
    private lateinit var budget: AiBudget
    private lateinit var writer: ReportContextWriter

    private val unimot = Company(7, "UNIMOT SA", "PLUNMOT00013", Market.GPW, "UNT", "UNIMOT", false, "UNIMOT")
    private val report = report(1254, publishedAt)
    private val headline = BankierHeadline("Unimot zaskoczył wynikami", "https://www.bankier.pl/wiadomosc/unimot-1.html", Instant.parse("2026-11-05T19:15:00Z"))

    private fun report(id: Long, at: Instant, market: Market = Market.GPW, eventType: EventType = EventType.RESULTS, title: String = "Wstępne dane za III kwartał") =
        Report(id, 7, market, ReportKind.CURRENT, "45/2026", "ref-$id", title, at, at, "https://www.gpw.pl/komunikat?geru_id=$id", ReportStatus.SCORED, eventType = eventType)

    private fun score(total: Double) = StoredScore(Score(total, ScoreComponents(25.3, 20.0, 7.5, 0.0, 5.0, 7.5), 0.0, emptySet()), "Powód.")

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        budget = mockk(relaxed = true)
        every { budget.canSpend(any()) } returns true
        val client = AnthropicOkHttpClient.builder()
            .baseUrl(server.url("/").toString().trimEnd('/'))
            .apiKey("test-key")
            .maxRetries(0)
            .build()
        every { reports.findById(1254) } returns report
        every { reports.relevantBetween(7, any(), any(), any()) } returns emptyList()
        every { companies.findById(7) } returns unimot
        every { extractions.find(1254) } returns null
        every { scores.find(1254) } returns score(77.5)
        every { consensusNotes.find(1254) } returns null
        every { consensusClient.headlines(unimot, any(), any(), any()) } returns listOf(headline)
        every { contexts.exists(1254) } returns false
        writer = ReportContextWriter(
            reports, companies, extractions, scores, consensusNotes, consensusClient, contexts,
            ContextPointsWriter(budget, client), Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun respondWithPoints() {
        val text = mapper.writeValueAsString(
            mapOf(
                "points" to listOf(
                    mapOf("direction" to "PLUS", "text" to "Zysk wyraźnie powyżej oczekiwań analityków."),
                    mapOf("direction" to "MINUS", "text" to "Kurs wzrósł już mocno od początku roku."),
                ),
            ),
        )
        val message = mapOf(
            "id" to "msg_test", "type" to "message", "role" to "assistant", "model" to "claude-haiku-5-5",
            "content" to listOf(mapOf("type" to "text", "text" to text)),
            "stop_reason" to "end_turn", "stop_sequence" to null,
            "usage" to mapOf("input_tokens" to 900, "output_tokens" to 120),
        )
        server.enqueue(MockResponse().setHeader("content-type", "application/json").setBody(mapper.writeValueAsString(message)))
    }

    @Test
    fun `due report gets points and headlines saved`() {
        respondWithPoints()
        val saved = slot<ReportContext>()
        every { contexts.save(1254, capture(saved)) } returns Unit

        writer.writeIfDue(1254)

        assertEquals(
            listOf(
                ContextPoint(ContextDirection.PLUS, "Zysk wyraźnie powyżej oczekiwań analityków."),
                ContextPoint(ContextDirection.MINUS, "Kurs wzrósł już mocno od początku roku."),
            ),
            saved.captured.points,
        )
        assertEquals(listOf(headline), saved.captured.headlines)
        assertEquals(now, saved.captured.createdAt)
        verify { consensusClient.headlines(unimot, publishedAt.minus(Duration.ofDays(14)), now, 15) }
        verify { budget.record(AiPool.GPW, 900, 120) }
    }

    @Test
    fun `earlier ESPI titles from 30 days go to the model without the report itself`() {
        respondWithPoints()
        every { consensusClient.headlines(any(), any(), any(), any()) } returns emptyList()
        every { reports.relevantBetween(7, publishedAt.minus(Duration.ofDays(30)), publishedAt, 11) } returns listOf(
            report, report(1200, Instant.parse("2026-10-01T12:38:00Z"), title = "Objęcie akcji PZL Sędziszów"),
        )

        assertEquals(ContextOutcome.WRITTEN, writer.rewrite(1254))

        val request = server.takeRequest().body.readUtf8()
        assertEquals(true, "<komunikaty>\\n2026-10-01: Objęcie akcji PZL Sędziszów\\n</komunikaty>" in request)
    }

    @Test
    fun `report below 50 gets no context and makes no calls`() {
        every { scores.find(1254) } returns score(49.9)

        writer.writeIfDue(1254)

        assertEquals(0, server.requestCount)
        verify(exactly = 0) { consensusClient.headlines(any(), any(), any(), any()) }
        verify(exactly = 0) { contexts.save(any(), any()) }
    }

    @Test
    fun `stored context is not written again but the admin rewrite replaces it`() {
        every { contexts.exists(1254) } returns true

        writer.writeIfDue(1254)
        assertEquals(0, server.requestCount)

        respondWithPoints()
        assertEquals(ContextOutcome.WRITTEN, writer.rewrite(1254))
        verify(exactly = 1) { contexts.save(1254, any()) }
    }

    @Test
    fun `admin rewrite ignores the score floor but not the market or event type`() {
        every { scores.find(1254) } returns score(30.0)
        respondWithPoints()
        assertEquals(ContextOutcome.WRITTEN, writer.rewrite(1254))

        every { reports.findById(1254) } returns report(1254, publishedAt, market = Market.SE)
        assertEquals(ContextOutcome.NOT_ELIGIBLE, writer.rewrite(1254))

        every { reports.findById(1254) } returns report(1254, publishedAt, eventType = EventType.NEWS)
        assertEquals(ContextOutcome.NOT_ELIGIBLE, writer.rewrite(1254))
    }

    @Test
    fun `unknown report and unscored report are reported as such`() {
        every { reports.findById(9) } throws EmptyResultDataAccessException(1)
        every { scores.find(9) } returns null
        every { scores.find(1254) } returns null

        assertEquals(ContextOutcome.NOT_FOUND, writer.rewrite(9))
        assertEquals(ContextOutcome.NOT_ELIGIBLE, writer.rewrite(1254))
    }

    @Test
    fun `no headline and no ESPI title stores an empty context without an AI call`() {
        every { consensusClient.headlines(any(), any(), any(), any()) } returns emptyList()

        assertEquals(ContextOutcome.NOTHING_TO_ADD, writer.writeIfDue(1254))
        assertEquals(0, server.requestCount)
        verify(exactly = 1) { contexts.save(1254, ReportContext(emptyList(), emptyList(), now)) }
    }

    @Test
    fun `headlines of an old report end two days after its publication`() {
        val old = Instant.parse("2026-09-01T15:00:00Z")
        every { reports.findById(1254) } returns report(1254, old)
        respondWithPoints()

        writer.rewrite(1254)

        verify { consensusClient.headlines(unimot, old.minus(Duration.ofDays(14)), old.plus(Duration.ofDays(2)), 15) }
    }

    @Test
    fun `due reports come from the last six hours of status changes, ten at most`() {
        every { contexts.due(now.minus(Duration.ofHours(6)), 50.0, 10) } returns listOf(1254L)

        assertEquals(listOf(1254L), writer.dueReportIds())
    }

    @Test
    fun `exhausted budget gives no context and does not throw`() {
        every { budget.canSpend(AiPool.GPW) } returns false

        assertEquals(ContextOutcome.FAILED, writer.writeIfDue(1254))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contexts.save(any(), any()) }
    }

    @Test
    fun `AI failure gives no context and does not throw`() {
        server.enqueue(MockResponse().setResponseCode(529).setBody("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}"""))

        assertEquals(ContextOutcome.FAILED, writer.writeIfDue(1254))
        assertEquals(ContextOutcome.FAILED, writer.rewrite(1254))
        verify(exactly = 0) { contexts.save(any(), any()) }
    }

    @Test
    fun `unavailable Bankier is a retryable outcome without a stored context`() {
        every { consensusClient.headlines(any(), any(), any(), any()) } throws SourceUnavailableException(Source.BANKIER_NEWS, RuntimeException("down"))

        assertEquals(ContextOutcome.SOURCE_UNAVAILABLE, writer.writeIfDue(1254))
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { contexts.save(any(), any()) }
    }

    @Test
    fun `output without usable points saves nothing`() {
        val message = mapOf(
            "id" to "msg_test", "type" to "message", "role" to "assistant", "model" to "claude-haiku-5-5",
            "content" to listOf(mapOf("type" to "text", "text" to """{"points":[{"direction":"PLUS","text":"  "}]}""")),
            "stop_reason" to "end_turn", "stop_sequence" to null,
            "usage" to mapOf("input_tokens" to 900, "output_tokens" to 10),
        )
        server.enqueue(MockResponse().setHeader("content-type", "application/json").setBody(mapper.writeValueAsString(message)))

        assertEquals(ContextOutcome.FAILED, writer.rewrite(1254))
        verify(exactly = 0) { contexts.save(any(), any()) }
    }
}
