package pl.investor.news

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.history.DailyQuote
import pl.investor.history.QuarterResult
import pl.investor.history.QuarterResultRepository
import pl.investor.history.QuoteRepository
import pl.investor.history.ValuationPoint
import pl.investor.history.ValuationRepository
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.scoring.Warning
import pl.investor.source.content.ReportContent
import pl.investor.support.PostgresTestBase
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsContextAssemblerTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var quarterResults: QuarterResultRepository
    @Autowired lateinit var valuations: ValuationRepository
    @Autowired lateinit var quotes: QuoteRepository
    @Autowired lateinit var assembler: NewsContextAssembler

    private val publishedAt = Instant.parse("2026-11-03T10:00:00Z")
    private val content = ReportContent("Treść komunikatu.", emptyList(), emptyList())
    private lateinit var company: Company

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company, daily_quote RESTART IDENTITY CASCADE").update()
        company = companies.save(Company(null, "ATREM", ISIN, Market.GPW, "ATR", "ATREM", false))
    }

    private fun saveTrailingYear(revenue: Long, netProfit: Long) =
        listOf("2026-06-30", "2026-03-31", "2025-12-31", "2025-09-30").map(LocalDate::parse).forEach { end ->
            quarterResults.save(QuarterResult(company.id!!, end, BigDecimal(revenue), null, BigDecimal(netProfit), end.plusDays(40)))
        }

    private fun saveClose(date: String, close: String, isin: String = ISIN) =
        quotes.save(DailyQuote(isin, LocalDate.parse(date), BigDecimal(close), 1000, BigDecimal(1000)))

    private fun report(sourceRef: String, at: Instant, title: String?, status: ReportStatus) = Report(
        id = null, companyId = company.id!!, market = Market.GPW, kind = ReportKind.CURRENT, number = sourceRef,
        sourceRef = sourceRef, title = title, publishedAt = at, detectedAt = at, detailUrl = "https://www.gpw.pl/$sourceRef",
        status = status,
    )

    private fun current() = report("100", publishedAt, "Zawarcie umowy z PSE S.A.", ReportStatus.NEW)

    @Test
    fun `input carries trailing revenue, net result and market cap from shares and the previous close`() {
        saveTrailingYear(revenue = 75_000, netProfit = 5_500)
        valuations.save(ValuationPoint(company.id!!, LocalDate.parse("2026-06-30"), BigDecimal("40"), 9_200_000, BigDecimal("20"), BigDecimal("1.5")))
        saveClose("2026-11-02", "50.90")
        saveClose("2026-11-03", "99.00")

        val input = assembler.input(company, current(), "Zawarcie umowy z PSE S.A.", content)

        assertEquals(0, BigDecimal("300000").compareTo(input.trailingRevenue))
        assertEquals(0, BigDecimal("22000").compareTo(input.trailingNetProfit))
        assertEquals(0, BigDecimal("468280").compareTo(input.marketCap))
    }

    @Test
    fun `market cap without a share count comes from the price to sales ratio`() {
        saveTrailingYear(revenue = 1_000, netProfit = -9_000)
        valuations.save(ValuationPoint(company.id!!, LocalDate.parse("2026-06-30"), BigDecimal("20"), null, null, BigDecimal("3")))
        saveClose("2026-11-02", "10")

        val input = assembler.input(company, current(), "Licensing agreement", content)

        assertEquals(0, BigDecimal("6000").compareTo(input.marketCap))
        assertEquals(0, BigDecimal("-36000").compareTo(input.trailingNetProfit))
    }

    @Test
    fun `market cap is unknown without a recent close, a valuation or for a foreign quote currency`() {
        saveTrailingYear(revenue = 1_000, netProfit = 10)
        valuations.save(ValuationPoint(company.id!!, LocalDate.parse("2026-06-30"), BigDecimal("20"), 1_000_000, null, null))
        saveClose("2026-10-01", "10")
        assertNull(assembler.input(company, current(), "Umowa", content).marketCap)

        saveClose("2026-11-02", "10")
        val euro = companies.save(company.copy(id = null, name = "EURO", isin = "PLEUR0000010", quoteCurrency = "EUR"))
        saveClose("2026-11-02", "10", isin = "PLEUR0000010")
        assertNull(assembler.input(euro, current(), "Umowa", content).marketCap)
    }

    @Test
    fun `earlier reports are the relevant titles of the last 90 days, newest first`() {
        val day = Duration.ofDays(1)
        reports.saveIfNew(report("1", publishedAt.minus(day.multipliedBy(74)), "Wybór oferty Spółki przez PSE S.A.", ReportStatus.SCORED))
        reports.saveIfNew(report("2", publishedAt.minus(day.multipliedBy(10)), "Raport kwartalny za III kwartał", ReportStatus.SCORED))
        reports.saveIfNew(report("3", publishedAt.minus(day.multipliedBy(5)), "Transakcje osób pełniących obowiązki zarządcze", ReportStatus.IRRELEVANT))
        reports.saveIfNew(report("4", publishedAt.minus(day.multipliedBy(91)), "Stary komunikat", ReportStatus.SCORED))
        reports.saveIfNew(report("5", publishedAt.plus(day), "Późniejszy komunikat", ReportStatus.SCORED))

        val input = assembler.input(company, current(), "Zawarcie umowy z PSE S.A.", content)

        assertEquals(
            listOf(
                EarlierReport(LocalDate.parse("2026-10-24"), "Raport kwartalny za III kwartał"),
                EarlierReport(LocalDate.parse("2026-08-21"), "Wybór oferty Spółki przez PSE S.A."),
            ),
            input.earlierReports,
        )
    }

    @Test
    fun `penny stock warning comes from a recent previous close below 1`() {
        saveClose("2026-11-02", "0.10")
        assertEquals(setOf(Warning.PENNY_STOCK), assembler.warnings(company, current()))

        saveClose("2026-11-02", "1.00")
        assertTrue(assembler.warnings(company, current()).isEmpty())
    }

    private companion object {
        const val ISIN = "PLATREM00017"
    }
}
