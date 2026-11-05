package pl.investor.source

import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.investor.history.indexInstrument
import pl.investor.history.sessionCalendar
import pl.investor.monitoring.PublicationWindows
import pl.investor.monitoring.SourceHealthTracker
import pl.investor.report.Market
import pl.investor.scoring.ReferenceSession
import pl.investor.source.avanza.AvanzaClient
import pl.investor.source.avanza.AvanzaParser
import pl.investor.source.avanza.AvanzaStockNotFoundException
import pl.investor.source.euronext.EuronextClient
import pl.investor.source.euronext.EuronextReleaseParser
import pl.investor.source.quotes.YahooChartClient
import pl.investor.source.xtb.XtbClient
import pl.investor.source.xtb.XtbCountry
import pl.investor.source.xtb.XtbOffer
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DutchSourcesTest {

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readText()

    private val http = PoliteHttpClient(Clock.systemUTC(), "me@example.com", mockk<SourceHealthTracker>(relaxed = true))

    @Test
    fun `Avanza identifies a share by ISIN only on Euronext Amsterdam`() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().body(fixture("nl/avanza-search-isin-shell.json")).build())
        server.enqueue(MockResponse.Builder().body(fixture("nl/avanza-stock-shell.json")).build())

        val shell = AvanzaClient(http, server.url("").toString().trimEnd('/')).findStockByIsin("GB00BP6MXD84", Market.NL)

        assertEquals("1338663", shell.orderBookId)
        assertEquals("SHELL", shell.ticker)
        assertEquals("EUR", shell.currency)
        server.takeRequest()
        assertEquals("/_api/market-guide/stock/1338663", server.takeRequest().url.encodedPath)
        assertEquals(2, server.requestCount)
        server.close()
    }

    @Test
    fun `Euronext listing is requested like the browser ajax call`() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().body(fixture("nl/euronext-listing-p0.json")).build())

        val rows = EuronextClient(http, EuronextReleaseParser(), server.url("").toString().trimEnd('/')).page(0)

        assertEquals(50, rows.size)
        val request = server.takeRequest()
        assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
        assertTrue("view_args=404" in request.url.toString() && "page=0" in request.url.toString())
        server.close()
    }

    @Test
    fun `share without an Amsterdam listing on Avanza is not covered`() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().body(fixture("de/avanza-search-isin-masterflex.json")).build())

        assertThrows<AvanzaStockNotFoundException> {
            AvanzaClient(http, server.url("").toString().trimEnd('/')).findStockByIsin("DE0005492938", Market.NL)
        }
        assertEquals(1, server.requestCount)
        server.close()
    }

    @Test
    fun `Avanza search and stock of ASML give the Amsterdam ticker`() {
        val hits = AvanzaParser.searchHits(fixture("nl/avanza-search-isin-asml.json"))
        val stock = AvanzaParser.stock(fixture("nl/avanza-stock-asml.json"))

        assertEquals(listOf("Euronext Amsterdam"), hits.map { it.marketPlaceName })
        assertEquals("ASML", stock.ticker)
        assertEquals("NL0010273215", stock.isin)
    }

    @Test
    fun `quarterly history keeps reported quarters and leaves unreported slots empty while a real zero stays zero`() {
        val asml = AvanzaParser.quarters(fixture("nl/avanza-analysis-asml.json")).single { it.quarterEnd == LocalDate.parse("2026-09-30") }
        assertEquals(0, BigDecimal("11662000").compareTo(asml.salesInThousands))
        assertEquals(LocalDate.parse("2026-10-14"), asml.reportDate)

        val imcd = AvanzaParser.quarters(fixture("nl/avanza-analysis-imcd.json"))
        val halfYear = imcd.single { it.quarterEnd == LocalDate.parse("2026-06-30") }
        val q3 = imcd.single { it.quarterEnd == LocalDate.parse("2026-09-30") }
        assertEquals(0, BigDecimal("2638000").compareTo(halfYear.salesInThousands))
        assertNull(q3.salesInThousands)
        assertNull(q3.netProfitInThousands)
        assertNull(q3.reportDate)

        val zeros = """{"companyFinancialsByQuarterQuarter":{"sales":[{"financialYear":2026,"reportType":"Q3","value":0,"date":"2026-11-05"}],
            |"netProfit":[{"financialYear":2026,"reportType":"Q3","value":0.0}]},"stockKeyRatiosByQuarter":{}}""".trimMargin()
        val zero = AvanzaParser.quarters(zeros).single()
        assertEquals(0, BigDecimal.ZERO.compareTo(zero.salesInThousands))
        assertEquals(0, BigDecimal.ZERO.compareTo(zero.netProfitInThousands))
        assertEquals(LocalDate.parse("2026-11-05"), zero.reportDate)
    }

    @Test
    fun `Avanza daily chart of ASML and Yahoo AEX sessions`() {
        val bars = AvanzaParser.dailyQuotes(fixture("nl/avanza-chart-asml.json"), "NL0010273215")
        assertEquals(26, bars.size)
        assertEquals(0, BigDecimal("1285.8").compareTo(bars.last().close))

        val aex = YahooChartClient.parse(fixture("nl/yahoo-aex.json"), "AEX")
        assertEquals(24, aex.size)
        assertEquals(aex.size, aex.map { it.date }.distinct().size)
        assertTrue(aex.all { it.instrument == "AEX" })
    }

    @Test
    fun `XTB Dutch cash stocks drop close-only and match by Avanza ticker, Euronext ticker or name`() {
        val stocks = XtbClient.parse(fixture("xtb/instruments-nl.json"), XtbCountry.NL)

        assertEquals(57, stocks.size)
        assertTrue(listOf("HDG", "RDSA", "URW").none { closeOnly -> stocks.any { it.ticker == closeOnly } })
        val offer = XtbOffer(mapOf(XtbCountry.NL to stocks))
        assertTrue(offer.lists(XtbCountry.NL, listOf("SHELL"), "Shell plc"))
        assertTrue(offer.lists(XtbCountry.NL, listOf("XXX", "LIGHT"), "SIGNIFY NV"))
        assertTrue(offer.lists(XtbCountry.NL, emptyList(), "Envipco Holding N.V."))
        assertFalse(offer.lists(XtbCountry.NL, listOf("BAMNB"), "Koninklijke BAM Groep N.V."))
    }

    @Test
    fun `Euronext Amsterdam session closes at 17 40 and NL uses AEX without a liquidity limit`() {
        val session = LocalDate.parse("2026-11-05")

        assertFalse(ReferenceSession.isClosed(session, Instant.parse("2026-11-05T16:39:00Z"), Market.NL))
        assertTrue(ReferenceSession.isClosed(session, Instant.parse("2026-11-05T16:41:00Z"), Market.NL))
        assertFalse(ReferenceSession.isClosed(LocalDate.parse("2026-07-15"), Instant.parse("2026-07-15T15:39:00Z"), Market.NL))
        assertEquals("AEX", Market.NL.indexInstrument())
        assertEquals("AEX", Market.NL.sessionCalendar())
        assertEquals("EUR", Market.NL.currency)
        assertNull(Market.NL.lowLiquidityTurnover)
        assertTrue(Market.NL.isCoveredByAvanza)
        assertTrue(PublicationWindows.isOpen(Instant.parse("2026-11-05T06:00:00Z"), Market.NL))
        assertFalse(PublicationWindows.isOpen(Instant.parse("2026-11-05T12:00:00Z"), Market.NL))
    }
}
