package pl.investor.scoring

import org.junit.jupiter.api.Test
import pl.investor.history.indexInstrument
import pl.investor.history.sessionCalendar
import pl.investor.monitoring.PublicationWindows
import pl.investor.report.Market
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GermanSessionTest {

    @Test
    fun `a Xetra session counts as closed only after 17 35 Berlin time`() {
        val session = LocalDate.parse("2026-11-04")

        assertFalse(ReferenceSession.isClosed(session, Instant.parse("2026-11-04T16:34:00Z"), Market.DE))
        assertTrue(ReferenceSession.isClosed(session, Instant.parse("2026-11-04T16:36:00Z"), Market.DE))
        assertFalse(ReferenceSession.isClosed(LocalDate.parse("2026-07-15"), Instant.parse("2026-07-15T15:34:00Z"), Market.DE))
    }

    @Test
    fun `DE uses DAX as index and session calendar`() {
        assertEquals("DAX", Market.DE.indexInstrument())
        assertEquals("DAX", Market.DE.sessionCalendar())
        assertEquals("EUR", Market.DE.currency)
        assertNull(Market.DE.lowLiquidityTurnover)
    }

    @Test
    fun `EQS outage windows follow Berlin time`() {
        assertTrue(PublicationWindows.isOpen(Instant.parse("2026-11-04T05:30:00Z"), Market.DE))
        assertTrue(PublicationWindows.isOpen(Instant.parse("2026-11-04T07:59:00Z"), Market.DE))
        assertFalse(PublicationWindows.isOpen(Instant.parse("2026-11-04T05:29:00Z"), Market.DE))
        assertFalse(PublicationWindows.isOpen(Instant.parse("2026-11-04T12:00:00Z"), Market.DE))
        assertTrue(PublicationWindows.isOpen(Instant.parse("2026-11-04T16:30:00Z"), Market.DE))
        assertTrue(PublicationWindows.isOpen(Instant.parse("2026-11-04T23:30:00Z"), Market.DE))
    }
}
