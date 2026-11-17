package pl.investor.pipeline

import org.junit.jupiter.api.Test
import pl.investor.extraction.Amount
import pl.investor.extraction.ExtractedPeriod
import pl.investor.extraction.Extraction
import pl.investor.extraction.Outlook
import pl.investor.extraction.OutlookTone
import pl.investor.extraction.QuoteVerifier
import pl.investor.extraction.ReportCategory
import pl.investor.extraction.RevenueLine
import pl.investor.history.PeriodKind
import pl.investor.history.QuarterResult
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuarterFromNineMonthsTest {

    private fun quarter(end: String, revenue: Int, operatingProfit: Int?, netProfit: Int?) =
        QuarterResult(1L, LocalDate.parse(end), BigDecimal(revenue), operatingProfit?.let(::BigDecimal), netProfit?.let(::BigDecimal), LocalDate.parse(end).plusDays(45))

    private val yanosikHistory = listOf(
        quarter("2026-06-30", 19650, 3900, 3100),
        quarter("2026-03-31", 16800, 3000, 2600),
        quarter("2025-12-31", 19208, 4500, 4090),
        quarter("2025-09-30", 15206, 2572, 2100),
        quarter("2025-06-30", 15023, 1100, 1000),
        quarter("2025-03-31", 13954, 1000, 900),
    )

    private fun amount(value: Int, text: String = value.toString()) = Amount(BigDecimal(value), text)

    private fun period(kind: PeriodKind, revenue: Int, revenuePrevYear: Int?, currency: String = "PLN") = ExtractedPeriod(
        kind = kind,
        periodEnd = LocalDate.parse("2026-09-30"),
        currency = currency,
        revenue = amount(revenue, "59 984"),
        revenuePrevYear = revenuePrevYear?.let { amount(it, "44 183") },
        operatingProfit = amount(12468, "12 468"),
        operatingProfitPrevYear = amount(4672, "4 672"),
        netProfit = amount(10192, "10 192"),
        netProfitPrevYear = amount(4000, "4 000"),
    )

    private fun extraction(vararg periods: ExtractedPeriod) =
        Extraction(ReportCategory.RESULTS, periods.toList(), Outlook(OutlookTone.NONE, null), false, null, false)

    private val yanosikNineMonths = period(PeriodKind.NINE_MONTHS, 59984, 44183)

    @Test
    fun `Yanosik preliminary nine months minus the first half gives the third quarter`() {
        val completed = extraction(yanosikNineMonths).withQuarterFromNineMonths(yanosikHistory, "PLN")

        val quarter = completed.periods.single { it.kind == PeriodKind.QUARTER }
        assertEquals(LocalDate.parse("2026-09-30"), quarter.periodEnd)
        assertEquals(BigDecimal(23534), quarter.revenue?.valueInThousands)
        assertEquals(BigDecimal(15206), quarter.revenuePrevYear?.valueInThousands)
        assertEquals(BigDecimal(5568), quarter.operatingProfit?.valueInThousands)
        assertEquals(BigDecimal(2572), quarter.operatingProfitPrevYear?.valueInThousands)
        assertEquals(BigDecimal(4492), quarter.netProfit?.valueInThousands)
        assertEquals(BigDecimal(2100), quarter.netProfitPrevYear?.valueInThousands)
        assertTrue(yanosikNineMonths in completed.periods)
    }

    @Test
    fun `revenue lines of the nine months are not copied to the quarter`() {
        val withLines = yanosikNineMonths.copy(revenueLines = listOf(RevenueLine("Sprzedaż towarów", amount(35000), amount(22000))))

        val quarter = extraction(withLines).withQuarterFromNineMonths(yanosikHistory, "PLN").periods.single { it.kind == PeriodKind.QUARTER }

        assertEquals(emptyList(), quarter.revenueLines)
    }

    @Test
    fun `quarter reported next to the nine months is kept as it is`() {
        val reported = period(PeriodKind.QUARTER, 23600, 15200)
        val original = extraction(reported, yanosikNineMonths)

        assertEquals(original, original.withQuarterFromNineMonths(yanosikHistory, "PLN"))
    }

    @Test
    fun `missing second quarter in the history leaves only the nine months`() {
        val original = extraction(yanosikNineMonths)

        assertEquals(original, original.withQuarterFromNineMonths(yanosikHistory.filterNot { it.quarterEnd == LocalDate.parse("2026-06-30") }, "PLN"))
    }

    @Test
    fun `nine months in a currency other than the history is not converted`() {
        val original = extraction(period(PeriodKind.NINE_MONTHS, 59984, 44183, currency = "EUR"))

        assertEquals(original, original.withQuarterFromNineMonths(yanosikHistory, "PLN"))
    }

    @Test
    fun `history that does not add up to the year-ago nine months is not trusted`() {
        val original = extraction(period(PeriodKind.NINE_MONTHS, 59984, 52000))

        assertEquals(original, original.withQuarterFromNineMonths(yanosikHistory, "PLN"))
    }

    @Test
    fun `quarter without revenue left after subtraction is not created`() {
        val original = extraction(period(PeriodKind.NINE_MONTHS, 36000, 44183))

        assertEquals(original, original.withQuarterFromNineMonths(yanosikHistory, "PLN"))
    }

    @Test
    fun `year-ago figures stay empty when the year-ago first half is missing`() {
        val history = yanosikHistory.filter { it.quarterEnd.year == 2026 }

        val quarter = extraction(yanosikNineMonths).withQuarterFromNineMonths(history, "PLN").periods.single { it.kind == PeriodKind.QUARTER }

        assertEquals(BigDecimal(23534), quarter.revenue?.valueInThousands)
        assertNull(quarter.revenuePrevYear)
        assertNull(quarter.operatingProfitPrevYear)
    }

    @Test
    fun `table figures of the Yanosik preliminary attachment pass the quote check`() {
        val attachment = """
            Wybrane dane finansowe (w tys. PLN)
            Rachunek zysków i strat
            Od 01.01.2026 Od 01.01.2025
            do 30.09.2026 do 30.09.2025
            Przychody netto ze sprzedaży 59 984 44 183
            Koszty działalności operacyjnej 47 549 39 345
            Zysk (strata) ze sprzedaży 12 435 4 838
            Zysk (strata) z działalności operacyjnej 12 468 4 672
            Zysk brutto 12 786 4 907
            Zysk netto 10 192 4 000
            EBITDA 13 225 5 215
        """.trimIndent()

        val verified = QuoteVerifier().verify(extraction(yanosikNineMonths), attachment)

        assertEquals(emptySet(), verified.uncertainFields)
    }
}
