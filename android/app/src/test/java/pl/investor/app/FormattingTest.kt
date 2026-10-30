package pl.investor.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FormattingTest {

    private val now = Instant.parse("2026-09-21T10:00:00Z")

    @Test
    fun `time formatting in Warsaw`() {
        assertEquals("08:09", Formatting.time(Instant.parse("2026-09-21T06:09:00Z"), now))
        assertEquals("wczoraj 22:14", Formatting.time(Instant.parse("2026-09-20T20:14:00Z"), now))
        assertEquals("18.09 08:09", Formatting.time(Instant.parse("2026-09-18T06:09:00Z"), now))
    }

    @Test
    fun `day boundary follows Warsaw time not UTC`() {
        assertEquals("00:20", Formatting.time(Instant.parse("2026-09-20T22:20:00Z"), now))
        assertEquals("wczoraj 23:55", Formatting.time(Instant.parse("2026-09-20T21:55:00Z"), now))
    }

    @Test
    fun `section titles`() {
        assertEquals("Dziś", Formatting.sectionTitle(Instant.parse("2026-09-21T06:09:00Z"), now))
        assertEquals("Wczoraj", Formatting.sectionTitle(Instant.parse("2026-09-20T20:14:00Z"), now))
        assertEquals("18.09", Formatting.sectionTitle(Instant.parse("2026-09-18T06:09:00Z"), now))
    }

    @Test
    fun `market labels`() {
        assertEquals("GPW", Formatting.marketLabel(Market.GPW))
        assertEquals("NC", Formatting.marketLabel(Market.NC))
        assertEquals("USA", Formatting.marketLabel(Market.US))
    }

    @Test
    fun `points drop the fraction when whole`() {
        assertEquals("26", Formatting.points(26.0))
        assertEquals("12,5", Formatting.points(12.5))
    }
}
