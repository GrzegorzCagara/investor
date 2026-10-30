package pl.investor.notification

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import pl.investor.extraction.AiBudget
import pl.investor.extraction.AiPool
import pl.investor.extraction.OutlookTone
import pl.investor.report.Company
import pl.investor.report.Market
import pl.investor.scoring.ScoreCalculator
import pl.investor.scoring.ScoringInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReasonWriterTest {

    private lateinit var server: MockWebServer
    private lateinit var budget: AiBudget
    private lateinit var writer: ReasonWriter

    private val asbis = Company(1, "ASBIS", "CY1000031710", Market.GPW, "ASB", "ASBISC", true)
    private val input = ScoringInput(
        revenueYoyPct = 91.0, previousYoyPcts = listOf(89.0, 74.0, 89.0, 74.0), sur = 1.6, operatingProfitYoyPct = null,
        marginChangePp = null, revenueOnly = true, consecutiveStrongBefore = 6, outlook = OutlookTone.POSITIVE, pe = 20.1,
        ttmNetProfitGrowthPct = 150.0, ps = null, psMedian20q = null, cyclical = false, oneOff = false,
        priceChange10Pct = 27.0, indexChange10Pct = 2.0, lowLiquidity = false, aiUsed = true, uncertainNumbers = false,
    )
    private val score = ScoreCalculator.score(input)
    private val templateText = ReasonTemplate().render(asbis, input, score)

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
        writer = ReasonWriter(client, budget, ReasonTemplate())
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

    @Test
    fun `canned AI answer is returned and its cost recorded in the GPW pool`() {
        server.enqueue(json(checkNotNull(javaClass.getResource("/fixtures/ai/reason-canned.json")).readText()))

        val reason = writer.write(asbis, input, score)

        assertTrue(reason.startsWith("Przychody spółki wzrosły o 91%"))
        verify { budget.record(AiPool.GPW, 310, 72) }
        val request = server.takeRequest().body.readUtf8()
        assertTrue("\"claude-haiku-5-5\"" in request)
        assertTrue("\"max_tokens\":1000" in request)
        assertTrue("91.0" in request)
        assertTrue("\"low\"" in request)
    }

    @Test
    fun `exhausted budget gives the template without calling the API`() {
        every { budget.canSpend(AiPool.GPW) } returns false

        assertEquals(templateText, writer.write(asbis, input, score))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `API error gives the template`() {
        server.enqueue(json("""{"type":"error","error":{"type":"api_error","message":"boom"}}""", code = 500))

        assertEquals(templateText, writer.write(asbis, input, score))
    }

    @Test
    fun `refusal gives the template`() {
        server.enqueue(
            json(
                checkNotNull(javaClass.getResource("/fixtures/ai/reason-canned.json")).readText().replace("\"end_turn\"", "\"refusal\""),
            ),
        )

        assertEquals(templateText, writer.write(asbis, input, score))
    }
}
