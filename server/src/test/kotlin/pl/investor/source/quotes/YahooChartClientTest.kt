package pl.investor.source.quotes

import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test
import pl.investor.monitoring.SourceHealthTracker
import pl.investor.source.PoliteHttpClient
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YahooChartClientTest {

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/se/$path")) { "missing fixture $path" }.readText()

    @Test
    fun `OMXSPI chart gives one close per Stockholm session`() {
        val quotes = YahooChartClient.parse(fixture("yahoo-omxspi.json"), "OMXSPI")

        assertEquals(22, quotes.size)
        assertEquals(quotes.size, quotes.map { it.date }.distinct().size)
        assertTrue(quotes.all { it.instrument == "OMXSPI" && it.turnover == null })
        assertEquals(LocalDate.parse("2026-11-02"), quotes.last().date)
        assertTrue(quotes.none { it.date.dayOfWeek.value > 5 })
    }

    @Test
    fun `stock chart parses with volume`() {
        val quotes = YahooChartClient.parse(fixture("yahoo-mips.json"), "SE0009216278")

        assertTrue(quotes.isNotEmpty())
        assertTrue(quotes.any { it.volume > 0 })
        assertNull(quotes.first().turnover)
    }

    @Test
    fun `client asks for daily bars of the encoded symbol in the requested range`() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().body(fixture("yahoo-omxspi.json")).build())
        val client = YahooChartClient(PoliteHttpClient(Clock.systemUTC(), "me@example.com", mockk<SourceHealthTracker>(relaxed = true)), server.url("").toString().trimEnd('/'))

        client.daily("^OMXSPI", "OMXSPI", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-02"))

        val url = server.takeRequest().url
        assertEquals("/v8/finance/chart/%5EOMXSPI", url.encodedPath)
        assertEquals("1d", url.queryParameter("interval"))
        assertEquals("1790805600", url.queryParameter("period1"))
        assertEquals("1793660400", url.queryParameter("period2"))
        server.close()
    }
}
