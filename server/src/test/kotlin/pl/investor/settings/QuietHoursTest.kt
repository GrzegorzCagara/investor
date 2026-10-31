package pl.investor.settings

import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuietHoursTest {

    private fun inMemorySettings(vararg initial: Pair<String, String>): SettingRepository {
        val values = mutableMapOf(*initial)
        return mockk {
            every { find(any()) } answers { values[firstArg()] }
            every { put(any(), any()) } answers { values[firstArg()] = secondArg() }
        }
    }

    private fun quietHours(enabled: Boolean, from: String, to: String) =
        QuietHours(inMemorySettings()).apply { update(QuietHoursConfig(enabled, LocalTime.parse(from), LocalTime.parse(to))) }

    private fun warsaw(dateTime: String): Instant = Instant.parse("${dateTime}+02:00")

    @Test
    fun `defaults are off from 22 to 7`() {
        val quietHours = QuietHours(inMemorySettings())

        assertEquals(QuietHoursConfig(false, LocalTime.of(22, 0), LocalTime.of(7, 0)), quietHours.current())
        assertFalse(quietHours.isActive(warsaw("2026-09-21T23:30:00")))
    }

    @Test
    fun `update and read round trip`() {
        val quietHours = quietHours(true, "23:15", "06:45")

        assertEquals(QuietHoursConfig(true, LocalTime.of(23, 15), LocalTime.of(6, 45)), quietHours.current())
    }

    @Test
    fun `window wraps midnight with inclusive start and exclusive end`() {
        val quietHours = quietHours(true, "22:00", "07:00")

        assertFalse(quietHours.isActive(warsaw("2026-09-21T21:59:00")))
        assertTrue(quietHours.isActive(warsaw("2026-09-21T22:00:00")))
        assertTrue(quietHours.isActive(warsaw("2026-09-21T23:30:00")))
        assertTrue(quietHours.isActive(warsaw("2026-09-22T00:00:00")))
        assertTrue(quietHours.isActive(warsaw("2026-09-22T06:59:00")))
        assertFalse(quietHours.isActive(warsaw("2026-09-22T07:00:00")))
        assertFalse(quietHours.isActive(warsaw("2026-09-22T12:00:00")))
    }

    @Test
    fun `same day window`() {
        val quietHours = quietHours(true, "12:00", "14:00")

        assertFalse(quietHours.isActive(warsaw("2026-09-21T11:59:00")))
        assertTrue(quietHours.isActive(warsaw("2026-09-21T12:00:00")))
        assertTrue(quietHours.isActive(warsaw("2026-09-21T13:59:00")))
        assertFalse(quietHours.isActive(warsaw("2026-09-21T14:00:00")))
        assertFalse(quietHours.isActive(warsaw("2026-09-21T23:00:00")))
    }

    @Test
    fun `equal start and end is never active`() {
        val quietHours = quietHours(true, "07:00", "07:00")

        assertFalse(quietHours.isActive(warsaw("2026-09-21T07:00:00")))
        assertFalse(quietHours.isActive(warsaw("2026-09-21T03:00:00")))
        assertFalse(quietHours.isActive(warsaw("2026-09-21T20:00:00")))
    }

    @Test
    fun `DST change day uses Warsaw wall time`() {
        val quietHours = quietHours(true, "22:00", "07:00")

        assertTrue(quietHours.isActive(Instant.parse("2026-10-25T05:30:00Z")))
        assertFalse(quietHours.isActive(Instant.parse("2026-10-25T06:00:00Z")))
        assertFalse(quietHours.isActive(Instant.parse("2026-10-24T05:30:00Z")))
        assertTrue(quietHours.isActive(Instant.parse("2026-10-24T04:59:00Z")))
    }
}
