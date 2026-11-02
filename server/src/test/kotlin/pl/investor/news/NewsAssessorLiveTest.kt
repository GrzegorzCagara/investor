package pl.investor.news

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import pl.investor.extraction.AiBudget
import pl.investor.extraction.QuoteVerifier
import pl.investor.report.Market
import pl.investor.source.content.ReportContent
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "RECORD_AI", matches = "1")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class NewsAssessorLiveTest {

    private class Case(val name: String, val input: NewsInput)

    private val cases = listOf(
        Case(
            "eurohold-frankfurt",
            NewsInput(
                "EUROHOLD BULGARIA AD", Market.GPW,
                "Eurohold Bulgaria launched a procedure to list its shares on the Frankfurt Stock Exchange",
                content(
                    "Eurohold Bulgaria AD informs that the Management Board has launched a procedure for the admission of the company's shares " +
                        "to trading on the regulated market of the Frankfurt Stock Exchange. The listing is intended to broaden the investor base " +
                        "and increase the liquidity of the shares. The company will inform about the next steps of the procedure.",
                ),
                null,
            ),
        ),
        Case(
            "odlewnie-pgz",
            NewsInput(
                "ODLEWNIE POLSKIE SA", Market.GPW,
                "Zawarcie umowy z Polską Grupą Zbrojeniową S.A.",
                content(
                    "Zarząd Odlewni Polskie S.A. informuje, że w dniu 23 lutego 2026 r. Spółka zawarła z Polską Grupą Zbrojeniową S.A. umowę " +
                        "na dostawy odlewów dla przemysłu obronnego. Umowa została zawarta na czas określony do 31 grudnia 2028 r.",
                ),
                BigDecimal("190000"),
            ),
        ),
        Case(
            "credit-annex",
            NewsInput(
                "RELPOL SA", Market.GPW,
                "Zawarcie kolejnego aneksu do umowy kredytu w rachunku bieżącym z instytucją bankową",
                content(
                    "Zarząd Spółki informuje, że w dniu dzisiejszym zawarł z bankiem aneks do umowy kredytu w rachunku bieżącym, " +
                        "na mocy którego okres kredytowania został przedłużony o kolejne 12 miesięcy. Pozostałe warunki umowy nie uległy istotnym zmianom.",
                ),
                BigDecimal("400000"),
            ),
        ),
    )

    @Test
    fun `assesses three real announcements`() {
        val budget = mockk<AiBudget>(relaxed = true)
        every { budget.canSpend(any()) } returns true
        val client = AnthropicOkHttpClient.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).build()
        val assessor = NewsAssessor(budget, client, QuoteVerifier())

        val results = cases.associate { it.name to assessor.assess(it.input) }

        Files.createDirectories(Path.of("build"))
        Path.of("build/news-live.txt").writeText(results.entries.joinToString("\n") { (name, it) -> "$name: ${it.category} ${it.direction} impact=${it.impact} score=${it.score} value=${it.contractValue} ${it.contractCurrency} share=${it.revenueSharePct} reason=${it.reason}" })
        assertTrue(results.values.all { it.impact in 0.0..100.0 && it.reason.isNotBlank() })
        assertTrue(checkNotNull(results["credit-annex"]).score < 50, "${results["credit-annex"]}")
    }

    private fun content(text: String) = ReportContent(text, emptyList(), emptyList())
}
