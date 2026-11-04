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
import pl.investor.news.NewsAssessor
import pl.investor.news.NewsContextAssembler
import pl.investor.pipeline.AvanzaCompanyRefresher
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportStatus
import pl.investor.source.content.ReportContentClient
import pl.investor.source.eqs.EqsListingClient
import pl.investor.source.eqs.EqsNewsItem
import pl.investor.support.PostgresTestBase
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "RECORD_AI", matches = "1")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class GermanScoringLiveTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var listing: EqsListingClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var refresher: AvanzaCompanyRefresher
    @Autowired lateinit var contentClient: ReportContentClient
    @Autowired lateinit var assembler: ScoringInputAssembler
    @Autowired lateinit var newsContext: NewsContextAssembler

    private val releases = mapOf(
        "1869172c-aa15-4cff-9a3e-5c5c433ac07b" to "Masterflex Q3 2026",
        "5103e393-9fd3-4a55-8a51-88e74ff147ba" to "Continental Q3 2026",
        "0efb9d9b-7a33-48a4-86f8-8d76de91ca3b" to "Uniper Ad-hoc",
    )

    private val mapper = jacksonObjectMapper()

    @Test
    fun `scores two real German results releases and assesses one Ad-hoc`() {
        jdbc.sql("TRUNCATE company, daily_quote RESTART IDENTITY CASCADE").update()
        jdbc.sql("DELETE FROM setting WHERE key LIKE 'AVANZA_%'").update()
        val usage = mutableListOf<Pair<Long, Long>>()
        val budget = mockk<AiBudget>()
        every { budget.canSpend(any()) } returns true
        every { budget.record(any(), any(), any()) } answers { usage += secondArg<Long>() to thirdArg<Long>() }
        val anthropic = AnthropicOkHttpClient.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).build()
        val extractor = ClaudeExtractor(budget, anthropic)
        val newsAssessor = NewsAssessor(budget, anthropic, QuoteVerifier())
        val items = (1..PAGES).flatMap { listing.page(it) }.filter { it.newsId in releases }.distinctBy { it.newsId }

        val lines = items.map { item -> line(item, extractor, newsAssessor) }

        val (input, output) = usage.fold(0L to 0L) { (i, o), (ui, uo) -> (i + ui) to (o + uo) }
        val costUsd = BigDecimal(input).multiply(BigDecimal("0.0000001")) + BigDecimal(output).multiply(BigDecimal("0.0000005"))
        Files.createDirectories(Path.of("build"))
        Path.of("build/germany-live.txt").writeText((lines + "input=$input output=$output costUsd=$costUsd").joinToString("\n"))
        assertTrue(lines.size == releases.size)
    }

    private fun line(item: EqsNewsItem, extractor: ClaudeExtractor, newsAssessor: NewsAssessor): String {
        val entry = item.toListingEntry()
        val company = companies.save(Company(null, entry.companyName, entry.isin, Market.DE, null, null, false))
        refresher.refresh(company, force = true)
        val identified = companies.findById(checkNotNull(company.id))
        val content = contentClient.fetch(entry)
        val raw = extractor.extract(content, identified.name, AiPool.DE)
        val reportInput = ExtractionPrompt.reportInput(content)
        Files.createDirectories(Path.of("build/germany-live"))
        Path.of("build/germany-live/${item.newsId}-input.txt").writeText(reportInput)
        Path.of("build/germany-live/${item.newsId}-raw.json").writeText(mapper.writeValueAsString(raw))
        val verified = QuoteVerifier().verify(raw, reportInput, Market.DE)
        val report = Report(null, checkNotNull(company.id), Market.DE, entry.kind, entry.number, entry.sourceRef, entry.title, entry.publishedAt, entry.publishedAt, entry.detailUrl, ReportStatus.NEW)
        val input = assembler.assemble(identified, report, verified)
        val score = ScoreCalculator.score(input)
        val news = if (entry.number == "Ad-hoc") {
            newsAssessor.assess(newsContext.input(identified, report, entry.title.orEmpty(), content)).let { "  news=${it.category} ${it.direction} score=${it.score} reason=${it.reason}\n" }
        } else {
            ""
        }
        return "${releases.getValue(item.newsId)}: ${identified.shortName ?: identified.name} ${entry.publishedAt} isin=${identified.isin} ticker=${identified.ticker}\n" +
            "  category=${verified.extraction.category} periods=${mapper.writeValueAsString(verified.extraction.periods)}\n" +
            "  outlook=${verified.extraction.outlook.tone} uncertain=${verified.uncertainFields}\n" +
            "  input=$input\n" +
            "  score=${score.total} components=${score.components} warnings=${score.warnings} signals=${score.signals}\n" + news
    }

    private companion object {
        const val PAGES = 3
    }
}
