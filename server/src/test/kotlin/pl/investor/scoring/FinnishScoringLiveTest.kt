package pl.investor.scoring

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.extraction.AiBudget
import pl.investor.extraction.AiPool
import pl.investor.extraction.ClaudeExtractor
import pl.investor.extraction.ExtractionPrompt
import pl.investor.extraction.QuoteVerifier
import pl.investor.pipeline.AvanzaCompanyRefresher
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportStatus
import pl.investor.source.content.ReportContentClient
import pl.investor.source.nasdaq.NasdaqList
import pl.investor.source.nasdaq.NasdaqNewsItem
import pl.investor.source.nasdaq.NasdaqNordicListingClient
import pl.investor.support.PostgresTestBase
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "RECORD_AI", matches = "1")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class FinnishScoringLiveTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var nasdaq: NasdaqNordicListingClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var refresher: AvanzaCompanyRefresher
    @Autowired lateinit var contentClient: ReportContentClient
    @Autowired lateinit var assembler: ScoringInputAssembler

    private val releases = mapOf(
        "1476137" to "Raisio Q3 2026 interim",
        "1474755" to "Kempower Q3 2026 interim",
        "1475515" to "Raisio profit warning",
    )

    private val mapper = jacksonObjectMapper()

    @Test
    fun `scores three real Finnish releases`() {
        jdbc.sql("TRUNCATE company, daily_quote RESTART IDENTITY CASCADE").update()
        jdbc.sql("DELETE FROM setting WHERE key LIKE 'AVANZA_%'").update()
        val usage = mutableListOf<Pair<Long, Long>>()
        val budget = mockk<AiBudget>()
        every { budget.canSpend(any()) } returns true
        every { budget.record(any(), any(), any()) } answers { usage += secondArg<Long>() to thirdArg<Long>() }
        val extractor = ClaudeExtractor(budget, AnthropicOkHttpClient.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).build())
        val items = (0 until PAGES).flatMap { nasdaq.page(NasdaqList.HELSINKI, it * NasdaqNordicListingClient.PAGE_SIZE) }
            .filter { it.disclosureId in releases }
            .distinctBy { it.disclosureId }

        val lines = items.map { line(it, extractor) }

        val (input, output) = usage.fold(0L to 0L) { (i, o), (ui, uo) -> (i + ui) to (o + uo) }
        val costUsd = BigDecimal(input).multiply(BigDecimal("0.0000001")) + BigDecimal(output).multiply(BigDecimal("0.0000005"))
        Files.createDirectories(Path.of("build"))
        Path.of("build/finland-live.txt").writeText((lines + "input=$input output=$output costUsd=$costUsd").joinToString("\n"))
        assertTrue(lines.size == releases.size)
    }

    private fun line(item: NasdaqNewsItem, extractor: ClaudeExtractor): String {
        val entry = item.toListingEntry(Market.FI)
        val company = companies.findByName(entry.companyName, Market.FI)
            ?: companies.save(Company(null, entry.companyName, null, Market.FI, null, null, false))
        refresher.refresh(company, force = true)
        val identified = companies.findById(checkNotNull(company.id))
        val content = contentClient.fetch(entry, includeAttachments = entry.kind.isPeriodic)
        val raw = extractor.extract(content, identified.name, AiPool.FI)
        val reportInput = ExtractionPrompt.reportInput(content)
        Files.createDirectories(Path.of("build/finland-live"))
        Path.of("build/finland-live/${item.disclosureId}-input.txt").writeText(reportInput)
        Path.of("build/finland-live/${item.disclosureId}-raw.json").writeText(mapper.writeValueAsString(raw))
        val verified = QuoteVerifier().verify(raw, reportInput, Market.FI)
        val report = Report(null, checkNotNull(company.id), Market.FI, entry.kind, entry.number, entry.sourceRef, entry.title, entry.publishedAt, entry.publishedAt, entry.detailUrl, ReportStatus.NEW)
        val input = assembler.assemble(identified, report, verified)
        val score = ScoreCalculator.score(input)
        return "${releases.getValue(item.disclosureId)}: ${identified.shortName ?: identified.name} ${entry.publishedAt} isin=${identified.isin} ticker=${identified.ticker} " +
            "inputChars=${reportInput.length} pdf=${content.attachmentText.isNotEmpty()}\n" +
            "  category=${verified.extraction.category} periods=${mapper.writeValueAsString(verified.extraction.periods)}\n" +
            "  outlook=${verified.extraction.outlook.tone} uncertain=${verified.uncertainFields}\n" +
            "  input=$input\n" +
            "  score=${score.total} components=${score.components} warnings=${score.warnings} signals=${score.signals}\n"
    }

    private companion object {
        const val PAGES = 2
    }
}
