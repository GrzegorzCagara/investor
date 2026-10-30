package pl.investor.extraction

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
import pl.investor.history.PeriodKind
import pl.investor.source.content.AttachmentTextExtractor
import pl.investor.source.content.ReportContent
import pl.investor.source.content.ReportContentParser
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClaudeExtractorTest {

    private lateinit var server: MockWebServer
    private lateinit var budget: AiBudget
    private lateinit var extractor: ClaudeExtractor

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        budget = mockk(relaxed = true)
        every { budget.canSpend(any()) } returns true
        val client: AnthropicClient = AnthropicOkHttpClient.builder()
            .baseUrl(server.url("/").toString().trimEnd('/'))
            .apiKey("test-key")
            .maxRetries(0)
            .build()
        extractor = ClaudeExtractor(budget, client)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun fixture(path: String) =
        checkNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readText()

    private fun content(path: String): ReportContent = ReportContentParser().parse(fixture(path))

    private fun json(body: String) = MockResponse().setHeader("content-type", "application/json").setBody(body)

    @Test
    fun `extractor maps a recorded response for ASBIS RB 23`() {
        server.enqueue(json(fixture("ai/asbis-rb-23-2026.json")))

        val extraction = extractor.extract(content("reports/asbis-rb-23-2026.html"), "ASBIS", AiPool.GPW)

        assertEquals(ReportCategory.RESULTS, extraction.category)
        val period = extraction.periods.single()
        assertEquals(PeriodKind.MONTH, period.kind)
        assertEquals(LocalDate.parse("2026-08-31"), period.periodEnd)
        assertEquals("USD", period.currency)
        assertEquals(OutlookTone.NONE, extraction.outlook.tone)
        assertEquals(0, BigDecimal(604000).compareTo(period.revenue?.valueInThousands))
        assertEquals(0, BigDecimal(316000).compareTo(period.revenuePrevYear?.valueInThousands))
        val verified = QuoteVerifier().verify(extraction, content("reports/asbis-rb-23-2026.html").text)
        assertTrue(verified.uncertainFields.isEmpty())
        verify { budget.record(AiPool.GPW, any(), any()) }
    }

    private fun contentWithAttachments(report: String, vararg pdfs: String): ReportContent {
        val extractor = AttachmentTextExtractor(mockk())
        val texts = pdfs.map { extractor.relevantPages(checkNotNull(javaClass.getResourceAsStream("/fixtures/attachments/$it.pdf")).readBytes()) }
        return content("reports/$report.html").copy(attachmentText = extractor.joined(texts))
    }

    private fun BigDecimal?.isWithinTwoPercentOf(expected: Long) =
        this != null && (this - BigDecimal(expected)).abs() <= BigDecimal(expected) * BigDecimal("0.02")

    @Test
    fun `extractor maps a recorded XTB response built from the PDF attachment`() {
        server.enqueue(json(fixture("ai/xtb-prelim-q2-2026.json")))
        val content = contentWithAttachments("xtb-prelim-q2-2026", "xtb-prelim-q2-2026-1")

        val extraction = extractor.extract(content, "XTB", AiPool.GPW)

        assertEquals(ReportCategory.RESULTS, extraction.category)
        val quarter = extraction.periods.single { it.kind == PeriodKind.QUARTER }
        assertEquals("PLN", quarter.currency)
        assertTrue(quarter.revenue?.valueInThousands.isWithinTwoPercentOf(992_000))
        assertTrue(quarter.netProfit?.valueInThousands.isWithinTwoPercentOf(492_000))
        assertEquals(OutlookTone.NONE, extraction.outlook.tone)
        val verified = QuoteVerifier().verify(extraction, ExtractionPrompt.reportInput(content))
        assertEquals(emptySet(), verified.uncertainFields)
    }

    @Test
    fun `extractor maps a recorded ASBIS H1 response built from the PDF attachments`() {
        server.enqueue(json(fixture("ai/asbis-ps-2026.json")))
        val content = contentWithAttachments("asbis-ps-2026", "asbis-ps-2026-1", "asbis-ps-2026-2", "asbis-ps-2026-4")

        val extraction = extractor.extract(content, "ASBIS", AiPool.GPW)

        assertEquals(ReportCategory.RESULTS, extraction.category)
        assertTrue(extraction.periods.any {
            it.currency == "USD" && (it.revenue?.valueInThousands.isWithinTwoPercentOf(1_696_700) || it.revenue?.valueInThousands.isWithinTwoPercentOf(2_965_176))
        })
        assertEquals(OutlookTone.NONE, extraction.outlook.tone)
        val verified = QuoteVerifier().verify(extraction, ExtractionPrompt.reportInput(content))
        assertEquals(emptySet(), verified.uncertainFields)
    }

    @Test
    fun `request carries the configured model, effort, schema format and token limit`() {
        server.enqueue(json(fixture("ai/asbis-rb-23-2026.json")))

        extractor.extract(content("reports/asbis-rb-23-2026.html"), "ASBIS", AiPool.GPW)

        val request = server.takeRequest().body.readUtf8()
        assertTrue("\"model\":\"claude-haiku-5-5\"" in request)
        assertTrue("\"max_tokens\":8000" in request)
        assertTrue("\"effort\":\"low\"" in request)
        assertTrue("\"json_schema\"" in request)
        assertTrue("ASBIS" in request)
        assertTrue("<raport>" in request && "</raport>" in request)
    }

    @Test
    fun `refusal stop reason throws ExtractionFailedException`() {
        server.enqueue(json(message(stopReason = "refusal", content = "[]")))

        assertFailsWith<ExtractionFailedException> {
            extractor.extract(content("reports/asbis-rb-23-2026.html"), "ASBIS", AiPool.GPW)
        }
    }

    @Test
    fun `max_tokens stop reason throws ExtractionFailedException`() {
        server.enqueue(json(message(stopReason = "max_tokens", content = """[{"type":"text","text":"{\"category\":"}]""")))

        assertFailsWith<ExtractionFailedException> {
            extractor.extract(content("reports/asbis-rb-23-2026.html"), "ASBIS", AiPool.GPW)
        }
    }

    @Test
    fun `invalid json throws ExtractionFailedException`() {
        server.enqueue(json(message(stopReason = "end_turn", content = """[{"type":"text","text":"not json"}]""")))

        assertFailsWith<ExtractionFailedException> {
            extractor.extract(content("reports/asbis-rb-23-2026.html"), "ASBIS", AiPool.GPW)
        }
    }

    @Test
    fun `overloaded API maps to AiUnavailableException`() {
        server.enqueue(MockResponse().setResponseCode(529).setBody("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))

        assertFailsWith<AiUnavailableException> {
            extractor.extract(content("reports/asbis-rb-23-2026.html"), "ASBIS", AiPool.GPW)
        }
    }

    @Test
    fun `rate limit maps to AiUnavailableException`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}"""))

        assertFailsWith<AiUnavailableException> {
            extractor.extract(content("reports/asbis-rb-23-2026.html"), "ASBIS", AiPool.GPW)
        }
    }

    @Test
    fun `rejected API key maps to AiAuthenticationException`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))

        assertFailsWith<AiAuthenticationException> {
            extractor.extract(content("reports/asbis-rb-23-2026.html"), "ASBIS", AiPool.GPW)
        }
    }

    @Test
    fun `denied permission maps to AiAuthenticationException`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"type":"error","error":{"type":"permission_error","message":"not allowed"}}"""))

        assertFailsWith<AiAuthenticationException> {
            extractor.extract(content("reports/asbis-rb-23-2026.html"), "ASBIS", AiPool.GPW)
        }
    }

    @Test
    fun `other client errors stay ExtractionFailedException`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}"""))

        assertFailsWith<ExtractionFailedException> {
            extractor.extract(content("reports/asbis-rb-23-2026.html"), "ASBIS", AiPool.GPW)
        }
    }

    @Test
    fun `exhausted budget throws before calling the API`() {
        every { budget.canSpend(AiPool.GPW) } returns false

        assertFailsWith<AiBudgetExhaustedException> {
            extractor.extract(content("reports/asbis-rb-23-2026.html"), "ASBIS", AiPool.GPW)
        }

        assertEquals(0, server.requestCount)
    }

    private fun message(stopReason: String, content: String) =
        """
        {"id":"msg_test","type":"message","role":"assistant","model":"claude-haiku-5-5","content":$content,
         "stop_reason":"$stopReason","stop_sequence":null,"usage":{"input_tokens":100,"output_tokens":10}}
        """.trimIndent()
}
