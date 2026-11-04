package pl.investor.source.eqs

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EqsReleaseParserTest {

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/de/$path")) { "missing fixture $path" }.readText()

    private fun text(path: String) = EqsReleaseParser().parse(fixture(path), "https://www.eqs-news.com/news/$path").text

    @Test
    fun `German Corporate results release keeps the body and drops the contact table and the sidebars`() {
        val text = text("eqs-masterflex-q3-2026-de.html")

        assertTrue(text.startsWith("Masterflex SE / Schlagwort(e): Quartals-/Zwischenmitteilung"))
        assertTrue("Konzernumsatz nach neun Monaten bei 82,2 Mio. Euro (9M/2025: 79,8 Mio. Euro)" in text)
        assertTrue("27,9 Mio. Euro" in text)
        assertFalse("Sprache:" in text)
        assertFalse("Willy-Brandt-Allee" in text)
        assertFalse("Ende der Mitteilung" in text)
        assertFalse("Aktuelle News" in text)
    }

    @Test
    fun `German Ad-hoc release ends at the end of the whole announcement`() {
        val text = text("eqs-uniper-adhoc-2026-11-04-de.html")

        assertTrue("Uniper erhöht die Prognose für das Adjusted Net Income für das Jahr 2026 um 50 Mio. EUR" in text)
        assertTrue("1.024 Mio. EUR (Vorjahreszeitraum: 641 Mio. EUR)" in text)
        assertFalse("EQS Distributionsservices" in text)
        assertFalse("Börsen:" in text)
        assertFalse("Ende der Mitteilung" in text)
    }

    @Test
    fun `issuer explanation after the end of the inside information stays in the text`() {
        val withExplanation = fixture("eqs-uniper-adhoc-2026-11-04-de.html").replaceFirst(
            "Ende der Insiderinformation",
            "Ende der Insiderinformation<br/><br/>Informationen und Erläuterungen des Emittenten zu dieser Mitteilung:<br/>Umsatz 9M 2026: 1.234 Mio. EUR (Vorjahr: 1.100 Mio. EUR)",
        )

        val text = EqsReleaseParser().parse(withExplanation, "https://www.eqs-news.com/uniper").text

        assertTrue("Umsatz 9M 2026: 1.234 Mio. EUR (Vorjahr: 1.100 Mio. EUR)" in text)
        assertFalse("EQS Distributionsservices" in text)
    }

    @Test
    fun `real English Ad-hoc keeps the issuer explanation with its figures`() {
        val text = text("eqs-teamviewer-adhoc-q3-2026-en.html")

        assertTrue("Information and Explanation of the Issuer to this News" in text)
        assertTrue("compared to Q3 2025 comparable pro forma Revenue of €192.0m" in text)
        assertTrue(text.endsWith("CET/CEST"))
        assertFalse("EQS Distribution Services" in text)
        assertFalse("Language:" in text)
    }

    @Test
    fun `English release is read the same way`() {
        val text = text("eqs-continental-q3-2026-en.html")

        assertTrue("Consolidated sales of €3.5 billion (Q3 2025: €3.9 billion, -1.5 percent organically)" in text)
        assertFalse("End of News" in text)
        assertFalse("Language:" in text)
        assertFalse("Latest News" in text)
    }
}
