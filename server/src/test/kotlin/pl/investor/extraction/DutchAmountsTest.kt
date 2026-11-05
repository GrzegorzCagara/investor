package pl.investor.extraction

import io.mockk.mockk
import org.junit.jupiter.api.Test
import pl.investor.history.PeriodKind
import pl.investor.report.Market
import pl.investor.source.content.AttachmentTextExtractor
import pl.investor.source.euronext.EuronextReleaseParser
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DutchAmountsTest {

    private fun release(nid: String) = ExtractionPrompt.reportInput(
        EuronextReleaseParser().content(
            checkNotNull(javaClass.getResource("/fixtures/nl/node-$nid.html")).readText(),
            "https://live.euronext.com/en/ajax/node/company-press-release/$nid",
        ),
    )

    private val besiPdf = AttachmentTextExtractor(mockk()).relevantPages(
        checkNotNull(javaClass.getResource("/fixtures/nl/pdf-12917844-besi-q3-2026.pdf")).readBytes(),
    )

    private fun amount(value: String, text: String) = Amount(BigDecimal(value), text)

    private fun period(revenue: Amount? = null, revenuePrev: Amount? = null, profit: Amount? = null, net: Amount? = null, currency: String = "EUR") =
        Extraction(
            ReportCategory.RESULTS,
            listOf(ExtractedPeriod(PeriodKind.QUARTER, LocalDate.parse("2026-09-30"), currency, revenue, revenuePrev, profit, null, net, null)),
            Outlook(OutlookTone.NONE, null), false, null, false,
        )

    private val verifier = QuoteVerifier()

    private fun uncertain(extraction: Extraction, text: String) = verifier.verify(extraction, text, Market.NL).uncertainFields

    private fun supported(value: String, source: String, text: String) = verifier.isSupported(amount(value, source), text, Market.NL)

    @Test
    fun `IMCD English release verifies comma thousands and the labelled prior-year bracket`() {
        val text = release("12921454")
        val correct = period(
            revenue = amount("3965000", "EUR 3,965 million"),
            revenuePrev = amount("284000", "EUR 317 million (first nine months 2025: EUR 284 million)"),
            net = amount("211000", "Net result at EUR 211 million"),
        )
        val swapped = period(revenue = amount("284000", "EUR 317 million (first nine months 2025: EUR 284 million)"), revenuePrev = amount("317000", "EUR 317 million (first nine months 2025: EUR 284 million)"))

        assertEquals(emptySet(), uncertain(correct, text))
        assertEquals(setOf("periods[0].revenue", "periods[0].revenuePrevYear"), uncertain(swapped, text))
        assertEquals(setOf("periods[0].revenue"), uncertain(period(revenue = amount("3965", "EUR 3,965 million")), text))
    }

    @Test
    fun `BESI PDF verifies euro millions in the text and bare table values under the millions header`() {
        assertTrue("(€ millions" in besiPdf)
        val correct = period(
            revenue = amount("283400", "€ 283.4 million"),
            revenuePrev = amount("132700", "132.7"),
            net = amount("107200", "€ 107.2 Million"),
        )

        assertEquals(emptySet(), uncertain(correct, besiPdf))
        assertEquals(setOf("periods[0].revenue"), uncertain(period(revenue = amount("2834000", "€ 283.4 million")), besiPdf))
    }

    @Test
    fun `table values next to other columns are verified under an In EUR millions header`() {
        val vopak = release("12921130")
        val table = period(revenue = amount("349700", "349.7"), revenuePrev = amount("321600", "321.6"), net = amount("141700", "141.7"))
        val incomeStatement = "(in thousands of euros) Three Months Ended September 30, 2026 2025 Revenue 283,400 132,731 718,145 424,977 Cost of sales 98,630 50"

        assertEquals(emptySet(), uncertain(table, vopak))
        assertEquals(emptySet(), uncertain(period(revenue = amount("283400", "283,400"), revenuePrev = amount("132731", "132,731")), incomeStatement))
    }

    @Test
    fun `English billions, USD amounts and Dutch spellings are read by their unit`() {
        val text = "ASML reports €11.7 billion total net sales and €5.0 billion net income in Q3 2026. " +
            "Net income of \$2.0 billion, EBITDA of \$0.4bn and revenue of USD 1.2 billion. " +
            "Coöperatie VGZ zegt € 25 miljoen toe. De omzet bedroeg € 1.024 miljoen (vorig jaar: € 980 miljoen), de nettowinst EUR 1,2 miljard " +
            "en de kosten € 3,5 mln. Order intake of € 1,024 million."
        listOf(
            "11700000" to "€11.7 billion", "5000000" to "€5.0 billion", "2000000" to "\$2.0 billion", "400000" to "\$0.4bn",
            "1200000" to "USD 1.2 billion", "25000" to "€ 25 miljoen", "1024000" to "€ 1.024 miljoen", "1200000" to "EUR 1,2 miljard",
            "3500" to "€ 3,5 mln", "1024000" to "€ 1,024 million",
        ).forEach { (value, source) -> assertTrue(supported(value, source, text), source) }

        assertFalse(supported("1024", "€ 1.024 miljoen", text))
        assertFalse(supported("1024", "€ 1,024 million", text))
        assertFalse(supported("11700", "€11.7 billion", text))
        assertEquals(
            emptySet(),
            uncertain(period(revenue = amount("1024000", "€ 1.024 miljoen (vorig jaar: € 980 miljoen)"), revenuePrev = amount("980000", "€ 1.024 miljoen (vorig jaar: € 980 miljoen)")), text),
        )
    }

    @Test
    fun `USD extraction is verified against USD amounts in the text`() {
        val text = "Net income of \$2.0 billion and EBITDA of \$0.4bn on revenue of USD 15.6 billion (Q3 2025: USD 15.2 billion)."
        val usd = period(
            revenue = amount("15600000", "USD 15.6 billion (Q3 2025: USD 15.2 billion)"),
            revenuePrev = amount("15200000", "USD 15.6 billion (Q3 2025: USD 15.2 billion)"),
            profit = amount("400000", "\$0.4bn"),
            net = amount("2000000", "\$2.0 billion"),
            currency = "USD",
        )

        assertEquals(emptySet(), uncertain(usd, text))
    }
}
