package pl.investor.source.quotes

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import pl.investor.source.quotes.QuoteFixtures.ASBIS_ISIN
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpwArchiveClientTest {

    private val http = mockk<PoliteHttpClient>()
    private val client = GpwArchiveClient(http, GpwArchiveParser())

    @Test
    fun `fetches the stock archive for the given day`() {
        every { http.getBytes(Source.GPW_ARCHIVE, any()) } returns QuoteFixtures.bytes("quotes/gpw-2026-09-21.xls")

        val quotes = client.day(LocalDate.of(2026, 9, 21))

        assertTrue(quotes.any { it.instrument == ASBIS_ISIN })
        verify {
            http.getBytes(Source.GPW_ARCHIVE, "https://www.gpw.pl/archiwum-notowan?fetch=1&type=10&instrument=&date=21-09-2026")
        }
    }

    @Test
    fun `non session day returns an empty list`() {
        every { http.getBytes(Source.GPW_ARCHIVE, any()) } returns QuoteFixtures.HTML_PAGE

        assertEquals(emptyList(), client.day(LocalDate.of(2026, 5, 1)))
    }

    @Test
    fun `empty response returns an empty list`() {
        every { http.getBytes(Source.GPW_ARCHIVE, any()) } returns ByteArray(0)

        assertEquals(emptyList(), client.day(LocalDate.of(2026, 5, 2)))
    }
}
