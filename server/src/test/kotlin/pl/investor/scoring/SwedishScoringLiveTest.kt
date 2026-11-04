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
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import pl.investor.source.content.ReportContentClient
import pl.investor.source.nasdaq.NasdaqNewsParser
import pl.investor.source.placera.ConsensusPreviewRepository
import pl.investor.source.placera.PlaceraParser
import pl.investor.support.PostgresTestBase
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "RECORD_AI", matches = "1")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class SwedishScoringLiveTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var http: PoliteHttpClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var refresher: AvanzaCompanyRefresher
    @Autowired lateinit var contentClient: ReportContentClient
    @Autowired lateinit var previews: ConsensusPreviewRepository
    @Autowired lateinit var assembler: ScoringInputAssembler

    private class Case(val freeText: String, val market: String, val disclosureId: String)

    private val cases = listOf(
        Case("Mips", "Main Market, Stockholm", "1471355"),
        Case("Prevas", "Main Market, Stockholm", "1472809"),
        Case("Nelly", "Main Market, Stockholm", "1471790"),
        Case("FlexQube", "First North Sweden", "1473408"),
    )

    private val mapper = jacksonObjectMapper()

    @Test
    fun `scores four real Swedish interim reports`() {
        jdbc.sql("TRUNCATE company, daily_quote, consensus_preview RESTART IDENTITY CASCADE").update()
        jdbc.sql("DELETE FROM setting WHERE key LIKE 'AVANZA_%'").update()
        previews.save(PlaceraParser.article(fixture("placera-mips-q3-2026.html")).toPreview("https://www.placera.se/telegram/mips", "SE0009216278", Instant.now()))
        val usage = mutableListOf<Pair<Long, Long>>()
        val budget = mockk<AiBudget>()
        every { budget.canSpend(any()) } returns true
        every { budget.record(any(), any(), any()) } answers { usage += secondArg<Long>() to thirdArg<Long>() }
        val extractor = ClaudeExtractor(budget, AnthropicOkHttpClient.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).build())

        val lines = cases.map { case ->
            val entry = NasdaqNewsParser.parse(http.get(Source.NASDAQ_NORDIC, searchUrl(case)))
                .single { it.disclosureId == case.disclosureId }
                .toListingEntry()
            val company = companies.save(Company(null, entry.companyName, null, Market.SE, null, null, false))
            refresher.refresh(company, force = true)
            val identified = companies.findById(checkNotNull(company.id))
            val content = contentClient.fetch(entry, includeAttachments = true)
            val raw = extractor.extract(content, identified.name, AiPool.SE)
            val reportInput = ExtractionPrompt.reportInput(content)
            Files.createDirectories(Path.of("build/sweden-live"))
            Path.of("build/sweden-live/${case.disclosureId}-input.txt").writeText(reportInput)
            Path.of("build/sweden-live/${case.disclosureId}-raw.json").writeText(mapper.writeValueAsString(raw))
            val verified = QuoteVerifier().verify(raw, reportInput, Market.SE)
            val report = Report(null, checkNotNull(company.id), Market.SE, entry.kind, entry.number, entry.sourceRef, entry.title, entry.publishedAt, entry.publishedAt, entry.detailUrl, ReportStatus.NEW)
            val input = assembler.assemble(identified, report, verified)
            val score = ScoreCalculator.score(input)
            "${identified.shortName ?: identified.name} ${entry.publishedAt} isin=${identified.isin} listing=${identified.listing}\n" +
                "  periods=${mapper.writeValueAsString(verified.extraction.periods)}\n" +
                "  outlook=${verified.extraction.outlook.tone} uncertain=${verified.uncertainFields}\n" +
                "  input=$input\n" +
                "  score=${score.total} components=${score.components} warnings=${score.warnings} signals=${score.signals}"
        }

        val (input, output) = usage.fold(0L to 0L) { (i, o), (ui, uo) -> (i + ui) to (o + uo) }
        val costUsd = BigDecimal(input).multiply(BigDecimal("0.0000001")) + BigDecimal(output).multiply(BigDecimal("0.0000005"))
        Files.createDirectories(Path.of("build"))
        Path.of("build/sweden-live.txt").writeText((lines + "input=$input output=$output costUsd=$costUsd").joinToString("\n"))
        assertTrue(lines.size == cases.size)
    }

    private fun searchUrl(case: Case) =
        "https://api.news.eu.nasdaq.com/news/query.action?type=json&showAttachments=true&showCnsSpecific=true&showCompany=true" +
            "&countResults=false&globalGroup=exchangeNotice&globalName=NordicAllMarkets&displayLanguage=en&timeZone=CET" +
            "&dateMask=yyyy-MM-dd+HH%3Amm%3Ass&limit=20&start=0&dir=DESC&market=${case.market.encoded()}&freeText=${case.freeText.encoded()}"

    private fun String.encoded() = URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/se/$path")).readText()
}
