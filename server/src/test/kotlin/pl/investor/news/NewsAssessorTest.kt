package pl.investor.news

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.investor.extraction.AiAuthenticationException
import pl.investor.extraction.AiBudget
import pl.investor.extraction.AiBudgetExhaustedException
import pl.investor.extraction.AiPool
import pl.investor.extraction.AiUnavailableException
import pl.investor.extraction.QuoteVerifier
import pl.investor.report.Market
import pl.investor.source.content.ReportContent
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsAssessorTest {

    private lateinit var server: MockWebServer
    private lateinit var budget: AiBudget
    private lateinit var assessor: NewsAssessor
    private val mapper = jacksonObjectMapper()

    private val body = "Zarząd Odlewni Polskich S.A. informuje, że w dniu 23 lutego 2026 r. Spółka zawarła z Polską Grupą Zbrojeniową S.A. " +
        "umowę na dostawę odlewów o wartości 18,4 mln zł netto. Realizacja potrwa do końca 2027 r."

    private fun input(revenue: BigDecimal? = BigDecimal("92000")) = NewsInput(
        companyName = "ODLEWNIE POLSKIE SA",
        market = Market.GPW,
        title = "Zawarcie istotnej umowy z Polską Grupą Zbrojeniową S.A.",
        content = ReportContent(body, emptyList(), emptyList()),
        trailingRevenue = revenue,
    )

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
        assessor = NewsAssessor(budget, client, QuoteVerifier())
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun respond(assessment: Map<String, Any?>, stopReason: String = "end_turn") {
        val text = mapper.writeValueAsString(assessment)
        val message = mapOf(
            "id" to "msg_test", "type" to "message", "role" to "assistant", "model" to "claude-haiku-5-5",
            "content" to listOf(mapOf("type" to "text", "text" to text)),
            "stop_reason" to stopReason, "stop_sequence" to null,
            "usage" to mapOf("input_tokens" to 900, "output_tokens" to 60),
        )
        server.enqueue(MockResponse().setHeader("content-type", "application/json").setBody(mapper.writeValueAsString(message)))
    }

    private fun assessment(
        category: NewsCategory = NewsCategory.CONTRACT,
        direction: NewsDirection = NewsDirection.POSITIVE,
        impact: Int = 72,
        contractValue: Map<String, Any>? = mapOf("valueInThousands" to 18400, "currency" to "PLN", "sourceText" to "18,4 mln zł"),
    ) = mapOf(
        "category" to category.name,
        "direction" to direction.name,
        "impact" to impact,
        "contractValue" to contractValue,
        "reason" to " Umowa to około 20% rocznych przychodów spółki. ",
    )

    @Test
    fun `request carries the model, effort, schema, token limit and the wrapped announcement`() {
        respond(assessment())

        assessor.assess(input())

        val request = server.takeRequest().body.readUtf8()
        assertTrue("\"model\":\"claude-haiku-5-5\"" in request)
        assertTrue("\"max_tokens\":2000" in request)
        assertTrue("\"effort\":\"low\"" in request)
        assertTrue("\"json_schema\"" in request)
        assertTrue("TENDER_OFFER" in request && "LEGAL_REGULATORY" in request)
        assertTrue("Spółka: ODLEWNIE POLSKIE SA" in request)
        assertTrue("Rynek: GPW" in request)
        assertTrue("Przychody z ostatnich 12 miesięcy: 92000 tys. zł" in request)
        assertTrue("<komunikat>\\nTytuł: Zawarcie istotnej umowy z Polską Grupą Zbrojeniową S.A." in request)
        assertTrue("</komunikat>" in request)
        verify { budget.record(AiPool.NEWS, 900, 60) }
    }

    @Test
    fun `unknown revenue is stated in the prompt and the body is truncated`() {
        respond(assessment(contractValue = null))
        val long = input(revenue = null).copy(content = ReportContent("x".repeat(9000), emptyList(), emptyList()))

        assessor.assess(long)

        val request = server.takeRequest().body.readUtf8()
        assertTrue("Przychody z ostatnich 12 miesięcy: brak danych" in request)
        assertTrue("x".repeat(NewsPrompt.MAX_BODY_CHARS) in request)
        assertTrue("x".repeat(NewsPrompt.MAX_BODY_CHARS + 1) !in request)
    }

    @Test
    fun `verified PLN contract keeps its value and gets the revenue share`() {
        respond(assessment())

        val result = assessor.assess(input())

        assertEquals(NewsCategory.CONTRACT, result.category)
        assertEquals(72.0, result.impact)
        assertEquals(72.0, result.score)
        assertEquals(0, BigDecimal("18400").compareTo(result.contractValue))
        assertEquals(ContractCurrency.PLN, result.contractCurrency)
        assertEquals(20.0, result.revenueSharePct)
        assertEquals("Umowa to około 20% rocznych przychodów spółki.", result.reason)
    }

    @Test
    fun `contract value not found in the announcement is dropped and the impact capped`() {
        respond(assessment(impact = 85, contractValue = mapOf("valueInThousands" to 48000, "currency" to "PLN", "sourceText" to "48 mln zł")))

        val result = assessor.assess(input())

        assertNull(result.contractValue)
        assertNull(result.revenueSharePct)
        assertEquals(60.0, result.impact)
    }

    @Test
    fun `contract value that does not match the quoted amount is dropped`() {
        respond(assessment(impact = 85, contractValue = mapOf("valueInThousands" to 184000, "currency" to "PLN", "sourceText" to "18,4 mln zł")))

        assertNull(assessor.assess(input()).contractValue)
    }

    @Test
    fun `contract without a value is capped at 60`() {
        respond(assessment(impact = 90, contractValue = null))

        assertEquals(60.0, assessor.assess(input()).impact)
    }

    @Test
    fun `foreign currency contract has no revenue share and unknown revenue gives none either`() {
        val euro = input().copy(content = ReportContent("Umowa o wartości 4,5 mln EUR.", emptyList(), emptyList()))
        respond(assessment(contractValue = mapOf("valueInThousands" to 4500, "currency" to "EUR", "sourceText" to "4,5 mln EUR")))
        respond(assessment())

        val foreign = assessor.assess(euro)
        val withoutRevenue = assessor.assess(input(revenue = null))

        assertEquals(ContractCurrency.EUR, foreign.contractCurrency)
        assertNull(foreign.revenueSharePct)
        assertEquals(0, BigDecimal("18400").compareTo(withoutRevenue.contractValue))
        assertNull(withoutRevenue.revenueSharePct)
    }

    @Test
    fun `contract in another currency keeps its value without a revenue share`() {
        val other = input().copy(content = ReportContent("Umowa o wartości 120 mln RON.", emptyList(), emptyList()))
        respond(assessment(contractValue = mapOf("valueInThousands" to 120000, "currency" to "OTHER", "sourceText" to "120 mln RON")))

        val result = assessor.assess(other)

        assertEquals(ContractCurrency.OTHER, result.contractCurrency)
        assertEquals(0, BigDecimal("120000").compareTo(result.contractValue))
        assertNull(result.revenueSharePct)
        assertTrue("\"PLN\",\"SEK\",\"EUR\",\"USD\",\"GBP\",\"CHF\",\"CZK\",\"BGN\",\"NOK\",\"DKK\",\"OTHER\"" in server.takeRequest().body.readUtf8())
    }

    @Test
    fun `value given for a non-contract category is ignored and impact is clamped`() {
        respond(assessment(category = NewsCategory.LISTING, impact = 130))

        val result = assessor.assess(input())

        assertNull(result.contractValue)
        assertEquals(100.0, result.impact)
    }

    @Test
    fun `negative and neutral news score zero`() {
        respond(assessment(category = NewsCategory.SHARE_ISSUE, direction = NewsDirection.NEGATIVE, impact = 80, contractValue = null))
        respond(assessment(category = NewsCategory.OTHER, direction = NewsDirection.NEUTRAL, impact = 15, contractValue = null))

        assertEquals(0.0, assessor.assess(input()).score)
        assertEquals(0.0, assessor.assess(input()).score)
    }

    @Test
    fun `exhausted NEWS pool throws before calling the API`() {
        every { budget.canSpend(AiPool.NEWS) } returns false

        assertFailsWith<AiBudgetExhaustedException> { assessor.assess(input()) }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `overloaded API maps to AiUnavailableException`() {
        server.enqueue(MockResponse().setResponseCode(529).setBody("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))

        assertFailsWith<AiUnavailableException> { assessor.assess(input()) }
    }

    @Test
    fun `rejected API key maps to AiAuthenticationException`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))

        assertFailsWith<AiAuthenticationException> { assessor.assess(input()) }
    }

    @Test
    fun `other client errors, refusals and invalid json fail the assessment`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}"""))
        respond(assessment(), stopReason = "refusal")
        server.enqueue(
            MockResponse().setHeader("content-type", "application/json").setBody(
                """{"id":"m","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":"not json"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""",
            ),
        )

        repeat(3) { assertFailsWith<NewsAssessmentFailedException> { assessor.assess(input()) } }
    }
}
