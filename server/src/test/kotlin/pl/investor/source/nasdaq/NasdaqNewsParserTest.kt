package pl.investor.source.nasdaq

import org.junit.jupiter.api.Test
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.source.gpw.ListingEntry
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NasdaqNewsParserTest {

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/se/$path")) { "missing fixture $path" }.readText()

    private val mainMarket = NasdaqNewsParser.parse(fixture("nasdaq-main-market.json"))
    private val firstNorth = NasdaqNewsParser.parse(fixture("nasdaq-first-north.json"))

    @Test
    fun `main market page has 200 items with the release fields`() {
        assertEquals(200, mainMarket.size)
        val macGregor = mainMarket.single { it.disclosureId == "1474109" }

        assertEquals("MacGregor Group AB", macGregor.company)
        assertEquals("en", macGregor.language)
        assertEquals("Interim report (Q1 and Q3)", macGregor.category)
        assertEquals("Main Market, Stockholm", macGregor.market)
        assertEquals(Instant.parse("2026-11-02T08:00:00Z"), macGregor.publishedAt)
        assertTrue(macGregor.messageUrl.startsWith("https://view.news.eu.nasdaq.com/view?id="))
        assertTrue(macGregor.headline.startsWith("MacGregor's Q3 2026 interim report"))
    }

    @Test
    fun `sv and en copies of one release share company, category and minute`() {
        val english = firstNorth.single { it.disclosureId == "1473457" }
        val swedish = firstNorth.single { it.disclosureId == "1473458" }

        assertEquals(0, english.languagePreference)
        assertEquals(1, swedish.languagePreference)
        assertEquals(english.company, swedish.company)
        assertEquals(english.category, swedish.category)
        assertEquals(english.publishedAt, swedish.publishedAt)
    }

    @Test
    fun `a few items of other markets leak into a market filter`() {
        assertTrue(mainMarket.any { it.market != NasdaqList.MAIN_MARKET.marketName })
        assertTrue(firstNorth.any { it.market != NasdaqList.FIRST_NORTH.marketName })
    }

    @Test
    fun `listing entry carries the category as number and the disclosure id as source ref`() {
        val entry = firstNorth.single { it.disclosureId == "1473716" }.toListingEntry(Market.SE)

        assertEquals(Market.SE, entry.market)
        assertEquals("Opter AB", entry.companyName)
        assertEquals(ReportKind.QUARTERLY, entry.kind)
        assertEquals("Interim report (Q1 and Q3)", entry.number)
        assertEquals("1473716", entry.sourceRef)
        assertEquals(null, entry.isin)
    }

    @Test
    fun `categories route to report kinds`() {
        assertEquals(ReportKind.QUARTERLY, NasdaqCategories.kindOf("Interim report (Q1 and Q3)"))
        assertEquals(ReportKind.QUARTERLY, NasdaqCategories.kindOf("Quarterly report"))
        assertEquals(ReportKind.HALF_YEARLY, NasdaqCategories.kindOf("Half Year financial report"))
        assertEquals(ReportKind.HALF_YEARLY, NasdaqCategories.kindOf("Half year financial report"))
        assertEquals(ReportKind.OTHER, NasdaqCategories.kindOf("Annual financial report"))
        assertEquals(ReportKind.ANNUAL, NasdaqCategories.kindOf("Financial statement release"))
        assertEquals(ReportKind.CURRENT, NasdaqCategories.kindOf("Inside information"))
        assertEquals(ReportKind.CURRENT, NasdaqCategories.kindOf("Tender offer"))
        listOf("Investor News", "Company Announcement", "Changes in company's own shares", "Total number of voting rights and capital")
            .forEach { assertEquals(ReportKind.OTHER, NasdaqCategories.kindOf(it), it) }
    }

    @Test
    fun `only Swedish entries of other categories are ignored`() {
        val swedish = firstNorth.first { it.category == "Investor News" }.toListingEntry(Market.SE)
        val polish = ListingEntry(Market.GPW, "X", null, ReportKind.OTHER, "1/2026", "1", "t", Instant.EPOCH, "u")

        assertTrue(swedish.isIgnoredNasdaqCategory())
        assertTrue(swedish.copy(kind = NasdaqCategories.kindOf("Annual financial report")).isIgnoredNasdaqCategory())
        assertFalse(swedish.copy(kind = NasdaqCategories.kindOf("Financial statement release")).isIgnoredNasdaqCategory())
        assertFalse(polish.isIgnoredNasdaqCategory())
    }
}
