package pl.investor.scoring

import org.junit.jupiter.api.Test
import pl.investor.extraction.Amount
import pl.investor.extraction.ExtractedPeriod
import pl.investor.history.CompanyForecast
import pl.investor.history.PeriodKind
import pl.investor.history.QuarterResult
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForecastPaceTest {

    private fun quarter(end: String, revenue: Int, operatingProfit: Int? = null) =
        QuarterResult(1L, LocalDate.parse(end), BigDecimal(revenue), operatingProfit?.let(::BigDecimal), null, LocalDate.parse(end).plusDays(50))

    private val imsHistory = listOf(
        quarter("2026-06-30", 19502, 2600),
        quarter("2026-03-31", 15849, 2200),
        quarter("2025-12-31", 20161, 3900),
        quarter("2025-09-30", 15586, 2488),
        quarter("2025-06-30", 16288, 2300),
        quarter("2025-03-31", 14266, 2084),
        quarter("2024-12-31", 20356, 3500),
        quarter("2024-09-30", 16278, 2400),
    )

    private fun forecast(revenue: Int?, operatingProfit: Int? = null, yearEnd: String = "2026-12-31") = CompanyForecast(
        companyId = 1L,
        periodEnd = LocalDate.parse(yearEnd),
        currency = "PLN",
        revenue = revenue?.let(::BigDecimal),
        operatingProfit = operatingProfit?.let(::BigDecimal),
        netProfit = null,
        publishedAt = Instant.parse("2026-09-15T11:53:00Z"),
        reportId = 7L,
    )

    private fun period(kind: PeriodKind, end: String, revenue: Int, operatingProfit: Int? = null) = ExtractedPeriod(
        kind = kind,
        periodEnd = LocalDate.parse(end),
        currency = "PLN",
        revenue = Amount(BigDecimal(revenue), "$revenue"),
        revenuePrevYear = null,
        operatingProfit = operatingProfit?.let { Amount(BigDecimal(it), "$it") },
        operatingProfitPrevYear = null,
        netProfit = null,
        netProfitPrevYear = null,
    )

    private val imsThirdQuarter = period(PeriodKind.QUARTER, "2026-09-30", 22344, 4290)

    @Test
    fun `IMS after three quarters is on pace for 82,9 mln against the 80 mln forecast`() {
        val revenue = forecast(80000).paceComparisons(imsThirdQuarter, imsHistory).single()

        assertEquals(ConsensusMetric.REVENUE, revenue.metric)
        assertEquals(82905.0, revenue.actual.toDouble(), 1.0)
        assertEquals(BigDecimal(80000), revenue.consensus)
        assertEquals(3.6, revenue.differencePct(), 0.05)
        assertTrue(revenue.companyForecast)
    }

    @Test
    fun `operating profit forecast is compared at the same seasonal pace`() {
        val profit = forecast(80000, operatingProfit = 12000).paceComparisons(imsThirdQuarter, imsHistory).single { it.metric == ConsensusMetric.EBIT }

        val share = (2084.0 + 2300 + 2488) / (2084.0 + 2300 + 2488 + 3900)
        assertEquals((2200.0 + 2600 + 4290) / share, profit.actual.toDouble(), 1.0)
    }

    @Test
    fun `full year is compared with the forecast directly`() {
        val year = forecast(80000).paceComparisons(period(PeriodKind.YEAR, "2026-12-31", 78000), imsHistory).single()

        assertEquals(78000.0, year.actual.toDouble(), 0.001)
        assertEquals(-2.5, year.differencePct(), 0.001)
    }

    @Test
    fun `missing earlier quarter of the year gives no comparison`() {
        val history = imsHistory.filterNot { it.quarterEnd == LocalDate.parse("2026-03-31") }

        assertEquals(emptyList(), forecast(80000).paceComparisons(imsThirdQuarter, history))
    }

    @Test
    fun `without a complete earlier year there is no seasonal pace`() {
        val history = imsHistory.filter { it.quarterEnd.year == 2026 }

        assertEquals(emptyList(), forecast(80000).paceComparisons(imsThirdQuarter, history))
    }

    @Test
    fun `forecast for a year that does not contain the reported quarter is ignored`() {
        assertEquals(emptyList(), forecast(95000, yearEnd = "2027-12-31").paceComparisons(imsThirdQuarter, imsHistory))
    }

    @Test
    fun `forecast in another currency is ignored`() {
        val euro = imsThirdQuarter.copy(currency = "EUR")

        assertEquals(emptyList(), forecast(80000).paceComparisons(euro, imsHistory))
    }
}
