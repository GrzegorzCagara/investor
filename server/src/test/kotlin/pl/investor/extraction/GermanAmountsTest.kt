package pl.investor.extraction

import org.junit.jupiter.api.Test
import pl.investor.history.PeriodKind
import pl.investor.report.Market
import pl.investor.source.eqs.EqsReleaseParser
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GermanAmountsTest {

    private fun release(path: String) = ExtractionPrompt.reportInput(
        EqsReleaseParser().parse(checkNotNull(javaClass.getResource("/fixtures/de/$path")).readText(), "https://www.eqs-news.com/$path"),
    )

    private fun amount(value: String, text: String) = Amount(BigDecimal(value), text)

    private fun quarter(revenue: Amount?, revenuePrev: Amount?, profit: Amount? = null, profitPrev: Amount? = null, net: Amount? = null, kind: PeriodKind = PeriodKind.QUARTER) =
        Extraction(
            ReportCategory.RESULTS,
            listOf(ExtractedPeriod(kind, LocalDate.parse("2026-09-30"), "EUR", revenue, revenuePrev, profit, profitPrev, net, null)),
            Outlook(OutlookTone.NONE, null), false, null, false,
        )

    private fun uncertain(extraction: Extraction, text: String) = QuoteVerifier().verify(extraction, text, Market.DE).uncertainFields

    @Test
    fun `Masterflex German release verifies Mio Euro amounts and the prior year in the labelled bracket`() {
        val text = release("eqs-masterflex-q3-2026-de.html")
        val correct = quarter(
            revenue = amount("27900", "27,9 Mio. Euro (Q3/2025: 26,5 Mio. Euro)"),
            revenuePrev = amount("26500", "27,9 Mio. Euro (Q3/2025: 26,5 Mio. Euro)"),
        )
        val nineMonths = quarter(
            revenue = amount("82200", "82,2 Mio. Euro (9M/2025: 79,8 Mio. Euro)"),
            revenuePrev = amount("79800", "82,2 Mio. Euro (9M/2025: 79,8 Mio. Euro)"),
            profit = amount("12300", "12,3 Mio. Euro"),
            kind = PeriodKind.HALF_YEAR,
        )
        val swapped = quarter(
            revenue = amount("26500", "27,9 Mio. Euro (Q3/2025: 26,5 Mio. Euro)"),
            revenuePrev = amount("27900", "27,9 Mio. Euro (Q3/2025: 26,5 Mio. Euro)"),
        )

        assertEquals(emptySet(), uncertain(correct, text))
        assertEquals(emptySet(), uncertain(nineMonths, text))
        assertEquals(setOf("periods[0].revenue", "periods[0].revenuePrevYear"), uncertain(swapped, text))
    }

    @Test
    fun `German thousands dots, Vorjahr brackets and table values in TEUR are verified`() {
        val uniper = release("eqs-uniper-adhoc-2026-11-04-de.html")
        val ebitda = quarter(
            revenue = amount("1024000", "1.024 Mio. EUR (Vorjahreszeitraum: 641 Mio. EUR)"),
            revenuePrev = amount("641000", "1.024 Mio. EUR (Vorjahreszeitraum: 641 Mio. EUR)"),
        )
        assertEquals(emptySet(), uncertain(ebitda, uniper))

        val table = "Konzernkennzahlen in TEUR 9M 2026 9M 2025 Konzernumsatz 82.229 79.833 EBIT 12.125 11.963"
        assertEquals(emptySet(), uncertain(quarter(amount("82229", "82.229"), amount("79833", "79.833")), table))
    }

    @Test
    fun `German and English unit spellings are read as thousands of EUR`() {
        val text = "Umsatz 82,2 Mio. EUR. Ergebnis EUR 12,3 Mio. Cashflow € 374,5 Mio. Auftragseingang Mio € 51,0. " +
            "Kosten 950 TEUR und 120 T€. Bilanzsumme 1,4 Mrd. Euro, Eigenkapital 2,1 Milliarden Euro. " +
            "Sales of EUR 4.4 billion, EBIT of EUR 82.2 million, net income €12.5m (prior year: €10.1m), loss of -€756 million."
        val checks = listOf(
            "82200" to "82,2 Mio. EUR", "12300" to "EUR 12,3 Mio.", "374500" to "€ 374,5 Mio", "51000" to "Mio € 51,0",
            "950" to "950 TEUR", "120" to "120 T€", "1400000" to "1,4 Mrd. Euro", "2100000" to "2,1 Milliarden Euro",
            "4400000" to "EUR 4.4 billion", "82200" to "EUR 82.2 million", "12500" to "€12.5m", "-756000" to "-€756 million",
        )
        checks.forEach { (value, source) ->
            assertTrue(QuoteVerifier().isSupported(amount(value, source), text, Market.DE), source)
        }
        assertEquals(setOf("periods[0].revenuePrevYear"), uncertain(quarter(amount("12500", "€12.5m (prior year: €10.1m)"), amount("12500", "€12.5m (prior year: €10.1m)")), text))
    }

    @Test
    fun `Continental English release verifies billions and the bracket with a remark`() {
        val text = release("eqs-continental-q3-2026-en.html")
        val sales = quarter(
            revenue = amount("3500000", "€3.5 billion (Q3 2025: €3.9 billion, -1.5 percent organically)"),
            revenuePrev = amount("3900000", "€3.5 billion (Q3 2025: €3.9 billion, -1.5 percent organically)"),
            net = amount("349000", "€349 million"),
        )

        assertEquals(emptySet(), uncertain(sales, text))
    }

    @Test
    fun `the unit spelling decides whether a dot groups thousands or marks decimals`() {
        val uniper = release("eqs-uniper-adhoc-2026-11-04-de.html")
        val verifier = QuoteVerifier()
        assertTrue(verifier.isSupported(amount("1024000", "1.024 Mio. EUR"), uniper, Market.DE))
        assertFalse(verifier.isSupported(amount("1024", "1.024 Mio. EUR"), uniper, Market.DE))

        val text = "Umsatz 1,024 Mio. EUR. Sales of EUR 1.024 million and EUR 1,024 million, cost €1.5m."
        assertTrue(verifier.isSupported(amount("1024", "1,024 Mio. EUR"), text, Market.DE))
        assertFalse(verifier.isSupported(amount("1024000", "1,024 Mio. EUR"), text, Market.DE))
        assertTrue(verifier.isSupported(amount("1024", "EUR 1.024 million"), text, Market.DE))
        assertFalse(verifier.isSupported(amount("1024000", "EUR 1.024 million"), text, Market.DE))
        assertTrue(verifier.isSupported(amount("1024000", "EUR 1,024 million"), text, Market.DE))
        assertFalse(verifier.isSupported(amount("1024", "EUR 1,024 million"), text, Market.DE))
        assertTrue(verifier.isSupported(amount("1500", "€1.5m"), text, Market.DE))
        assertFalse(verifier.isSupported(amount("1500000", "€1.5m"), text, Market.DE))
    }

    @Test
    fun `amounts quoted alone are bound to their side of the prior-year bracket`() {
        val text = release("eqs-masterflex-q3-2026-de.html")
        val correct = quarter(amount("27900", "27,9 Mio. Euro"), amount("26500", "26,5 Mio. Euro"))
        val swapped = quarter(amount("26500", "26,5 Mio. Euro"), amount("27900", "27,9 Mio. Euro"))

        assertEquals(emptySet(), uncertain(correct, text))
        assertEquals(setOf("periods[0].revenue", "periods[0].revenuePrevYear"), uncertain(swapped, text))
        assertEquals(emptySet(), uncertain(quarter(null, amount("26500", "(Q3/2025: 26,5 Mio. Euro)")), text))
        assertEquals(setOf("periods[0].revenue"), uncertain(quarter(amount("26500", "(Q3/2025: 26,5 Mio. Euro)"), null), text))
    }

    @Test
    fun `wrong German amounts stay uncertain`() {
        val text = release("eqs-masterflex-q3-2026-de.html")

        assertEquals(setOf("periods[0].revenue"), uncertain(quarter(amount("82.2", "82,2 Mio. Euro"), null), text))
    }
}
