package pl.investor.source.biznesradar

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValuationParserTest {

    private val parser = ValuationParser()

    private fun fixture(name: String) =
        javaClass.getResource("/fixtures/biznesradar/$name")!!.readText()

    @Test
    fun `ASBISC valuation 2026 Q2 has price 98 and PE 12`() {
        val quarter = parser.parse(fixture("ASBISC-valuation.html")).single { it.quarterEnd == LocalDate.of(2026, 6, 30) }

        assertEquals(BigDecimal("98.00"), quarter.price)
        assertEquals(BigDecimal("12.00"), quarter.priceToEarnings)
        assertNotNull(quarter.shares)
        assertNotNull(quarter.priceToRevenue)
    }

    @Test
    fun `placeholder zero price in ASBISC 2006 Q4 is null`() {
        val quarter = parser.parse(fixture("ASBISC-valuation.html")).single { it.quarterEnd == LocalDate.of(2006, 12, 31) }

        assertNull(quarter.price)
    }

    @Test
    fun `valuation columns stay aligned when early quarters are empty`() {
        listOf("ASBISC-valuation.html", "XTB-valuation.html", "CRJ-valuation.html").forEach { name ->
            val quarters = parser.parse(fixture(name))
            val firstWithEarnings = quarters.first { it.priceToEarnings != null }
            assertTrue(firstWithEarnings.price!! > BigDecimal.ZERO, name)
        }
    }

    @Test
    fun `empty first cell stays null instead of shifting values`() {
        val html = """
            <table class="report-table">
              <tr><th class="thname"></th><th class="thq h">2026/Q1</th><th class="thq h newest">2026/Q2</th></tr>
              <tr data-field="Quote"><td class="f">Kurs</td><td class="h"><span class="value"><span class="pv"><span>90,00</span></span></span></td><td class="h newest"><span class="value"><span class="pv"><span>98,00</span></span></span></td></tr>
              <tr data-field="CZ"><td class="f">Cena / Zysk</td><td class="h"></td><td class="h newest"><span class="value"><span class="pv"><span>12,00</span></span></span><span class="sectorv">~branża <span class="pv"><span>9,46</span></span></span></td><td class="ch"></td></tr>
            </table>
        """.trimIndent()

        val (first, second) = parser.parse(html)

        assertNull(first.priceToEarnings)
        assertEquals(BigDecimal("90.00"), first.price)
        assertEquals(BigDecimal("12.00"), second.priceToEarnings)
        assertEquals(BigDecimal("98.00"), second.price)
    }
}
