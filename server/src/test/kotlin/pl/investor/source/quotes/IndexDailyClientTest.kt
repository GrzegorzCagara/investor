package pl.investor.source.quotes

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IndexDailyClientTest {

    private val http = mockk<PoliteHttpClient>()
    private val client = IndexDailyClient(http, GpwArchiveParser())
    private val date = LocalDate.of(2026, 9, 21)

    private fun sessionDay() {
        every { http.getBytes(Source.INDEX_DAILY, any()) } returns QuoteFixtures.bytes("index/gpw-indices-2026-09-21.xls")
    }

    @Test
    fun `index daily returns WIG close`() {
        sessionDay()

        val wig = client.day("WIG", date)!!

        assertEquals("WIG", wig.instrument)
        assertEquals(date, wig.date)
        assertEquals(0, BigDecimal("158381.53").compareTo(wig.close))
        verify { http.getBytes(Source.INDEX_DAILY, "https://www.gpw.pl/archiwum-notowan?fetch=1&type=1&instrument=&date=21-09-2026") }
    }

    @Test
    fun `index daily returns NCINDEX close`() {
        sessionDay()

        assertEquals(0, BigDecimal("276.25").compareTo(client.day("NCINDEX", date)!!.close))
    }

    @Test
    fun `two indices for the same date download the archive once`() {
        sessionDay()

        client.day("WIG", date)
        client.day("NCINDEX", date)

        verify(exactly = 1) { http.getBytes(Source.INDEX_DAILY, any()) }
    }

    @Test
    fun `empty result is not cached so a later retry fetches again`() {
        every { http.getBytes(Source.INDEX_DAILY, any()) } returnsMany listOf(
            QuoteFixtures.HTML_PAGE,
            QuoteFixtures.bytes("index/gpw-indices-2026-09-21.xls"),
        )

        assertNull(client.day("WIG", date))
        assertEquals(0, BigDecimal("158381.53").compareTo(client.day("WIG", date)!!.close))
        verify(exactly = 2) { http.getBytes(Source.INDEX_DAILY, any()) }
    }

    @Test
    fun `unknown index and non session day give null`() {
        sessionDay()
        assertNull(client.day("NOSUCH", date))

        every { http.getBytes(Source.INDEX_DAILY, any()) } returns QuoteFixtures.HTML_PAGE
        assertNull(client.day("WIG", LocalDate.of(2026, 5, 1)))
    }
}
