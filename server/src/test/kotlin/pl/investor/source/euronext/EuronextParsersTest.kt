package pl.investor.source.euronext

import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EuronextParsersTest {

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/nl/$path")) { "missing fixture $path" }.readText()

    private val parser = EuronextReleaseParser()

    private fun content(nid: String) = parser.content(fixture("node-$nid.html"), "https://live.euronext.com/en/ajax/node/company-press-release/$nid")

    @Test
    fun `listing page gives 50 releases newest first with Amsterdam times and decoded titles`() {
        val rows = EuronextListingParser.parse(fixture("euronext-listing-p0.json"))

        assertEquals(50, rows.size)
        assertEquals(EuronextRow("12921771", "ENVIPCO HOLDING N.V.", "Third Quarter 2026 Results Release and Earnings Call", Instant.parse("2026-11-05T13:38:00Z")), rows.first())
        val bam = rows.single { it.nid == "12921499" }
        assertEquals("Koninklijke BAM Groep N.V.", bam.company)
        assertEquals("BAM delivered strong performance in the first nine months 2026", bam.title)
        assertEquals(Instant.parse("2026-11-05T06:20:00Z"), bam.publishedAt)
        assertEquals(rows.sortedByDescending { it.publishedAt }.map { it.publishedAt }, rows.map { it.publishedAt })
    }

    @Test
    fun `summer time rows and HTML entities in titles are read`() {
        val oldest = EuronextListingParser.parse(fixture("euronext-listing-p4.json"))
        val ctp = EuronextListingParser.parse(fixture("euronext-listing-p2.json")).single { it.nid == "12918432" }

        assertEquals(12, oldest.size)
        assertEquals(Instant.parse("2026-10-05T05:00:00Z"), oldest.last().publishedAt)
        assertEquals("Aperam update on Q3 2026 market & financial trends", oldest.single { it.nid == "12914533" }.title)
        assertTrue("Serbia's largest" in ctp.title)
    }

    @Test
    fun `page after the last one is empty and a response without the view is rejected`() {
        assertEquals(emptyList(), EuronextListingParser.parse(fixture("euronext-listing-p5.json")))
        assertThrows<IllegalArgumentException> { EuronextListingParser.parse("""[{"command":"settings"}]""") }
    }

    @Test
    fun `issuer takes the first ISIN and the first Symbol without a leading digit`() {
        assertEquals(EuronextIssuer("NL0000337319", "BAMNB"), parser.issuer(fixture("node-12921499.html")))
        assertEquals(EuronextIssuer("NL0010273215", "ASML"), parser.issuer(fixture("node-12916206.html")))
        assertEquals(EuronextIssuer("LU1598757687", "MT"), parser.issuer(fixture("node-12921550.html")))
        assertEquals(EuronextIssuer("NL0015000GX8", "ENVI"), parser.issuer(fixture("node-12919282.html")))
    }

    @Test
    fun `bond notice has no Symbol`() {
        assertEquals(EuronextIssuer("XS2936812366", null), parser.issuer(fixture("node-12914496.html")))
    }

    @Test
    fun `PDF-only release has an empty body and links its PDF on live euronext com`() {
        val bam = content("12921499")

        assertEquals("", bam.text)
        assertEquals(1, bam.attachmentUrls.size)
        assertTrue(bam.attachmentUrls.single().startsWith("https://live.euronext.com/sites/default/files/company_press_releases/attachments/2026/11/05/Connect_pr-q3-2026-final.pdf"))
        assertTrue(bam.attachmentsArePdf)
    }

    @Test
    fun `release without a body block gives an empty body, never the issuer header`() {
        val document = Jsoup.parse(fixture("node-12921454.html"))
        document.select("#field_company_press_release_isin > .row.mb-5").last()?.remove()

        val content = parser.content(document.body().html(), "https://live.euronext.com/en/ajax/node/company-press-release/12921454")

        assertEquals("", content.text)
    }

    @Test
    fun `GlobeNewswire release keeps the body, drops the attachment section and links only the Euronext PDF copy`() {
        val imcd = content("12921454")

        assertTrue("Revenue at EUR 3,965 million" in imcd.text)
        assertTrue("(first nine months 2025: EUR 284 million)" in imcd.text)
        assertTrue("Attachment" !in imcd.text && "globenewswire" !in imcd.text.lowercase())
        assertTrue(imcd.text.length < 1500)
        assertEquals(1, imcd.attachmentUrls.size)
        assertTrue(imcd.attachmentUrls.single().startsWith("https://live.euronext.com/sites/default/files/company_press_releases/attachments/"))
    }

    @Test
    fun `full release body is long and has no footer fields`() {
        val vopak = content("12921130")

        assertTrue(vopak.text.length > 1500)
        assertTrue("Vopak" in vopak.text)
        assertTrue("Provider" !in vopak.text && "Company Name" !in vopak.text)
    }
}
