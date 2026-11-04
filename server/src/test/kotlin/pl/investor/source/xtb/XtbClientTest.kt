package pl.investor.source.xtb

import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.investor.monitoring.SourceHealthTracker
import pl.investor.source.PoliteHttpClient
import java.time.Clock
import kotlin.test.assertEquals

class XtbClientTest {

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/xtb/$path")) { "missing fixture $path" }.readText()

    @Test
    fun `Polish list keeps tradable shares and drops close-only stocks (flagged or named so), ETFs and CFDs`() {
        val stocks = XtbClient.parse(fixture("instruments-pl.json"), XtbCountry.PL)

        assertEquals(listOf("11B", "XTB", "ZUK", "ZWC"), stocks.map { it.ticker })
        assertEquals("Zaklady Urzadzen Kotlowych Staporkow SA", stocks.single { it.ticker == "ZUK" }.companyName)
    }

    @Test
    fun `Swedish list keeps share classes as separate tickers`() {
        val stocks = XtbClient.parse(fixture("instruments-se.json"), XtbCountry.SE)

        assertEquals(listOf("ADDTB", "SWECA", "SWECB", "VOLVB"), stocks.map { it.ticker })
    }

    @Test
    fun `a list without tradable stocks of the requested country is rejected`() {
        assertThrows<UnexpectedXtbListException> { XtbClient.parse(fixture("instruments-se.json"), XtbCountry.PL) }
    }

    @Test
    fun `a list shorter than the declared count is rejected`() {
        val truncated = fixture("instruments-pl.json").replace("\"cashstocks\": 6", "\"cashstocks\": 732")

        assertThrows<UnexpectedXtbListException> { XtbClient.parse(truncated, XtbCountry.PL) }
    }

    @Test
    fun `an empty list is rejected`() {
        assertThrows<UnexpectedXtbListException> { XtbClient.parse("""{"instrumentCounts":{},"items":[]}""", XtbCountry.PL) }
    }

    @Test
    fun `client asks the Polish branch for the whole country list in one page`() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().body(fixture("instruments-se.json")).build())
        val client = XtbClient(PoliteHttpClient(Clock.systemUTC(), "me@example.com", mockk<SourceHealthTracker>(relaxed = true)), server.url("").toString().trimEnd('/'))

        client.stocks(XtbCountry.SE)

        val url = server.takeRequest().url
        assertEquals("/web-api/v3/languages/pl/branches/pl/instrument-specification", url.encodedPath)
        assertEquals("SE", url.queryParameter("country"))
        assertEquals("5000", url.queryParameter("pageSize"))
        server.close()
    }
}
