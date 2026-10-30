package pl.investor.source.biznesradar

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QuarterlyResultsParserTest {

    private val parser = QuarterlyResultsParser()

    private fun fixture(name: String) =
        javaClass.getResource("/fixtures/biznesradar/$name")!!.readText()

    @Test
    fun `ASBISC quarter 2026 Q2 was published on 2026-08-06`() {
        val quarter = parser.parse(fixture("ASBISC-pl-q.html")).single { it.quarterEnd == LocalDate.of(2026, 6, 30) }

        assertEquals(LocalDate.of(2026, 8, 6), quarter.publishedOn)
        assertNotNull(quarter.revenue)
        assertNotNull(quarter.netProfit)
        assertNotNull(quarter.operatingProfit)
    }

    @Test
    fun `every quarterly fixture parses with ascending quarter ends`() {
        listOf("ASBISC-pl-q.html", "XTB-pl-q.html", "CRJ-pl-q.html").forEach { name ->
            val quarters = parser.parse(fixture(name))
            assertEquals(quarters.map { it.quarterEnd }.sorted(), quarters.map { it.quarterEnd }, name)
            assertEquals(true, quarters.isNotEmpty(), name)
        }
    }

    @Test
    fun `values are aligned by column and the extra trailing cell is ignored`() {
        val html = """
            <table class="report-table">
              <tr><th class="thname"></th><th class="thq h">2026/Q1<span>(mar 26)</span></th><th class="thq h newest">2026/Q2<span>(cze 26)</span></th><th class="thchart"></th></tr>
              <tr data-field="PrimaryReport"><td class="f">Data publikacji</td><td class="h"><span class="value"><span>2026-05-10</span></span></td><td class="h newest"><span class="value"><span>2026-08-06</span></span></td></tr>
              <tr data-field="IncomeRevenues"><td class="f">Przychody ze sprzedaży</td><td class="h"></td><td class="h newest"><span class="value"><span class="pv"><span>1 052</span></span></span><span class="sectorv">~branża <span class="pv"><span>9,46</span></span></span></td><td class="ch"></td></tr>
            </table>
        """.trimIndent()

        val (first, second) = parser.parse(html)

        assertNull(first.revenue)
        assertEquals(LocalDate.of(2026, 3, 31), first.quarterEnd)
        assertEquals(java.math.BigDecimal("1052"), second.revenue)
        assertEquals(LocalDate.of(2026, 8, 6), second.publishedOn)
        assertNull(second.netProfit)
    }
}
