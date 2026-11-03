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
import java.time.LocalDate
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
        Case(
            "atrem-pse-signing",
            NewsInput(
                "ATREM SA", Market.GPW,
                "Zawarcie umowy z PSE S.A. na rozbudowę stacji elektroenergetycznej Mikułowa",
                content(
                    "W nawiązaniu do raportu bieżącego nr 21/2026 z dnia 21 sierpnia 2026 r. Zarząd ATREM S.A. informuje, że w dniu 3 listopada 2026 r. " +
                        "Spółka zawarła z Polskimi Sieciami Elektroenergetycznymi S.A. umowę na rozbudowę stacji 400/110 kV Mikułowa. " +
                        "Łączne wynagrodzenie wynosi 157,8 mln zł brutto, w tym zamówienie podstawowe 98,1 mln zł brutto oraz prawo opcji 59,7 mln zł brutto. " +
                        "Termin realizacji wynosi 38 miesięcy. Łączna wysokość kar umownych nie może przekroczyć 20% wynagrodzenia.",
                ),
                BigDecimal("290000"),
                trailingNetProfit = BigDecimal("22000"),
                marketCap = BigDecimal("468000"),
                earlierReports = listOf(EarlierReport(LocalDate.parse("2026-08-21"), "Wybór oferty Spółki w postępowaniu prowadzonym przez PSE S.A.")),
            ),
        ),
        Case(
            "atrem-pse-selection",
            NewsInput(
                "ATREM SA", Market.GPW,
                "Wybór oferty Spółki w postępowaniu prowadzonym przez PSE S.A.",
                content(
                    "Zarząd ATREM S.A. informuje, że w dniu 21 sierpnia 2026 r. Polskie Sieci Elektroenergetyczne S.A. wybrały ofertę Spółki jako najkorzystniejszą " +
                        "w postępowaniu na rozbudowę stacji 400/110 kV Mikułowa. Wartość oferty wynosi 157,8 mln zł brutto, w tym zamówienie podstawowe " +
                        "98,1 mln zł brutto oraz prawo opcji 59,7 mln zł brutto. Termin realizacji wynosi 38 miesięcy od zawarcia umowy.",
                ),
                BigDecimal("270000"),
                trailingNetProfit = BigDecimal("21000"),
                marketCap = BigDecimal("430000"),
            ),
        ),
        Case(
            "alligator-license",
            NewsInput(
                "Alligator Bioscience AB", Market.SE,
                "Alligator Bioscience enters into license agreement with Biossil for mitazalimab",
                content(
                    "Alligator Bioscience AB announced today that it has entered into a license agreement with Biossil Inc. for mitazalimab. " +
                        "Under the agreement Alligator will receive an upfront payment of USD 0.75 million and is eligible to receive development, " +
                        "regulatory and commercial milestone payments of up to USD 108 million, as well as tiered royalties on net sales. " +
                        "Alligator previously decided not to start the phase 3 study of mitazalimab in pancreatic cancer on its own. " +
                        "After closing, Alligator will no longer carry the costs of the program and its patents.",
                ),
                BigDecimal("8000"),
                trailingNetProfit = BigDecimal("-180000"),
                marketCap = BigDecimal("120000"),
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
        Path.of("build/news-live.txt").writeText(results.entries.joinToString("\n") { (name, it) -> "$name: ${it.category} ${it.direction} impact=${it.impact} score=${it.score} known=${it.alreadyAnnounced} value=${it.contractValue} ${it.contractCurrency} share=${it.revenueSharePct} reason=${it.reason}" })
        assertTrue(results.values.all { it.impact in 0.0..100.0 && it.reason.isNotBlank() })
        assertTrue(checkNotNull(results["credit-annex"]).score < 50, "${results["credit-annex"]}")
        assertTrue(checkNotNull(results["atrem-pse-signing"]).score <= 30, "${results["atrem-pse-signing"]}")
        assertTrue(checkNotNull(results["alligator-license"]).score < 70, "${results["alligator-license"]}")
    }

    private fun content(text: String) = ReportContent(text, emptyList(), emptyList())
}
