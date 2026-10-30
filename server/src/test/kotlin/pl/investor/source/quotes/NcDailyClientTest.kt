package pl.investor.source.quotes

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pl.investor.history.DailyQuote
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NcDailyClientTest {

    private val http = mockk<PoliteHttpClient>()
    private val client = NcDailyClient(http, NcDailyParser())
    private val base = "https://newconnect.pl/pub/NEWCONNECT/statystyki/statystyki_dzienne"

    private fun assertDecimal(expected: String, actual: BigDecimal?) =
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")

    private fun List<DailyQuote>.byIsin(isin: String) = single { it.instrument == isin }

    @Test
    fun `parses the legacy xls file`() {
        every { http.getBytes(Source.NC_DAILY, "$base/NC_20260921.xls") } returns QuoteFixtures.bytes("quotes/nc-2026-09-21.xls")

        val trex = client.day(LocalDate.of(2026, 9, 21)).byIsin("PLAUXLM00026")

        assertDecimal("4.2", trex.close)
        assertEquals(17290L, trex.volume)
        assertDecimal("71243.44", trex.turnover)
        assertEquals(LocalDate.of(2026, 9, 21), trex.date)
    }

    @Test
    fun `parses the production xlsx file with extra columns`() {
        every { http.getBytes(Source.NC_DAILY, "$base/NC_20261029.xlsx") } returns QuoteFixtures.bytes("quotes/nc-2026-10-29.xlsx")

        val quotes = client.day(LocalDate.of(2026, 10, 29))
        val oneSolution = quotes.byIsin("PLONESL00011")

        assertTrue(quotes.size > 200)
        assertDecimal("0.0990", oneSolution.close)
        assertEquals(43689L, oneSolution.volume)
        assertDecimal("4270.41", oneSolution.turnover)
    }

    @Test
    fun `falls back to the other extension`() {
        every { http.getBytes(Source.NC_DAILY, "$base/NC_20260921.xlsx") } returns QuoteFixtures.HTML_PAGE
        every { http.getBytes(Source.NC_DAILY, "$base/NC_20260921.xls") } returns QuoteFixtures.bytes("quotes/nc-2026-09-21.xls")

        assertTrue(client.day(LocalDate.of(2026, 9, 21)).isNotEmpty())
        verify { http.getBytes(Source.NC_DAILY, "$base/NC_20260921.xls") }
    }

    @Test
    fun `non session day returns an empty list`() {
        every { http.getBytes(Source.NC_DAILY, any()) } returns QuoteFixtures.HTML_PAGE

        assertEquals(emptyList(), client.day(LocalDate.of(2026, 10, 31)))
    }
}
