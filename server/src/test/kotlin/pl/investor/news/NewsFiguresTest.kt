package pl.investor.news

import org.junit.jupiter.api.Test
import pl.investor.history.QuarterResult
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewsFiguresTest {

    private fun quarter(end: String, revenue: String?) =
        QuarterResult(1, LocalDate.parse(end), revenue?.let(::BigDecimal), null, null, LocalDate.parse(end).plusMonths(2))

    private fun assessment(value: String?, currency: ContractCurrency?) =
        NewsAssessment(NewsCategory.CONTRACT, NewsDirection.POSITIVE, 70.0, "Powód.", value?.let(::BigDecimal), currency, null)

    @Test
    fun `trailing year revenue sums the latest four consecutive quarters`() {
        val quarters = listOf(
            quarter("2026-06-30", "400"),
            quarter("2025-09-30", "100"),
            quarter("2026-03-31", "300"),
            quarter("2025-12-31", "200"),
            quarter("2025-06-30", "999"),
        )

        assertEquals(0, BigDecimal("1000").compareTo(quarters.trailingYearRevenue()))
    }

    @Test
    fun `trailing year revenue is unknown with a gap, a missing value or fewer than four quarters`() {
        assertNull(listOf(quarter("2026-06-30", "1"), quarter("2026-03-31", "1"), quarter("2025-12-31", "1"), quarter("2025-06-30", "1")).trailingYearRevenue())
        assertNull(listOf(quarter("2026-06-30", "1"), quarter("2026-03-31", null), quarter("2025-12-31", "1"), quarter("2025-09-30", "1")).trailingYearRevenue())
        assertNull(listOf(quarter("2026-06-30", "1"), quarter("2026-03-31", "1"), quarter("2025-12-31", "1")).trailingYearRevenue())
    }

    @Test
    fun `contract value is formatted in Polish units`() {
        assertEquals("18,4 mln PLN", assessment("18400", ContractCurrency.PLN).contractValueText())
        assertEquals("830 tys. EUR", assessment("830", ContractCurrency.EUR).contractValueText())
        assertEquals("1,25 mld USD", assessment("1250000", ContractCurrency.USD).contractValueText())
        assertEquals("2,5 mln PLN", assessment("2450", ContractCurrency.PLN).contractValueText())
        assertEquals("999 tys. PLN", assessment("999", ContractCurrency.PLN).contractValueText())
        assertEquals("1 234,57 mld PLN", assessment("1234567890", ContractCurrency.PLN).contractValueText())
        assertEquals("18,4 mln w innej walucie", assessment("18400", ContractCurrency.OTHER).contractValueText())
        assertNull(assessment(null, null).contractValueText())
    }
}
