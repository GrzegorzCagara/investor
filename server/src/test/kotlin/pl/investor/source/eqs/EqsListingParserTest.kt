package pl.investor.source.eqs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.investor.report.Market
import pl.investor.report.ReportKind
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EqsListingParserTest {

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/de/$path")) { "missing fixture $path" }.readText()

    private val items = EqsListingParser.parse(fixture("eqs-listing-en-p1.html"))

    private fun item(id: String) = items.single { it.newsId.startsWith(id) }

    @Test
    fun `lang=en page yields one item per release with English categories and Berlin times under the date header`() {
        assertEquals(31, items.size)
        val masterflex = item("1869172c")
        assertEquals("Masterflex SE", masterflex.company)
        assertEquals("DE0005492938", masterflex.isin)
        assertEquals("Corporate", masterflex.category)
        assertTrue(checkNotNull(masterflex.title).startsWith("Masterflex increases revenue and achieves record earnings"))
        assertEquals(Instant.parse("2026-11-04T06:30:00Z"), masterflex.publishedAt)
        assertTrue(masterflex.newsUrl.startsWith("https://www.eqs-news.com/news/corporate/"))
        assertTrue(masterflex.newsUrl.endsWith("1869172c-aa15-4cff-9a3e-5c5c433ac07b_en"))
        assertEquals(Instant.parse("2026-11-03T21:49:00Z"), items.last().publishedAt)
        assertEquals(setOf("Ad-hoc", "Corporate", "Voting rights", "Directors", "Advance financial reports", "UK Regulatory", "FR Regulatory", "Media"), items.map { it.category }.toSet())
    }

    @Test
    fun `only Ad-hoc and Corporate releases of German ISINs are covered`() {
        val covered = items.filter { it.isCovered }

        assertEquals(14, covered.size)
        assertTrue(covered.all { it.isin.startsWith("DE") && it.category in setOf("Ad-hoc", "Corporate") })
        assertFalse(item("4215644a").isCovered)
        assertFalse(item("9bbf38ce").isCovered)
        assertFalse(item("20a2f80d").isCovered)
        assertFalse(item("0f86b932").isCovered)
        assertNull(item("5790134e").title)
    }

    @Test
    fun `German-only release keeps its German detail url`() {
        assertTrue(item("c6d7e350").newsUrl.endsWith("_de"))
        assertTrue(item("0efb9d9b").newsUrl.endsWith("_en"))
    }

    @Test
    fun `listing entry carries market DE, the EQS category as number and the news uuid as source ref`() {
        val entry = item("0efb9d9b").toListingEntry()

        assertEquals(Market.DE, entry.market)
        assertEquals(ReportKind.CURRENT, entry.kind)
        assertEquals("Ad-hoc", entry.number)
        assertEquals("0efb9d9b-7a33-48a4-86f8-8d76de91ca3b", entry.sourceRef)
        assertEquals("DE000UNSE026", entry.isin)
        assertTrue(entry.isEqsCorporateNews().not())
        assertTrue(item("1869172c").toListingEntry().isEqsCorporateNews())
    }

    @Test
    fun `malformed items are skipped and the rest of the page is read`() {
        val html = fixture("eqs-listing-en-p1.html")
            .replace("data-news-item=\"1869172c-aa15-4cff-9a3e-5c5c433ac07b\"", "data-news-item=\"\"")
            .replace("data-news-item=\"4215644a-4850-42a8-a2e9-08a577cd4006\"", "data-news-item=\"\"")

        val parsed = EqsListingParser.parse(html)

        assertEquals(29, parsed.size)
        assertTrue(parsed.none { it.company == "Masterflex SE" })
        assertTrue(parsed.any { it.company == "Continental AG" })
    }

    @Test
    fun `items before the first date header are skipped`() {
        val html = fixture("eqs-listing-en-p1.html").replaceFirst(Regex("<p data-wio=\"news-feed-list-grouped-date-field\"[^>]*>[^<]*</p>"), "")

        assertEquals(listOf("REALITES"), EqsListingParser.parse(html).map { it.company })
    }

    @Test
    fun `a page whose items cannot be read at all is rejected`() {
        val html = fixture("eqs-listing-en-p1.html").replace(Regex("<p data-wio=\"news-feed-list-grouped-date-field\"[^>]*>[^<]*</p>"), "")

        assertThrows<IllegalArgumentException> { EqsListingParser.parse(html) }
        assertEquals(emptyList(), EqsListingParser.parse("<span data-total-count=\"0\"></span>"))
    }
}
