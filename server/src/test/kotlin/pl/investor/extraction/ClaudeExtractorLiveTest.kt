package pl.investor.extraction

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import io.mockk.every
import io.mockk.mockk
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import pl.investor.source.content.AttachmentTextExtractor
import pl.investor.source.content.ReportContentParser
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "RECORD_AI", matches = "1")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class ClaudeExtractorLiveTest {

    private val resources = Path.of("src/test/resources/fixtures")
    private val mapper = jacksonObjectMapper()
    private val upstream = HttpClient.newHttpClient()

    private class Recorded(var status: Int = 0, var body: String = "")

    @Test
    fun `records an extraction for every report fixture`() {
        val recorded = Recorded()
        val proxy = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = forward(request, recorded)
            }
            start()
        }
        val usage = mutableListOf<Pair<Long, Long>>()
        val budget = mockk<AiBudget>()
        every { budget.canSpend(any()) } returns true
        every { budget.record(any(), any(), any()) } answers { usage += thirdArg<Long>() to secondArg<Long>() }
        val client = AnthropicOkHttpClient.builder()
            .baseUrl(proxy.url("/").toString().trimEnd('/'))
            .apiKey(System.getenv("ANTHROPIC_API_KEY"))
            .build()
        val extractor = ClaudeExtractor(budget, client)
        val aiDir = resources.resolve("ai").also { Files.createDirectories(it) }
        val liveDir = aiDir.resolve("live").also { Files.createDirectories(it) }
        val summary = StringBuilder()
        var totalUsd = BigDecimal.ZERO

        resources.resolve("reports").listDirectoryEntries("*.html").sortedBy { it.name }.forEach { report ->
            val name = report.nameWithoutExtension
            val target = aiDir.resolve("$name.json")
            if (target.exists()) return@forEach
            val content = ReportContentParser().parse(Files.readString(report)).copy(attachmentText = attachmentText(name))
            usage.clear()
            val extraction = extractor.extract(content, companyOf(name), AiPool.GPW)
            assertEquals(200, recorded.status)
            target.writeText(recorded.body)
            val (output, input) = usage.single()
            val costUsd = BigDecimal(input).multiply(BigDecimal("0.0000001")) + BigDecimal(output).multiply(BigDecimal("0.0000005"))
            totalUsd += costUsd
            summary.appendLine("$name input=$input output=$output costUsd=$costUsd")
            summary.appendLine(mapper.writeValueAsString(extraction))
            if (name == "asbis-rb-23-2026") assertAsbisRb23(extraction)
            if (name == "xtb-prelim-q2-2026") assertXtbPrelim(extraction)
            if (name == "asbis-ps-2026") assertAsbisH1(extraction)
        }
        summary.appendLine("TOTAL costUsd=$totalUsd")
        liveDir.resolve("summary.txt").writeText(summary.toString())
        proxy.shutdown()
        assertTrue(totalUsd < BigDecimal("0.05"), "live cost $totalUsd")
    }

    private fun assertAsbisRb23(extraction: Extraction) {
        val period = extraction.periods.single()
        assertWithin(BigDecimal("604000"), requireNotNull(period.revenue).valueInThousands)
        assertWithin(BigDecimal("316000"), requireNotNull(period.revenuePrevYear).valueInThousands)
    }

    private fun assertXtbPrelim(extraction: Extraction) {
        assertEquals(ReportCategory.RESULTS, extraction.category)
        assertTrue(extraction.periods.any { period ->
            period.revenue?.valueInThousands.isNear("992000") && period.netProfit?.valueInThousands.isNear("492000")
        }, "no period with revenue ~992 mln and net profit ~492 mln: ${extraction.periods}")
    }

    private fun assertAsbisH1(extraction: Extraction) {
        assertEquals(ReportCategory.RESULTS, extraction.category)
        assertTrue(
            extraction.periods.any { it.revenue?.valueInThousands.isNear("1696700") || it.revenue?.valueInThousands.isNear("2965176") },
            "no period with Q2 revenue ~1696.7 mln or H1 revenue ~2965 mln: ${extraction.periods}",
        )
    }

    private fun BigDecimal?.isNear(expected: String) =
        this != null && (this - BigDecimal(expected)).abs() <= BigDecimal(expected) * BigDecimal("0.02")

    private fun attachmentText(fixtureName: String): String {
        val pdfs = when (fixtureName) {
            "asbis-ps-2026" -> listOf("asbis-ps-2026-1", "asbis-ps-2026-2", "asbis-ps-2026-4")
            "xtb-prelim-q2-2026" -> listOf("xtb-prelim-q2-2026-1")
            else -> return ""
        }
        val extractor = AttachmentTextExtractor(mockk())
        return extractor.joined(pdfs.map { extractor.relevantPages(Files.readAllBytes(resources.resolve("attachments/$it.pdf"))) })
    }

    private fun assertWithin(expected: BigDecimal, actual: BigDecimal) {
        assertTrue((actual - expected).abs() <= expected * BigDecimal("0.01"), "expected ~$expected but was $actual")
    }

    private fun companyOf(fixtureName: String) = when {
        fixtureName.startsWith("asbis") -> "ASBIS"
        fixtureName.startsWith("xtb") -> "XTB"
        fixtureName.startsWith("crj") -> "CRJ"
        else -> "Spółka"
    }

    private fun forward(request: RecordedRequest, recorded: Recorded): MockResponse {
        val upstreamRequest = HttpRequest.newBuilder(URI.create("https://api.anthropic.com${request.path}"))
            .POST(HttpRequest.BodyPublishers.ofString(request.body.readUtf8()))
            .header("content-type", "application/json")
            .header("x-api-key", System.getenv("ANTHROPIC_API_KEY"))
            .header("anthropic-version", request.getHeader("anthropic-version") ?: "2023-06-01")
            .build()
        val response = upstream.send(upstreamRequest, HttpResponse.BodyHandlers.ofString())
        recorded.status = response.statusCode()
        recorded.body = response.body()
        return MockResponse().setResponseCode(response.statusCode())
            .setHeader("content-type", "application/json")
            .setBody(response.body())
    }
}
