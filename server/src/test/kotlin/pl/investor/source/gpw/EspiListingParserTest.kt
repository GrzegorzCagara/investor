package pl.investor.source.gpw

import org.junit.jupiter.api.Test
import pl.investor.report.Market
import pl.investor.report.ReportKind
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EspiListingParserTest {

    private val parser = EspiListingParser()

    private fun fixture(path: String) =
        checkNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readText()

    @Test
    fun `parses ASBIS RB 23 2026 from the 21 September listing`() {
        val entry = parser.parse(fixture("gpw/listing-2026-09-21-p1.html"), Market.GPW).entries
            .single { it.number == "23/2026" && it.companyName.contains("ASBIS", true) }

        assertEquals(ReportKind.CURRENT, entry.kind)
        assertEquals(Instant.parse("2026-09-21T06:09:21Z"), entry.publishedAt)
        assertTrue(entry.title!!.contains("szacunkowych miesięcznych"))
        assertEquals("ASBISC ENTERPRISES PLC", entry.companyName)
        assertEquals("CY1000031710", entry.isin)
        assertEquals("https://www.gpw.pl/komunikat?geru_id=497297", entry.detailUrl)
        assertEquals(Market.GPW, entry.market)
        assertEquals("497297", entry.sourceRef)
    }

    @Test
    fun `parses half-yearly report published at 00 20`() {
        val entry = parser.parse(fixture("gpw/listing-2026-08-06-p2.html"), Market.GPW).entries.single()

        assertEquals(ReportKind.HALF_YEARLY, entry.kind)
        assertEquals(Instant.parse("2026-08-05T22:20:21Z"), entry.publishedAt)
        assertNull(entry.title)
        assertEquals("/2026", entry.number)
        assertTrue(entry.companyName.contains("ASBIS", true))
        assertEquals("https://www.gpw.pl/komunikat?geru_id=494951", entry.detailUrl)
    }

    @Test
    fun `page sizes match the real multi-page days`() {
        assertEquals(50, parser.parse(fixture("gpw/listing-2026-05-12-p1.html"), Market.GPW).entries.size)
        assertEquals(12, parser.parse(fixture("gpw/listing-2026-05-12-p2.html"), Market.GPW).entries.size)
        assertEquals(50, parser.parse(fixture("gpw/listing-2026-08-06-p1.html"), Market.GPW).entries.size)
        assertEquals(1, parser.parse(fixture("gpw/listing-2026-08-06-p2.html"), Market.GPW).entries.size)
    }

    @Test
    fun `parses the item without a kind part from the 21 August listing`() {
        val page = parser.parse(fixture("gpw/listing-2026-08-21-p1.html"), Market.GPW)

        assertEquals(40, page.entries.size)
        assertEquals(40, page.rawItemCount)
        assertTrue(page.unparsed.isEmpty())
        val santander = page.entries.single { it.sourceRef == "495705" }
        assertEquals(ReportKind.OTHER, santander.kind)
        assertEquals("66/2026", santander.number)
    }

    @Test
    fun `unparseable item is reported and does not fail the page`() {
        val html = """
            <li><span class="date">garbage</span><strong class="name"><a href="komunikat?geru_id=1">A (PLAAAAAAAAAA)</a></strong></li>
            <li><span class="date">21-09-2026 08:09:21 | Bieżący | ESPI | 1/2026</span><strong class="name"><a href="komunikat?geru_id=2">B (PLBBBBBBBBBB)</a></strong><p></p></li>
        """
        val page = parser.parse(html, Market.GPW)

        assertEquals(2, page.rawItemCount)
        assertEquals(listOf("2"), page.entries.map { it.sourceRef })
        assertEquals(1, page.unparsed.size)
    }

    @Test
    fun `parses NewConnect listing with EBI entries and NC detail urls`() {
        val entries = parser.parse(fixture("nc/listing-2026-09-21-p1.html"), Market.NC).entries

        assertEquals(21, entries.size)
        val ebi = entries.single { it.detailUrl.endsWith("geru_id=243921") }
        assertEquals("Lokaty Budowlane Spółka Akcyjna", ebi.companyName)
        assertEquals("PLFBRD000018", ebi.isin)
        assertEquals(ReportKind.CURRENT, ebi.kind)
        assertEquals("13/2026", ebi.number)
        assertEquals("https://newconnect.pl/komunikat?geru_id=243921", ebi.detailUrl)
        assertEquals(Market.NC, ebi.market)
    }
}
