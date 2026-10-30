package pl.investor.extraction

import org.junit.jupiter.api.Test
import pl.investor.history.PeriodKind
import pl.investor.source.content.ReportContent
import pl.investor.source.content.SelectedDataRow
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuoteVerifierTest {

    private val verifier = QuoteVerifier()

    private fun extraction(
        revenue: Amount? = null,
        revenuePrevYear: Amount? = null,
        outlook: Outlook = Outlook(OutlookTone.NONE, null),
    ) = Extraction(
        category = ReportCategory.RESULTS,
        periods = listOf(
            ExtractedPeriod(
                kind = PeriodKind.MONTH,
                periodEnd = LocalDate.parse("2026-08-31"),
                currency = "USD",
                revenue = revenue,
                revenuePrevYear = revenuePrevYear,
                operatingProfit = null,
                operatingProfitPrevYear = null,
                netProfit = null,
                netProfitPrevYear = null,
            ),
        ),
        outlook = outlook,
        oneOffQuarter = false,
        oneOffQuote = null,
        cyclicalIndustry = false,
    )

    @Test
    fun `verifier accepts a real amount and rejects an invented one`() {
        val text = "przychody wyniosły ok. 604 mln USD wobec 316 mln USD rok wcześniej"

        val verified = verifier.verify(
            extraction(
                revenue = Amount(BigDecimal("604000"), "604 mln USD"),
                revenuePrevYear = Amount(BigDecimal("999000"), "999 mln USD"),
            ),
            text,
        )

        assertEquals(setOf("periods[0].revenuePrevYear"), verified.uncertainFields)
        assertEquals(BigDecimal("604000"), verified.extraction.periods.single().revenue?.valueInThousands)
        assertNull(verified.extraction.periods.single().revenuePrevYear)
    }

    @Test
    fun `verifier tolerates non-breaking spaces and thousands separators`() {
        val text = "przychody 1 052,3 mln USD"

        val verified = verifier.verify(
            extraction(revenue = Amount(BigDecimal("1052300"), "1 052,3 mln")),
            text,
        )

        assertTrue(verified.uncertainFields.isEmpty())
    }

    @Test
    fun `verifier accepts unitless table cells in thousands`() {
        val text = "Przychody netto ze sprzedaży 12 345 10 000 (dane w tys. zł)"

        val verified = verifier.verify(
            extraction(revenue = Amount(BigDecimal("12345"), "12 345")),
            text,
        )

        assertTrue(verified.uncertainFields.isEmpty())
    }

    @Test
    fun `verifier rejects an amount whose number does not match the quoted value`() {
        val text = "przychody 604 mln USD"

        val verified = verifier.verify(
            extraction(revenue = Amount(BigDecimal("700000"), "604 mln USD")),
            text,
        )

        assertEquals(setOf("periods[0].revenue"), verified.uncertainFields)
    }

    @Test
    fun `outlook with a quote missing from the text becomes NONE`() {
        val text = "przychody wzrosły"

        val verified = verifier.verify(
            extraction(outlook = Outlook(OutlookTone.POSITIVE, "spodziewamy się dalszego wzrostu")),
            text,
        )

        assertEquals(OutlookTone.NONE, verified.extraction.outlook.tone)
    }

    @Test
    fun `outlook with a quote present in the text is kept`() {
        val text = "Zarząd:  spodziewamy się dalszego wzrostu w IV kwartale"

        val verified = verifier.verify(
            extraction(outlook = Outlook(OutlookTone.POSITIVE, "spodziewamy się dalszego wzrostu")),
            text,
        )

        assertEquals(OutlookTone.POSITIVE, verified.extraction.outlook.tone)
    }

    private fun uncertainFor(value: String, source: String, text: String) =
        verifier.verify(extraction(revenue = Amount(BigDecimal(value), source)), text).uncertainFields

    @Test
    fun `amount inside a longer number is rejected`() {
        assertEquals(setOf("periods[0].revenue"), uncertainFor("604000", "604 mln USD", "przychody 1 604 mln USD"))
        assertEquals(setOf("periods[0].revenue"), uncertainFor("604000", "604 mln USD", "przychody 1604 mln USD"))
        assertEquals(setOf("periods[0].revenue"), uncertainFor("604000", "604 mln USD", "przychody 604,5 mln USD"))
    }

    @Test
    fun `label-prefixed quote whose number exists in the text is accepted`() {
        val text = "- przychody ze sprzedaży: 40,0 mln zł wobec 8,5 mln zł w 1 kw. 2025 r."

        assertTrue(uncertainFor("40000", "przychody ze sprzedaży: 40,0 mln zł", text).isEmpty())
        assertTrue(uncertainFor("40000", "przychody netto: 40,0 mln zł", text).isEmpty())
    }

    @Test
    fun `label-prefixed quote whose number is absent is rejected`() {
        val text = "- przychody ze sprzedaży: 40,0 mln zł wobec 8,5 mln zł"

        assertEquals(setOf("periods[0].revenue"), uncertainFor("41000", "przychody ze sprzedaży: 41,0 mln zł", text))
        assertEquals(setOf("periods[0].revenue"), uncertainFor("40000", "przychody ze sprzedaży: 41,0 mln zł", text))
    }

    @Test
    fun `quote with two numbers and no verbatim match is rejected`() {
        val text = "przychody 40,0 mln zł, zysk 32,3 mln zł"

        assertEquals(setOf("periods[0].revenue"), uncertainFor("40000", "przychody 40,0 mln zł zysk 32,3 mln zł", text))
    }

    @Test
    fun `number fallback keeps the unit so a wrong magnitude is rejected`() {
        val thousands = "przychody: 5 000 tys. zł"
        val millions = "przychody: 40,0 mln zł"

        assertTrue(uncertainFor("5000", "przychody: 5 000 tys. zł", thousands).isEmpty())
        assertEquals(setOf("periods[0].revenue"), uncertainFor("5000000", "przychody: 5 000 tys. zł", thousands))
        assertTrue(uncertainFor("40000", "przychody: 40,0 mln zł", millions).isEmpty())
        assertEquals(setOf("periods[0].revenue"), uncertainFor("40", "przychody: 40,0 mln zł", millions))
        assertEquals(setOf("periods[0].revenue"), uncertainFor("40000000", "przychody: 40,0 mln zł", millions))
    }

    @Test
    fun `number fallback cannot tell current from prior year when the labels are swapped`() {
        val text = "przychody 40,0 mln zł wobec 4,0 mln zł rok wcześniej"

        assertTrue(uncertainFor("4000", "przychody 4,0 mln zł", text).isEmpty())
    }

    @Test
    fun `longer number protection holds through the number fallback`() {
        assertEquals(
            setOf("periods[0].revenue"),
            uncertainFor("604000", "przychody wyniosły 604 mln USD", "przychody wyniosły 1 604 mln USD"),
        )
    }

    @Test
    fun `a later valid occurrence still passes`() {
        assertTrue(uncertainFor("604000", "604 mln USD", "rok 1 604 mln USD a sierpień 604 mln USD").isEmpty())
    }

    @Test
    fun `sign is respected`() {
        val text = "wynik netto -5 000 tys. zł"

        assertEquals(setOf("periods[0].revenue"), uncertainFor("5000", "5 000", text))
        assertTrue(uncertainFor("-5000", "-5 000", text).isEmpty())
        assertEquals(setOf("periods[0].revenue"), uncertainFor("5000", "5 000 tys.", "strata −5 000 tys. zł"))
    }

    @Test
    fun `percentages and years are not accepted as unitless amounts`() {
        assertEquals(setOf("periods[0].revenue"), uncertainFor("91", "91", "wzrost +91% r/r"))
        assertEquals(setOf("periods[0].revenue"), uncertainFor("2026", "2026", "w 2026 r. spółka"))
        assertEquals(setOf("periods[0].revenue"), uncertainFor("1500", "1500", "udział 1500 grudnia"))
    }

    @Test
    fun `unitless scaling needs a unit declaration`() {
        assertEquals(setOf("periods[0].revenue"), uncertainFor("12345000", "12 345", "Przychody 12 345"))
        assertTrue(uncertainFor("12345000", "12 345", "Przychody 12 345 (dane w tys. zł)").isEmpty())
    }

    @Test
    fun `blank outlook quote and one-off without a quote are not trusted`() {
        val base = extraction(outlook = Outlook(OutlookTone.POSITIVE, "  "))
        val verified = verifier.verify(base.copy(oneOffQuarter = true, oneOffQuote = null), "tekst")

        assertEquals(OutlookTone.NONE, verified.extraction.outlook.tone)
        assertFalse(verified.extraction.oneOffQuarter)
    }

    @Test
    fun `one-off with a quote present in the text is kept`() {
        val verified = verifier.verify(
            extraction().copy(oneOffQuarter = true, oneOffQuote = "debiut gry"),
            "wynik wspiera debiut gry StarRupture",
        )

        assertTrue(verified.extraction.oneOffQuarter)
    }

    @Test
    fun `reportInput appends attachment text after selected data within the cap`() {
        val content = ReportContent(
            text = "Raport kwartalny",
            selectedData = listOf(SelectedDataRow("Przychody", listOf("1 000"))),
            attachmentUrls = emptyList(),
            attachmentText = "Przychody ze sprzedaży wyniosły 992 558 tys. zł. " + "x".repeat(50_000),
        )

        val input = ExtractionPrompt.reportInput(content)

        assertTrue(input.indexOf("Przychody: 1 000") < input.indexOf("992 558"))
        assertEquals(ExtractionPrompt.MAX_TEXT_CHARS, input.length)
    }

    @Test
    fun `quote from attachment text passes verification`() {
        val content = ReportContent(
            text = "",
            selectedData = emptyList(),
            attachmentUrls = emptyList(),
            attachmentText = "W II kwartale przychody wyniosły 992 558 tys. zł, a zarząd oczekuje dalszego wzrostu.",
        )

        val verified = verifier.verify(
            extraction(revenue = Amount(BigDecimal("992558"), "992 558 tys. zł")).copy(
                outlook = Outlook(OutlookTone.POSITIVE, "zarząd oczekuje dalszego wzrostu"),
            ),
            ExtractionPrompt.reportInput(content),
        )

        assertTrue(verified.uncertainFields.isEmpty())
        assertEquals(OutlookTone.POSITIVE, verified.extraction.outlook.tone)
    }

    private fun verifiesRevenue(quote: String, valueInThousands: String, text: String) =
        "revenue" !in verifier.verify(extraction(revenue = Amount(BigDecimal(valueInThousands), quote)), text).uncertainFields.map { it.substringAfter('.') }

    @Test
    fun `multi group quote next to an adjacent table number is accepted`() {
        assertTrue(verifiesRevenue("492 198", "492198", "Zysk netto 535 042 492 198 w tys. PLN"))
    }

    @Test
    fun `multi group quote followed by an adjacent column is accepted`() {
        assertTrue(verifiesRevenue("1 027 240", "1027240", "Zysk netto 492 198 535 042 1 027 240 410 052"))
    }

    @Test
    fun `multi group quote preceded by a one or two digit group is rejected`() {
        assertFalse(verifiesRevenue("492 198", "492198", "Zysk netto 1 492 198 w tys. PLN"))
        assertFalse(verifiesRevenue("492 198", "492198", "Zysk netto 12 492 198 w tys. PLN"))
    }

    @Test
    fun `multi group quote preceded by a three digit group is accepted`() {
        assertTrue(verifiesRevenue("492 198", "492198", "Zysk netto 535 042 492 198 w tys. PLN"))
    }

    @Test
    fun `quote with a date and a single group amount stays strict`() {
        assertFalse(verifiesRevenue("604 mln USD w Q2 2026", "604000", "przychody 1 604 mln USD w Q2 2026 r."))
        assertTrue(verifiesRevenue("604 mln USD w Q2 2026", "604000", "przychody 604 mln USD w Q2 2026 r."))
    }

    @Test
    fun `single group quote before a thousands group is still rejected`() {
        assertFalse(verifiesRevenue("604", "604", "przychody 604 000 USD"))
    }

    @Test
    fun `single group quote after a thousands group is still rejected`() {
        assertFalse(verifiesRevenue("604", "604000", "przychody wyniosły 1 604 mln USD"))
    }

    @Test
    fun `english formatted quote passes when the text has the same number`() {
        assertTrue(verifiesRevenue("2,965,176", "2965176", "Revenue 2,965,176 1,685,702"))
        assertTrue(verifiesRevenue("1,696.7 million", "1696700", "revenues were USD 1,696.7 million in Q2"))
    }

    @Test
    fun `fragment of an english formatted number is rejected`() {
        assertFalse(verifiesRevenue("965,176", "965176", "Revenue 2,965,176 1,685,702"))
        assertFalse(verifiesRevenue("696.7 million", "696700", "revenues were USD 1,696.7 million in Q2"))
    }

    @Test
    fun `quote from a selectedData row passes against the rendered model input`() {
        val content = ReportContent(
            text = "Raport kwartalny",
            selectedData = listOf(SelectedDataRow("Przychody netto ze sprzedaży", listOf("12 345", "10 000"))),
            attachmentUrls = emptyList(),
        )

        val verified = verifier.verify(
            extraction(revenue = Amount(BigDecimal("12345"), "12 345")),
            ExtractionPrompt.reportInput(content),
        )

        assertTrue(verified.uncertainFields.isEmpty())
    }
}
