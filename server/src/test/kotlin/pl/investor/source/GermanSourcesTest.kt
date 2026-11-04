package pl.investor.source

import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.investor.monitoring.SourceHealthTracker
import pl.investor.source.avanza.AvanzaClient
import pl.investor.source.avanza.AvanzaParser
import pl.investor.source.avanza.AvanzaStockNotFoundException
import pl.investor.source.quotes.YahooChartClient
import pl.investor.source.xtb.XtbClient
import pl.investor.source.xtb.XtbCountry
import pl.investor.source.xtb.XtbOffer
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GermanSourcesTest {

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readText()

    private val http = PoliteHttpClient(Clock.systemUTC(), "me@example.com", mockk<SourceHealthTracker>(relaxed = true))

    @Test
    fun `Avanza finds a German share by ISIN on Xetra and gives its EUR ticker`() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().body(fixture("de/avanza-search-isin-masterflex.json")).build())
        server.enqueue(MockResponse.Builder().body(fixture("de/avanza-stock-masterflex.json")).build())

        val stock = AvanzaClient(http, server.url("").toString().trimEnd('/')).findStockByIsin("DE0005492938")

        assertEquals("1748784", stock.orderBookId)
        assertEquals("MZX", stock.ticker)
        assertEquals("EUR", stock.currency)
        assertEquals(null, stock.listing)
        assertTrue("\"query\":\"DE0005492938\"" in server.takeRequest().body!!.utf8())
        assertEquals("/_api/market-guide/stock/1748784", server.takeRequest().url.encodedPath)
        server.close()
    }

    @Test
    fun `Avanza hit with another ISIN or no hit at all means the share is not covered`() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().body(fixture("de/avanza-search-isin-masterflex.json")).build())
        server.enqueue(MockResponse.Builder().body(fixture("de/avanza-stock-masterflex.json")).build())
        server.enqueue(MockResponse.Builder().body(fixture("de/avanza-search-isin-umweltbank.json")).build())
        val client = AvanzaClient(http, server.url("").toString().trimEnd('/'))

        assertThrows<AvanzaStockNotFoundException> { client.findStockByIsin("DE0005492939") }
        assertThrows<AvanzaStockNotFoundException> { client.findStockByIsin("DE0005570808") }
        server.close()
    }

    @Test
    fun `Avanza analysis and daily chart of a German share`() {
        val quarters = AvanzaParser.quarters(fixture("de/avanza-analysis-masterflex.json"))
        val q3 = quarters.single { it.quarterEnd == LocalDate.parse("2025-09-30") }
        assertEquals(0, BigDecimal("26520").compareTo(q3.salesInThousands))
        assertEquals(LocalDate.parse("2025-11-05"), q3.reportDate)

        val bars = AvanzaParser.dailyQuotes(fixture("de/avanza-chart-masterflex.json"), "DE0005492938")
        assertEquals(46, bars.size)
        assertEquals(LocalDate.parse("2026-11-04"), bars.last().date)
        assertEquals(0, BigDecimal("14.1").compareTo(bars.last().close))
        assertEquals(4183, bars.last().volume)
    }

    @Test
    fun `Yahoo DAX chart gives one close per Xetra session`() {
        val dax = YahooChartClient.parse(fixture("de/yahoo-gdaxi.json"), "DAX")

        assertEquals(23, dax.size)
        assertEquals(LocalDate.parse("2026-11-04"), dax.last().date)
        assertEquals(dax.size, dax.map { it.date }.distinct().size)
        assertTrue(dax.all { it.instrument == "DAX" })
    }

    @Test
    fun `XTB German cash stocks keep Xetra tickers, drop close-only and pass the completeness guard`() {
        val stocks = XtbClient.parse(fixture("xtb/instruments-de.json"), XtbCountry.DE)

        assertEquals(listOf("12DA", "1COV", "BSL", "CON", "MZX", "TIMA", "UN0"), stocks.map { it.ticker })
        val offer = XtbOffer(mapOf(XtbCountry.DE to stocks))
        assertTrue(offer.lists(XtbCountry.DE, "MZX", "Masterflex SE"))
        assertTrue(offer.lists(XtbCountry.DE, null, "ZEAL Network SE"))
        assertFalse(offer.lists(XtbCountry.DE, null, "Daimler AG"))
    }
}
