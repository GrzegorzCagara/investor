package pl.investor.extraction

import org.junit.jupiter.api.Test
import pl.investor.history.PeriodKind
import pl.investor.report.Market
import pl.investor.source.nasdaq.NasdaqViewParser
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinnishAmountsTest {

    private fun release(disclosureId: String) = ExtractionPrompt.reportInput(
        NasdaqViewParser().parse(
            checkNotNull(javaClass.getResource("/fixtures/fi/view-$disclosureId.html")).readText(),
            "https://view.news.eu.nasdaq.com/view?id=$disclosureId",
        ),
    )

    private fun amount(value: String, text: String) = Amount(BigDecimal(value), text)

    private fun period(
        revenue: Amount? = null,
        revenuePrev: Amount? = null,
        profit: Amount? = null,
        profitPrev: Amount? = null,
        net: Amount? = null,
        orders: Amount? = null,
    ) = Extraction(
        ReportCategory.RESULTS,
        listOf(ExtractedPeriod(PeriodKind.QUARTER, LocalDate.parse("2026-09-30"), "EUR", revenue, revenuePrev, profit, profitPrev, net, null, orders, null)),
        Outlook(OutlookTone.NONE, null), false, null, false,
    )

    private val verifier = QuoteVerifier()

    private fun uncertain(extraction: Extraction, text: String) = verifier.verify(extraction, text, Market.FI).uncertainFields

    private fun supported(value: String, source: String, text: String) = verifier.isSupported(amount(value, source), text, Market.FI)

    @Test
    fun `Kempower English interim verifies EUR millions with the prior year in brackets, negatives and MEUR table values`() {
        val text = release("1474755")
        val correct = period(
            revenue = amount("66200", "EUR 66.2 million (EUR 73.7 million)"),
            revenuePrev = amount("73700", "EUR 66.2 million (EUR 73.7 million)"),
            profit = amount("-1700", "EUR -1.7 million (EUR 0.2 million)"),
            profitPrev = amount("200", "EUR -1.7 million (EUR 0.2 million)"),
            orders = amount("60800", "EUR 60.8 million"),
        )
        val swapped = period(revenue = amount("73700", "EUR 66.2 million (EUR 73.7 million)"), revenuePrev = amount("66200", "EUR 66.2 million (EUR 73.7 million)"))

        assertEquals(emptySet(), uncertain(correct, text))
        assertEquals(setOf("periods[0].revenue", "periods[0].revenuePrevYear"), uncertain(swapped, text))
        assertEquals(setOf("periods[0].revenue"), uncertain(period(revenue = amount("662000", "EUR 66.2 million")), text))
        assertTrue(supported("-1700", "EUR -1.7 million", text))
        assertTrue(supported("128300", "128.3", text))
        assertFalse(supported("1700", "EUR -1.7 million", text))
        assertTrue(supported("-3600", "-3.6", text))
        assertTrue(supported("-1900", "-1.9", text))
        assertFalse(supported("1900", "-1.9", text))
        assertEquals(
            emptySet(),
            uncertain(period(revenuePrev = amount("73700", "(EUR 73.7 million)"), profitPrev = amount("100", "0.1")), text),
        )
        assertEquals(setOf("periods[0].revenue"), uncertain(period(revenue = amount("73700", "(EUR 73.7 million)")), text))
    }

    @Test
    fun `Raisio English interim verifies the bracketed prior year between the amount and the unit`() {
        val text = release("1476137")

        assertEquals(
            emptySet(),
            uncertain(period(revenue = amount("57800", "EUR 57.8 (56.7) million"), revenuePrev = amount("56700", "EUR 57.8 (56.7) million")), text),
        )
        assertEquals(
            emptySet(),
            uncertain(period(revenue = amount("171300", "EUR 171.3 (169.0) million"), revenuePrev = amount("169000", "EUR 171.3 (169.0) million")), text),
        )
        assertFalse(supported("56700", "EUR 57.8 (56.7) million", text))
        assertEquals(
            emptySet(),
            uncertain(period(profit = amount("8200", "EBIT was EUR 8.2* (8.6) million"), profitPrev = amount("8600", "EBIT was EUR 8.2* (8.6) million")), text),
        )
    }

    @Test
    fun `Raisio Finnish interim verifies comma decimals with miljoonaa euroa`() {
        val text = release("1476133")

        assertEquals(
            emptySet(),
            uncertain(period(revenue = amount("57800", "57,8 (56,7) miljoonaa euroa"), revenuePrev = amount("56700", "57,8 (56,7) miljoonaa euroa")), text),
        )
        assertFalse(supported("578000", "57,8 (56,7) miljoonaa euroa", text))
    }

    @Test
    fun `Elecster Finnish-only interim verifies MEUR with a labelled prior-year bracket and a negative EBIT`() {
        val text = release("1475083")
        val quarter = period(
            revenue = amount("6800", "6,8 MEUR (7-9/2025: 7,3 MEUR)"),
            revenuePrev = amount("7300", "6,8 MEUR (7-9/2025: 7,3 MEUR)"),
            profit = amount("-200", "-0,2 MEUR"),
            profitPrev = amount("300", "-0,2 MEUR (0,3 MEUR)"),
        )

        assertEquals(emptySet(), uncertain(quarter, text))
        assertTrue(supported("22700", "22,7 MEUR (1-9/2025: 24,0 MEUR)", text))
        assertTrue(supported("-100", "-0,1 MEUR", text))
        assertFalse(supported("100", "-0,1 MEUR", text))
    }

    @Test
    fun `Raisio profit warning verifies bare values under the M euro table header`() {
        val text = release("1475515")

        assertTrue("M€" in text)
        assertEquals(emptySet(), uncertain(period(revenue = amount("57800", "57.8"), revenuePrev = amount("56700", "56.7")), text))
        assertEquals(emptySet(), uncertain(period(profit = amount("8400", "8.4"), profitPrev = amount("8600", "8.6")), text))
        assertTrue(supported("171300", "EUR 171.3 (169.0) million", text))
    }

    @Test
    fun `two-digit table values need a row label before them and no percentage after them`() {
        val text = "MEUR Q3/2026 Q3/2025 Comparable EBIT 8.4 8.6 Comparable EBIT margin 8.2 per cent and 7,5 prosenttia, liikevoittomarginaali 8,2 % " +
            "Approved. 12 3.3 4.4"

        assertTrue(supported("8400", "8.4", text))
        assertTrue(supported("8600", "8.6", text))
        assertFalse(supported("8200", "8.2", text))
        assertFalse(supported("7500", "7,5", text))
        assertFalse(supported("8200", "8,2", text))
        assertFalse(supported("4400", "4.4", text))
    }

    @Test
    fun `Finnish thousands and millions spellings are read by their unit`() {
        val text = "Liikevaihto oli 1 234,5 milj. euroa ja liikevoitto 512 TEUR. Tilauskanta 2 345 t€. Liiketulos -0,1 MEUR. " +
            "Saadut tilaukset 12,3 miljoonaa euroa ja tilikauden tulos 456 tuhatta euroa."
        listOf(
            "1234500" to "1 234,5 milj. euroa", "512" to "512 TEUR", "2345" to "2 345 t€", "-100" to "-0,1 MEUR",
            "12300" to "12,3 miljoonaa euroa", "456" to "456 tuhatta euroa",
        ).forEach { (value, source) -> assertTrue(supported(value, source, text), source) }

        assertFalse(supported("1234", "1 234,5 milj. euroa", text))
        assertFalse(supported("512000", "512 TEUR", text))
    }
}
