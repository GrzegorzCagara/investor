package pl.investor.source.consensus

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.investor.extraction.AiBudget
import pl.investor.extraction.AiBudgetExhaustedException
import pl.investor.extraction.AiPool
import pl.investor.extraction.QuoteVerifier
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsensusTextReaderTest {

    private lateinit var server: MockWebServer
    private lateinit var budget: AiBudget
    private lateinit var reader: ConsensusTextReader
    private val mapper = jacksonObjectMapper()

    private val body = checkNotNull(
        ConsensusNoteParser().parseArticle(
            checkNotNull(javaClass.getResource("/fixtures/bankier/unimot-konsensus-prelim-q3-2026.html")).readText(),
        ).body,
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
        reader = ConsensusTextReader(budget, client, QuoteVerifier())
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun amount(valueInThousands: Long, sourceText: String) = mapOf("valueInThousands" to valueInThousands, "sourceText" to sourceText)

    private fun row(label: ConsensusLabel, actual: Map<String, Any>, consensus: Map<String, Any>) =
        mapOf("label" to label.name, "actual" to actual, "consensus" to consensus)

    private val adjustedEbitda = row(ConsensusLabel.ADJUSTED_EBITDA, amount(133_000, "133 mln zł"), amount(108_800, "108,8 mln zł"))
    private val revenue = row(ConsensusLabel.REVENUE, amount(6_000_000, "6 mld zł"), amount(4_776_000, "4,776 mld zł"))

    private fun respond(vararg rows: Map<String, Any>, stopReason: String = "end_turn", aboutCompany: Boolean = true) =
        respondWithText(mapper.writeValueAsString(mapOf("aboutCompany" to aboutCompany, "rows" to rows.toList())), stopReason)

    private fun respondWithText(text: String, stopReason: String = "end_turn") {
        val message = mapOf(
            "id" to "msg_test", "type" to "message", "role" to "assistant", "model" to "claude-haiku-5-5",
            "content" to listOf(mapOf("type" to "text", "text" to text)),
            "stop_reason" to stopReason, "stop_sequence" to null,
            "usage" to mapOf("input_tokens" to 1200, "output_tokens" to 90),
        )
        server.enqueue(MockResponse().setHeader("content-type", "application/json").setBody(mapper.writeValueAsString(message)))
    }

    @Test
    fun `Unimot prelim note gives verified rows in millions with our own difference`() {
        respond(adjustedEbitda, revenue)

        val rows = reader.read("UNIMOT SA", body)

        assertEquals(
            listOf(
                ConsensusRow("EBITDA skorygowana", BigDecimal("133"), BigDecimal("108.8"), 22.2),
                ConsensusRow("Przychody", BigDecimal("6000"), BigDecimal("4776"), 25.6),
            ),
            rows,
        )
    }

    @Test
    fun `request carries the model, effort, schema, company and the wrapped note`() {
        respond(adjustedEbitda)

        reader.read("UNIMOT SA", body)

        val request = server.takeRequest().body.readUtf8()
        assertTrue("\"model\":\"claude-haiku-5-5\"" in request)
        assertTrue("\"effort\":\"low\"" in request)
        assertTrue("\"json_schema\"" in request)
        assertTrue("ADJUSTED_EBITDA" in request && "NET_PROFIT" in request)
        assertTrue("Spółka: UNIMOT SA" in request)
        assertTrue("<notatka>\\nSzacunkowa skorygowana EBITDA Unimotu" in request)
        assertTrue("</notatka>" in request)
        verify { budget.record(AiPool.GPW, 1200, 90) }
    }

    @Test
    fun `hallucinated consensus number fails verification and its row is dropped`() {
        respond(adjustedEbitda, row(ConsensusLabel.NET_PROFIT, amount(95_000, "95 mln zł"), amount(80_000, "80 mln zł")))

        assertEquals(listOf("EBITDA skorygowana"), reader.read("UNIMOT SA", body).map { it.label })
    }

    @Test
    fun `value that does not match its quoted text is dropped`() {
        respond(row(ConsensusLabel.ADJUSTED_EBITDA, amount(1_330_000, "133 mln zł"), amount(108_800, "108,8 mln zł")), revenue)

        assertEquals(listOf("Przychody"), reader.read("UNIMOT SA", body).map { it.label })
    }

    @Test
    fun `repeated label keeps the first row`() {
        respond(adjustedEbitda, row(ConsensusLabel.ADJUSTED_EBITDA, amount(290_000, "290 mln zł"), amount(108_800, "108,8 mln zł")))

        assertEquals(listOf(BigDecimal("133")), reader.read("UNIMOT SA", body).map { it.actual })
    }

    @Test
    fun `note without a comparison gives no rows`() {
        respond()

        assertTrue(reader.read("UNIMOT SA", body).isEmpty())
    }

    @Test
    fun `exhausted budget makes no call`() {
        every { budget.canSpend(AiPool.GPW) } returns false

        assertFailsWith<AiBudgetExhaustedException> { reader.read("UNIMOT SA", body) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `truncated response fails the reading without being malformed`() {
        respond(adjustedEbitda, stopReason = "max_tokens")

        val error = assertFailsWith<ConsensusReadingFailedException> { reader.read("UNIMOT SA", body) }
        assertFalse(error is MalformedConsensusReadingException)
    }

    @Test
    fun `output that does not fit the schema is malformed`() {
        respondWithText("""{"rows": []}""")

        assertFailsWith<MalformedConsensusReadingException> { reader.read("UNIMOT SA", body) }
    }

    @Test
    fun `note about another company gives no rows`() {
        respond(adjustedEbitda, revenue, aboutCompany = false)

        assertTrue(reader.read("ORLEN SA", body).isEmpty())
    }

    @Test
    fun `prompt asks only for the named company and for no unsigned losses`() {
        respond(adjustedEbitda)

        reader.read("UNIMOT SA", body)

        val request = server.takeRequest().body.readUtf8()
        assertTrue("aboutCompany" in request)
        assertTrue("Liczą się wyłącznie wyniki podanej spółki" in request)
        assertTrue("Pomiń każdą kwotę straty, która w tekście nie jest zapisana ze znakiem minus" in request)
    }

    private val lossNote = "Przychody wyniosły 300 mln zł wobec oczekiwanych 280 mln zł. W III kwartale strata netto wyniosła 5 mln zł. " +
        "Konsensus PAP Biznes zakładał 12 mln zł straty netto. Wynik operacyjny to -4 mln zł, a analitycy spodziewali się -2 mln zł."

    @Test
    fun `loss written without a minus sign is dropped`() {
        respond(
            row(ConsensusLabel.NET_PROFIT, amount(5_000, "5 mln zł"), amount(12_000, "12 mln zł")),
            row(ConsensusLabel.REVENUE, amount(300_000, "300 mln zł"), amount(280_000, "280 mln zł")),
        )

        assertEquals(listOf("Przychody"), reader.read("SPOLKA SA", lossNote).map { it.label })
    }

    @Test
    fun `loss quoted together with the word loss is dropped`() {
        respond(row(ConsensusLabel.NET_PROFIT, amount(-4_000, "-4 mln zł"), amount(12_000, "12 mln zł straty")))

        assertTrue(reader.read("SPOLKA SA", lossNote).isEmpty())
    }

    @Test
    fun `loss written with a minus sign is kept`() {
        respond(row(ConsensusLabel.EBIT, amount(-4_000, "-4 mln zł"), amount(-2_000, "-2 mln zł")))

        assertEquals(listOf(ConsensusRow("EBIT", BigDecimal("-4"), BigDecimal("-2"), -100.0)), reader.read("SPOLKA SA", lossNote))
    }
}
