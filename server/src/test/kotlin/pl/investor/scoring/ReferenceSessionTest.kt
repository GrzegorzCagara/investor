package pl.investor.scoring

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
            ReferenceSession.lastClosedSessionBefore(Instant.parse("2026-09-21T06:09:00Z"), sessions),
        )
    }

    @Test
    fun `reference session for a report at 17 14 is the same day`() {
        assertEquals(
            LocalDate.parse("2026-07-29"),
            ReferenceSession.lastClosedSessionBefore(Instant.parse("2026-07-29T15:14:57Z"), sessions),
        )
    }

    @Test
    fun `a report at 17 04 still sees the previous session`() {
        assertEquals(
            LocalDate.parse("2026-09-17"),
            ReferenceSession.lastClosedSessionBefore(Instant.parse("2026-09-18T15:04:00Z"), sessions),
        )
    }

    @Test
    fun `no closed session fails`() {
        assertFailsWith<NoSuchElementException> {
            ReferenceSession.lastClosedSessionBefore(Instant.parse("2026-01-01T00:00:00Z"), sessions)
        }
    }
}
