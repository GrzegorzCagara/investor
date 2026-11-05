package pl.investor.scoring

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.extraction.Amount
import pl.investor.extraction.ExtractedPeriod
import pl.investor.extraction.Extraction
import pl.investor.extraction.Outlook
import pl.investor.extraction.OutlookTone
import pl.investor.extraction.ReportCategory
import pl.investor.extraction.VerifiedExtraction
import pl.investor.history.PeriodKind
import pl.investor.history.QuarterResult
import pl.investor.history.QuarterResultRepository
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportKind
import pl.investor.report.ReportStatus
import pl.investor.support.PostgresTestBase
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DutchScoringInputTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var quarterResults: QuarterResultRepository
    @Autowired lateinit var assembler: ScoringInputAssembler

    private val publishedAt = Instant.parse("2026-10-14T05:00:00Z")
    private lateinit var asml: Company

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company, daily_quote RESTART IDENTITY CASCADE").update()
        asml = companies.save(Company(null, "ASML HOLDING N.V.", "NL0010273215", Market.NL, "ASML", null, false, avanzaId = "732279", quoteCurrency = "EUR"))
    }

    private fun stored(vararg quarters: Pair<String, String?>) = quarters.forEach { (end, revenueMln) ->
        quarterResults.save(
            QuarterResult(checkNotNull(asml.id), LocalDate.parse(end), revenueMln?.let { BigDecimal(it).movePointRight(3) }, null, null, LocalDate.parse(end).plusDays(15)),
        )
    }

    private fun asmlLikeHistory() = stored(
        "2025-06-30" to "7692", "2025-09-30" to "7516", "2025-12-31" to "9718", "2026-03-31" to "8767", "2026-06-30" to "9326",
        "2024-06-30" to "6243", "2024-09-30" to "7467", "2024-12-31" to "9263", "2025-03-31" to "7742",
    )

    private fun q3(revenueMln: String, revenuePrevYearMln: String? = null, currency: String = "EUR") = VerifiedExtraction(
        Extraction(
            ReportCategory.RESULTS,
            listOf(
                ExtractedPeriod(
                    kind = PeriodKind.QUARTER, periodEnd = LocalDate.parse("2026-09-30"), currency = currency,
                    revenue = Amount(BigDecimal(revenueMln).movePointRight(3), "€$revenueMln million"),
                    revenuePrevYear = revenuePrevYearMln?.let { Amount(BigDecimal(it).movePointRight(3), "€$it million") },
                    operatingProfit = null, operatingProfitPrevYear = null, netProfit = null, netProfitPrevYear = null,
                ),
            ),
            Outlook(OutlookTone.NONE, null), false, null, false,
        ),
        emptySet(),
    )

    private fun report() = Report(
        id = 1, companyId = checkNotNull(asml.id), market = Market.NL, kind = ReportKind.CURRENT, number = "Euronext",
        sourceRef = "12916206", title = "ASML reports Q3 2026", publishedAt = publishedAt, detectedAt = publishedAt,
        detailUrl = "https://live.euronext.com/en/ajax/node/company-press-release/12916206", status = ReportStatus.NEW,
    )

    private fun assemble(extraction: VerifiedExtraction) = assembler.assemble(asml, report(), extraction)

    @Test
    fun `release without prior-year revenue uses Avanza history when the previous quarter makes it plausible`() {
        asmlLikeHistory()

        val input = assemble(q3("11662"))

        assertTrue(abs(checkNotNull(input.revenueYoyPct) - 55.16) < 0.05, "${input.revenueYoyPct}")
        assertTrue(input.previousYoyPcts.isNotEmpty())
        assertTrue(checkNotNull(input.sur) > 0)
    }

    @Test
    fun `half-year reporter without a previous quarter in Avanza keeps the history out`() {
        stored("2025-06-30" to "2486", "2025-09-30" to "1200", "2025-12-31" to "2319", "2026-03-31" to "1250", "2026-06-30" to null)

        val input = assemble(q3("1300"))

        assertNull(input.revenueYoyPct)
        assertTrue(input.previousYoyPcts.isEmpty())
    }

    @Test
    fun `quarter revenue far from the previous quarter keeps the history out`() {
        asmlLikeHistory()

        assertNull(assemble(q3("25000")).revenueYoyPct)
        assertNull(assemble(q3("4000")).revenueYoyPct)
    }

    @Test
    fun `prior-year revenue that disagrees with Avanza still keeps the history out`() {
        asmlLikeHistory()

        val input = assemble(q3("11662", revenuePrevYearMln = "6000"))

        assertTrue(input.previousYoyPcts.isEmpty())
        assertEquals(94.37, checkNotNull(input.revenueYoyPct), 0.05)
    }

    @Test
    fun `release in another currency never uses the EUR history`() {
        asmlLikeHistory()

        val input = assemble(q3("13500", currency = "USD"))

        assertNull(input.revenueYoyPct)
        assertTrue(input.previousYoyPcts.isEmpty())
    }
}
