package pl.investor.source.avanza

import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test
import pl.investor.monitoring.SourceHealthTracker
import pl.investor.source.PoliteHttpClient
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import pl.investor.report.Market
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
        assertEquals("4", AvanzaClient.matches("Lyckegård Group", hits, Market.SE).firstOrNull()?.orderBookId)
        assertEquals("1", AvanzaClient.matches("Investor", hits, Market.SE).firstOrNull()?.orderBookId)
        assertNull(AvanzaClient.matches("Nelly", hits, Market.SE).firstOrNull())
        assertNull(AvanzaClient.matches("", hits, Market.SE).firstOrNull())
    }

    @Test
    fun `hits on Spotlight or NGM are rejected even when the name matches`() {
        val hits = listOf(
            AvanzaSearchHit("5", "Nelly Group", "SE", "Spotlight Stock Market"),
            AvanzaSearchHit("6", "Nelly", "SE", "NGM"),
            AvanzaSearchHit("7", "Nellyx", "SE", "Spotlight Stock Market"),
        )

        assertNull(AvanzaClient.matches("Nelly", hits, Market.SE).firstOrNull())
        assertEquals("8", AvanzaClient.matches("Nelly", hits + AvanzaSearchHit("8", "Nelly Group", "SE", "Stockholmsbörsen"), Market.SE).firstOrNull()?.orderBookId)
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

    @Test
    fun `apostrophes vanish from the search query and wider legal forms are stripped`() {
        assertEquals("Neems Hill", AvanzaClient.searchQuery("Neem's Hill AB"))
        assertEquals("Neems Hill", AvanzaClient.searchQuery("Neem’s Hill AB"))
        assertEquals("Kvika banki", AvanzaClient.searchQuery("Kvika banki hf."))
        assertEquals("KRUK", AvanzaClient.searchQuery("KRUK S.A."))
        assertEquals("Creditas Financial Solutions", AvanzaClient.searchQuery("Creditas Financial Solutions LTD"))
    }

    @Test
    fun `fallback queries drop trailing generic words, then keep the first word of at least four letters`() {
        assertEquals(listOf("Mips"), AvanzaClient.searchQueries("Mips AB"))
        assertEquals(listOf("Lyckegård Group", "Lyckegård"), AvanzaClient.searchQueries("Lyckegård Group AB"))
        assertEquals(listOf("Neems Hill", "Neem"), AvanzaClient.searchQueries("Neem's Hill AB"))
        assertEquals(listOf("Avanza Bank Holding", "Avanza"), AvanzaClient.searchQueries("Avanza Bank Holding AB (publ)"))
        assertEquals(
            listOf("Sparbanken Mälardalen Holding", "Sparbanken Mälardalen", "Sparbanken"),
            AvanzaClient.searchQueries("Sparbanken Mälardalen Holding AB"),
        )
        assertEquals(listOf("Ziklo Bank", "Ziklo"), AvanzaClient.searchQueries("Ziklo Bank AB"))
        assertEquals(listOf("KRUK"), AvanzaClient.searchQueries("KRUK S.A."))
        assertEquals(listOf("SBB Group", "SBB"), AvanzaClient.searchQueries("SBB Group AB"))
    }

    @Test
    fun `the full name finds Neem's Hill once the apostrophe is removed`() {
        withAvanza("avanza-search-neems-hill.json") { client, server ->
            assertEquals("710553", client.findStock("Neem's Hill AB").orderBookId)
            assertEquals(listOf("Neems Hill"), server.searchedQueries(1))
        }
    }

    @Test
    fun `the first word finds Neem's Hill when the full name has no hit`() {
        withAvanza(NO_HITS, "avanza-search-neem.json") { client, server ->
            assertEquals("710553", client.findStock("Neem's Hill AB").orderBookId)
            assertEquals(listOf("Neems Hill", "Neem"), server.searchedQueries(2))
        }
    }

    @Test
    fun `the name without Group finds Lyckegård`() {
        withAvanza("avanza-search-lyckegard-group.json", "avanza-search-lyckegard.json") { client, server ->
            assertEquals("1348985", client.findStock("Lyckegård Group AB").orderBookId)
            assertEquals(listOf("Lyckegård Group", "Lyckegård"), server.searchedQueries(2))
        }
    }

    @Test
    fun `a fallback hit must still match the full name so Neem never accepts Neemo Tech`() {
        val neemoTech = AvanzaSearchHit("99", "Neemo Tech", "SE", "First North Stockholm")
        assertNull(AvanzaClient.matches("Neems Hill", listOf(neemoTech), Market.SE).firstOrNull())
        val neemoTechResponse = fixture("avanza-search-neem.json").replace("Neem's Hill B (NEEMS B)", "Neemo Tech (NEEMO)")
        withAvanza(NO_HITS, neemoTechResponse) { client, server ->
            assertFailsWith<AvanzaStockNotFoundException> { client.findStock("Neem's Hill AB") }
            assertEquals(listOf("Neems Hill", "Neem"), server.searchedQueries(2))
        }
    }

    @Test
    fun `identification gives up after at most three searches`() {
        withAvanza(NO_HITS, NO_HITS, NO_HITS, NO_HITS) { client, server ->
            assertFailsWith<AvanzaStockNotFoundException> { client.findStock("Sparbanken Mälardalen Holding AB") }
            assertEquals(3, server.requestCount)
        }
    }

    private fun withAvanza(vararg responses: String, block: (AvanzaClient, MockWebServer) -> Unit) {
        val server = MockWebServer().apply { start() }
        responses.forEach { server.enqueue(MockResponse.Builder().body(if (it.endsWith(".json")) fixture(it) else it).build()) }
        val client = AvanzaClient(PoliteHttpClient(Clock.systemUTC(), "me@example.com", mockk<SourceHealthTracker>(relaxed = true)), server.url("").toString().trimEnd('/'))
        try {
            block(client, server)
        } finally {
            server.close()
        }
    }

    private fun MockWebServer.searchedQueries(count: Int): List<String> =
        List(count) { mapper.readTree(takeRequest().body?.utf8().orEmpty()).path("query").asString() }

    private companion object {
        const val NO_HITS = """{"totalNumberOfHits":0,"hits":[],"searchQuery":"","searchFilter":{"types":["STOCK"]},"facets":{"types":[]},"pagination":{"size":5,"from":0}}"""
        val mapper: JsonMapper = JsonMapper.builder().build()
    }
}
