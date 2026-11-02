package pl.investor.source.avanza

import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test
import pl.investor.monitoring.SourceHealthTracker
import pl.investor.source.PoliteHttpClient
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvanzaParserTest {

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/se/$path")) { "missing fixture $path" }.readText()

    private fun decimal(value: String) = BigDecimal(value)

    @Test
    fun `search hit carries the order book id, the plain name and the market place`() {
        val hit = AvanzaParser.searchHits(fixture("avanza-search-opter.json")).single()

        assertEquals(AvanzaSearchHit("1307030", "Opter", "SE", "First North Stockholm"), hit)
    }

    @Test
    fun `stock info gives ISIN, ticker, currency and list`() {
        val stock = AvanzaParser.stock(fixture("avanza-stock-mips.json"))

        assertEquals(AvanzaStock("740416", "Mips", "SE0009216278", "MIPS", "SEK", "Large Cap Stockholm"), stock)
    }

    @Test
    fun `analysis gives ten quarters in thousands with report dates and ratios`() {
        val quarters = AvanzaParser.quarters(fixture("avanza-analysis-mips.json"))

        assertEquals(10, quarters.size)
        assertEquals(
            listOf(215000, 232000, 151000, 147000, 135000, 135000, 116000, 144000, 123000, 133000),
            quarters.map { it.salesInThousands?.toInt() },
        )
        val q3 = quarters.first()
        assertEquals(LocalDate.parse("2026-09-30"), q3.quarterEnd)
        assertEquals(LocalDate.parse("2026-10-21"), q3.reportDate)
        assertEquals(0, decimal("39000").compareTo(q3.netProfitInThousands))
        assertEquals(0, decimal("60.31").compareTo(q3.priceEarnings))
        assertEquals(0, decimal("13.84").compareTo(q3.priceSales))
        val q4 = quarters.single { it.quarterEnd == LocalDate.parse("2025-12-31") }
        assertEquals(LocalDate.parse("2026-02-11"), q4.reportDate)
    }

    @Test
    fun `daily bars are dated in Stockholm and get turnover from close and volume`() {
        val quotes = AvanzaParser.dailyQuotes(fixture("avanza-chart-mips.json"), "SE0009216278")

        assertEquals(45, quotes.size)
        assertEquals(LocalDate.parse("2026-09-01"), quotes.first().date)
        val last = quotes.last()
        assertEquals(LocalDate.parse("2026-11-02"), last.date)
        assertEquals(0, decimal("433.0").compareTo(last.close))
        assertEquals(42984, last.volume)
        assertEquals(0, decimal("18612072.00").compareTo(last.turnover))
    }

    @Test
    fun `company names are searched without legal form and matched exactly first`() {
        assertEquals("Mips", AvanzaClient.searchQuery("Mips AB"))
        assertEquals("Primrock Power", AvanzaClient.searchQuery("Primrock Power AB (publ)"))
        assertEquals("Lyckegård Group", AvanzaClient.searchQuery("Lyckegård Group AB"))
        val hits = listOf(
            AvanzaSearchHit("1", "Investor A", "SE", "Stockholmsbörsen"),
            AvanzaSearchHit("2", "Investor B", "SE", "Stockholmsbörsen"),
            AvanzaSearchHit("3", "Lyckegard Group", "NO", "Oslo"),
            AvanzaSearchHit("4", "Lyckegård Group", "SE", "First North Stockholm"),
        )
        assertEquals("4", AvanzaClient.bestMatch("Lyckegård Group", hits)?.orderBookId)
        assertEquals("1", AvanzaClient.bestMatch("Investor", hits)?.orderBookId)
        assertNull(AvanzaClient.bestMatch("Nelly", hits))
        assertNull(AvanzaClient.bestMatch("", hits))
    }

    @Test
    fun `hits on Spotlight or NGM are rejected even when the name matches`() {
        val hits = listOf(
            AvanzaSearchHit("5", "Nelly Group", "SE", "Spotlight Stock Market"),
            AvanzaSearchHit("6", "Nelly", "SE", "NGM"),
            AvanzaSearchHit("7", "Nellyx", "SE", "Spotlight Stock Market"),
        )

        assertNull(AvanzaClient.bestMatch("Nelly", hits))
        assertEquals("8", AvanzaClient.bestMatch("Nelly", hits + AvanzaSearchHit("8", "Nelly Group", "SE", "Stockholmsbörsen"))?.orderBookId)
    }

    @Test
    fun `client posts the search and fails when nothing matches`() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().body(fixture("avanza-search-mips.json")).build())
        server.enqueue(MockResponse.Builder().body(fixture("avanza-search-opter.json")).build())
        val client = AvanzaClient(PoliteHttpClient(Clock.systemUTC(), "me@example.com", mockk<SourceHealthTracker>(relaxed = true)), server.url("").toString().trimEnd('/'))

        assertEquals("740416", client.findStock("Mips AB").orderBookId)
        val request = server.takeRequest()
        assertEquals("/_api/search/filtered-search", request.url.encodedPath)
        assertTrue(request.body?.utf8().orEmpty().contains("\"query\":\"Mips\""))
        assertFailsWith<AvanzaStockNotFoundException> { client.findStock("Mips AB") }
        server.close()
    }
}
