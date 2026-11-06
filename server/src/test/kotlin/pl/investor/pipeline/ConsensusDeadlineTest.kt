package pl.investor.pipeline

import org.junit.jupiter.api.Test
import pl.investor.support.WARSAW
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConsensusDeadlineTest {

    private fun warsaw(value: String): Instant = LocalDateTime.parse(value).atZone(WARSAW).toInstant()

    @Test
    fun `weekday evening report waits two hours`() {
        assertEquals(warsaw("2026-11-05T21:43:00"), consensusDeadline(warsaw("2026-11-05T19:43:00")))
    }

    @Test
    fun `Friday evening report waits two hours`() {
        assertEquals(warsaw("2026-11-06T20:30:00"), consensusDeadline(warsaw("2026-11-06T18:30:00")))
    }

    @Test
    fun `early morning report waits until a quarter before the session`() {
        assertEquals(warsaw("2026-11-05T08:45:00"), consensusDeadline(warsaw("2026-11-05T07:30:00")))
    }

    @Test
    fun `late night report waits until a quarter before the session when that comes first`() {
        assertEquals(warsaw("2026-11-06T08:45:00"), consensusDeadline(warsaw("2026-11-06T07:00:00")))
        assertEquals(warsaw("2026-11-06T01:30:00"), consensusDeadline(warsaw("2026-11-05T23:30:00")))
    }

    @Test
    fun `report just before the session does not wait`() {
        assertNull(consensusDeadline(warsaw("2026-11-05T08:50:00")))
    }

    @Test
    fun `report during the session does not wait`() {
        assertNull(consensusDeadline(warsaw("2026-11-05T12:00:00")))
        assertNull(consensusDeadline(warsaw("2026-11-05T09:00:00")))
        assertNull(consensusDeadline(warsaw("2026-11-05T17:04:59")))
    }

    @Test
    fun `report right after the close waits two hours`() {
        assertEquals(warsaw("2026-11-05T19:05:00"), consensusDeadline(warsaw("2026-11-05T17:05:00")))
    }

    @Test
    fun `Saturday report waits two hours`() {
        assertEquals(warsaw("2026-11-07T12:00:00"), consensusDeadline(warsaw("2026-11-07T10:00:00")))
    }

    @Test
    fun `Sunday late evening report waits until a quarter before Monday's session`() {
        assertEquals(warsaw("2026-11-09T08:45:00"), consensusDeadline(warsaw("2026-11-09T07:15:00")))
        assertEquals(warsaw("2026-11-09T00:30:00"), consensusDeadline(warsaw("2026-11-08T22:30:00")))
    }
}
