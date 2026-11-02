package pl.investor.scoring

import pl.investor.report.Market
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReferenceSessionTest {

    private val sessions = listOf("2026-09-17", "2026-09-18", "2026-09-21", "2026-07-29").map(LocalDate::parse)

    @Test
    fun `reference session for a report at 08 09 is the previous trading day`() {
        assertEquals(
            LocalDate.parse("2026-09-18"),
            ReferenceSession.lastClosedSessionBefore(Instant.parse("2026-09-21T06:09:00Z"), sessions, Market.GPW),
        )
    }

    @Test
    fun `reference session for a report at 17 14 is the same day`() {
        assertEquals(
            LocalDate.parse("2026-07-29"),
            ReferenceSession.lastClosedSessionBefore(Instant.parse("2026-07-29T15:14:57Z"), sessions, Market.GPW),
        )
    }

    @Test
    fun `a report at 17 04 still sees the previous session`() {
        assertEquals(
            LocalDate.parse("2026-09-17"),
            ReferenceSession.lastClosedSessionBefore(Instant.parse("2026-09-18T15:04:00Z"), sessions, Market.GPW),
        )
    }

    @Test
    fun `no closed session fails`() {
        assertFailsWith<NoSuchElementException> {
            ReferenceSession.lastClosedSessionBefore(Instant.parse("2026-01-01T00:00:00Z"), sessions, Market.GPW)
        }
    }

    @Test
    fun `a Swedish report at 17 20 still sees the previous session`() {
        assertEquals(
            LocalDate.parse("2026-09-17"),
            ReferenceSession.lastClosedSessionBefore(Instant.parse("2026-09-18T15:20:00Z"), sessions, Market.SE),
        )
    }

    @Test
    fun `a Swedish session counts as closed only after 17 35 Stockholm time`() {
        val session = LocalDate.parse("2026-11-02")

        assertEquals(false, ReferenceSession.isClosed(session, Instant.parse("2026-11-02T16:34:00Z"), Market.SE))
        assertEquals(true, ReferenceSession.isClosed(session, Instant.parse("2026-11-02T16:36:00Z"), Market.SE))
        assertEquals(true, ReferenceSession.isClosed(session, Instant.parse("2026-11-02T16:06:00Z"), Market.GPW))
    }
}
