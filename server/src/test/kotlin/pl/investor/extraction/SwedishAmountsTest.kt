package pl.investor.extraction

import org.junit.jupiter.api.Test
import pl.investor.history.PeriodKind
import pl.investor.report.Market
import pl.investor.source.biznesradar.PolishNumbers
import pl.investor.source.nasdaq.NasdaqViewParser
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwedishAmountsTest {

    private val content = NasdaqViewParser().parse(
        checkNotNull(javaClass.getResource("/fixtures/se/view-opter-q3-2026.html")).readText(),
        "https://view.news.eu.nasdaq.com/view?id=b3e1bac58183058432778b19c49e321a1",
    )

    private fun thousands(text: String) = PolishNumbers.parseAmountInThousands(text)

    @Test
    fun `Swedish units are converted to thousands`() {
        assertEquals(0, BigDecimal("27700").compareTo(thousands("27,7 MSEK")))
        assertEquals(0, BigDecimal("27700").compareTo(thousands("27,7 Mkr")))
        assertEquals(0, BigDecimal("1234.5").compareTo(thousands("1 234,5 tkr")))
        assertEquals(0, BigDecimal("12345").compareTo(thousands("12 345 KSEK")))
        assertEquals(0, BigDecimal("2100000").compareTo(thousands("2,1 mdkr")))
        assertEquals(0, BigDecimal("113000").compareTo(thousands("113 miljoner kronor")))
    }

    @Test
    fun `amounts of a Swedish report are verified against the release text`() {
        val period = ExtractedPeriod(
            kind = PeriodKind.QUARTER,
            periodEnd = LocalDate.parse("2026-09-30"),
            currency = "SEK",
            revenue = Amount(BigDecimal("27700"), "27,7 MSEK"),
            revenuePrevYear = Amount(BigDecimal("24200"), "24,2"),
            operatingProfit = Amount(BigDecimal("10600"), "10,6 MSEK"),
            operatingProfitPrevYear = Amount(BigDecimal("8200"), "10,6 MSEK (8,2)"),
            netProfit = Amount(BigDecimal("8500"), "8,5 MSEK"),
            netProfitPrevYear = Amount(BigDecimal("6600"), "8,5 MSEK (6,6)"),
            orderIntake = Amount(BigDecimal("50000"), "50,0 MSEK"),
            orderIntakePrevYear = null,
        )
        val extraction = Extraction(ReportCategory.RESULTS, listOf(period), Outlook(OutlookTone.NONE, null), false, null, false)

        val verified = QuoteVerifier().verify(extraction, ExtractionPrompt.reportInput(content), Market.SE)

        val checked = verified.extraction.periods.single()
        assertEquals(0, BigDecimal("27700").compareTo(checked.revenue?.valueInThousands))
        assertEquals(0, BigDecimal("24200").compareTo(checked.revenuePrevYear?.valueInThousands))
        assertEquals(0, BigDecimal("10600").compareTo(checked.operatingProfit?.valueInThousands))
        assertEquals(0, BigDecimal("8500").compareTo(checked.netProfit?.valueInThousands))
        assertEquals(0, BigDecimal("8200").compareTo(checked.operatingProfitPrevYear?.valueInThousands))
        assertEquals(0, BigDecimal("6600").compareTo(checked.netProfitPrevYear?.valueInThousands))
        assertEquals(null, checked.orderIntake)
        assertEquals(setOf("periods[0].orderIntake"), verified.uncertainFields)
    }

    private fun quarter(
        operatingProfit: Amount? = null,
        operatingProfitPrevYear: Amount? = null,
        revenue: Amount? = null,
        currency: String = "SEK",
    ) = Extraction(
        ReportCategory.RESULTS,
        listOf(
            ExtractedPeriod(
                PeriodKind.QUARTER, LocalDate.parse("2026-09-30"), currency, revenue, null,
                operatingProfit, operatingProfitPrevYear, null, null,
            ),
        ),
        Outlook(OutlookTone.NONE, null), false, null, false,
    )

    @Test
    fun `comparative in parentheses verifies the current field only against the leading amount and the prior year only against the bracket`() {
        val text = ExtractionPrompt.reportInput(content)
        val swapped = quarter(
            operatingProfit = Amount(BigDecimal("8200"), "10,6 MSEK (8,2)"),
            operatingProfitPrevYear = Amount(BigDecimal("10600"), "10,6 MSEK (8,2)"),
        )
        val correct = quarter(
            operatingProfit = Amount(BigDecimal("10600"), "10,6 MSEK (8,2)"),
            operatingProfitPrevYear = Amount(BigDecimal("8200"), "10,6 MSEK (8,2)"),
        )

        assertEquals(
            setOf("periods[0].operatingProfit", "periods[0].operatingProfitPrevYear"),
            QuoteVerifier().verify(swapped, text, Market.SE).uncertainFields,
        )
        assertEquals(emptySet(), QuoteVerifier().verify(correct, text, Market.SE).uncertainFields)
    }

    @Test
    fun `English amounts of Swedish releases with m suffix, decimal points and the unit after the comparative are verified`() {
        val text = "Net sales increased by 59 percent to SEK 215m (135). Operating profit amounted to SEK 60m (44). " +
            "Net sales amounted to SEK 357.0 (355.2) million. Operating profit was SEK 52.6 million (-10.6). Order intake SEK 1.2bn (0.9)."
        fun period(revenue: Amount, revenuePrev: Amount, profit: Amount, profitPrev: Amount) = Extraction(
            ReportCategory.RESULTS,
            listOf(ExtractedPeriod(PeriodKind.QUARTER, LocalDate.parse("2026-09-30"), "SEK", revenue, revenuePrev, profit, profitPrev, null, null)),
            Outlook(OutlookTone.NONE, null), false, null, false,
        )
        val mips = period(
            Amount(BigDecimal("215000"), "SEK 215m (135)"), Amount(BigDecimal("135000"), "SEK 215m (135)"),
            Amount(BigDecimal("60000"), "Operating profit amounted to SEK 60m (44)"), Amount(BigDecimal("44000"), "Operating profit amounted to SEK 60m (44)"),
        )
        val prevas = period(
            Amount(BigDecimal("357000"), "SEK 357.0 (355.2) million"), Amount(BigDecimal("355200"), "SEK 357.0 (355.2) million"),
            Amount(BigDecimal("52600"), "SEK 52.6 million (-10.6)"), Amount(BigDecimal("-10600"), "SEK 52.6 million (-10.6)"),
        )
        val swapped = period(
            Amount(BigDecimal("135000"), "SEK 215m (135)"), Amount(BigDecimal("215000"), "SEK 215m (135)"),
            Amount(BigDecimal("1200000"), "SEK 1.2bn (0.9)"), Amount(BigDecimal("900000"), "SEK 1.2bn (0.9)"),
        )

        assertEquals(emptySet(), QuoteVerifier().verify(mips, text, Market.SE).uncertainFields)
        assertEquals(emptySet(), QuoteVerifier().verify(prevas, text, Market.SE).uncertainFields)
        assertEquals(setOf("periods[0].revenue", "periods[0].revenuePrevYear"), QuoteVerifier().verify(swapped, text, Market.SE).uncertainFields)
        assertEquals(4, QuoteVerifier().verify(mips, text, Market.GPW).uncertainFields.size)
    }

    @Test
    fun `English thousands commas in Swedish amounts are read both ways and swaps stay rejected`() {
        val text = "Net sales were SEK 10,345m (9,876). Operating profit amounted to SEK 1,234.5 million (1,100.2)."
        fun quarter(revenue: Amount, revenuePrev: Amount, profit: Amount, profitPrev: Amount) = Extraction(
            ReportCategory.RESULTS,
            listOf(ExtractedPeriod(PeriodKind.QUARTER, LocalDate.parse("2026-09-30"), "SEK", revenue, revenuePrev, profit, profitPrev, null, null)),
            Outlook(OutlookTone.NONE, null), false, null, false,
        )
        val correct = quarter(
            Amount(BigDecimal("10345000"), "SEK 10,345m (9,876)"), Amount(BigDecimal("9876000"), "SEK 10,345m (9,876)"),
            Amount(BigDecimal("1234500"), "SEK 1,234.5 million (1,100.2)"), Amount(BigDecimal("1100200"), "SEK 1,234.5 million (1,100.2)"),
        )
        val swapped = quarter(
            Amount(BigDecimal("9876000"), "SEK 10,345m (9,876)"), Amount(BigDecimal("10345000"), "SEK 10,345m (9,876)"),
            Amount(BigDecimal("1100200"), "SEK 1,234.5 million (1,100.2)"), Amount(BigDecimal("1234500"), "SEK 1,234.5 million (1,100.2)"),
        )

        assertEquals(emptySet(), QuoteVerifier().verify(correct, text, Market.SE).uncertainFields)
        assertEquals(4, QuoteVerifier().verify(swapped, text, Market.SE).uncertainFields.size)
    }

    @Test
    fun `Swedish unit declarations do not loosen verification of GPW reports`() {
        val text = "Przychody ze sprzedaży wyniosły 1 234,5 w trzecim kwartale. Dane porównawcze w TEUR podano w nocie 4."
        val millions = quarter(revenue = Amount(BigDecimal("1234500"), "1 234,5"), currency = "EUR")

        assertEquals(setOf("periods[0].revenue"), QuoteVerifier().verify(millions, text, Market.GPW).uncertainFields)
        assertEquals(setOf("periods[0].revenue"), QuoteVerifier().verify(millions, text).uncertainFields)
        assertEquals(emptySet(), QuoteVerifier().verify(millions, text, Market.SE).uncertainFields)
    }

    @Test
    fun `GPW keeps rejecting the comparative form`() {
        val text = "Zysk operacyjny wyniósł 10,6 mln zł (8,2) w trzecim kwartale."
        val prior = quarter(operatingProfitPrevYear = Amount(BigDecimal("8200"), "10,6 mln zł (8,2)"))

        assertEquals(setOf("periods[0].operatingProfitPrevYear"), QuoteVerifier().verify(prior, text, Market.GPW).uncertainFields)
        assertEquals(emptySet(), QuoteVerifier().verify(prior, text, Market.SE).uncertainFields)
    }

    @Test
    fun `prompt and schema ask for Swedish units and order intake`() {
        assertTrue("MSEK" in ExtractionPrompt.SYSTEM && "orderingång" in ExtractionPrompt.SYSTEM)
        val period = ((ExtractionSchema.SCHEMA["properties"] as Map<*, *>)["periods"] as Map<*, *>)["items"] as Map<*, *>
        val fields = (period["properties"] as Map<*, *>).keys
        assertTrue("orderIntake" in fields && "orderIntakePrevYear" in fields)
    }
}
