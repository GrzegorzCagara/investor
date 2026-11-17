package pl.investor.context

import org.junit.jupiter.api.Test
import pl.investor.extraction.Amount
import pl.investor.extraction.ExtractedPeriod
import pl.investor.extraction.RevenueLine
import pl.investor.history.PeriodKind
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RevenueMixTest {

    private fun amount(value: Int) = Amount(BigDecimal(value), value.toString())

    private fun line(name: String, revenue: Int, revenuePrevYear: Int?) = RevenueLine(name, amount(revenue), revenuePrevYear?.let(::amount))

    private fun quarter(revenue: Int, revenuePrevYear: Int, vararg lines: RevenueLine) = ExtractedPeriod(
        kind = PeriodKind.QUARTER,
        periodEnd = LocalDate.parse("2026-09-30"),
        currency = "PLN",
        revenue = amount(revenue),
        revenuePrevYear = amount(revenuePrevYear),
        operatingProfit = null,
        operatingProfitPrevYear = null,
        netProfit = null,
        netProfitPrevYear = null,
        revenueLines = lines.toList(),
    )

    private val goods = line("Sprzedaż towarów", 14407, 7547)
    private val products = line("Sprzedaż produktów", 9125, 7658)

    @Test
    fun `Yanosik growth driven by goods is concentrated in one line`() {
        val growth = assertNotNull(quarter(23532, 15205, goods, products).concentratedRevenueGrowth())

        assertEquals("Sprzedaż towarów", growth.line.name)
        assertEquals(82.4, growth.shareOfGrowthPct, 0.1)
        assertEquals(19.2, assertNotNull(growth.restYoyPct), 0.1)
    }

    @Test
    fun `growth of the line that already made most of the revenue is not concentrated`() {
        assertNull(quarter(150, 100, line("Usługi", 135, 90), line("Towary", 15, 10)).concentratedRevenueGrowth())
    }

    @Test
    fun `growth spread over the lines is not concentrated`() {
        assertNull(quarter(200, 100, line("Kraj", 100, 50), line("Eksport", 100, 50)).concentratedRevenueGrowth())
    }

    @Test
    fun `falling revenue has no growth to attribute`() {
        assertNull(quarter(14000, 15205, line("Sprzedaż towarów", 7000, 7547), line("Sprzedaż produktów", 7000, 7658)).concentratedRevenueGrowth())
    }

    @Test
    fun `one line with a prior year is not a structure`() {
        assertNull(quarter(23532, 15205, goods, line("Sprzedaż produktów", 9125, null)).concentratedRevenueGrowth())
    }

    @Test
    fun `line larger than the whole revenue is ignored as a total`() {
        val growth = quarter(23532, 15205, line("Razem", 30000, 20000), goods, products).concentratedRevenueGrowth()

        assertEquals("Sprzedaż towarów", growth?.line?.name)
    }
}
