package pl.investor.outcome

import pl.investor.history.DailyQuote
import pl.investor.support.WARSAW
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OutcomeCalculatorTest {

    private val midnightReport = warsaw("2026-08-06T00:20:21")

    private fun calendar(from: String, to: String): List<LocalDate> =
        generateSequence(LocalDate.parse(from)) { it.plusDays(1) }
            .takeWhile { !it.isAfter(LocalDate.parse(to)) }
            .filterNot { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
            .toList()

    private fun warsaw(dateTime: String): Instant = LocalDateTime.parse(dateTime).atZone(WARSAW).toInstant()

    private fun quotes(instrument: String, sessions: List<LocalDate>, close: (LocalDate) -> Double = { 100.0 }, volume: (LocalDate) -> Long = { 100 }) =
        sessions.map { DailyQuote(instrument, it, BigDecimal.valueOf(close(it)), volume(it), null) }

    private fun closes(vararg pairs: Pair<String, Double>, default: Double = 100.0): (LocalDate) -> Double {
        val byDate = pairs.associate { LocalDate.parse(it.first) to it.second }
        return { byDate[it] ?: default }
    }

    private fun without(vararg dates: String): (LocalDate) -> Boolean {
        val excluded = dates.map(LocalDate::parse).toSet()
        return { it !in excluded }
    }

    @Test
    fun `report just after midnight has the previous day as ref and the same day as base`() {
        val sessions = calendar("2026-07-01", "2026-08-07")
        val stock = quotes("S", sessions, closes("2026-08-04" to 50.0, "2026-08-05" to 100.0, "2026-08-06" to 97.2))

        val outcome = assertNotNull(OutcomeCalculator.calculate(midnightReport, sessions, stock, emptyList()))

        assertEquals(LocalDate.parse("2026-08-06"), outcome.baseDate)
        assertEquals(-2.8, outcome.reactionPct!!, 1e-9)
    }

    @Test
    fun `report after the close has the same day as ref and the next day as base`() {
        val sessions = calendar("2026-07-01", "2026-07-31")
        val stock = quotes("S", sessions, closes("2026-07-28" to 50.0, "2026-07-29" to 100.0, "2026-07-30" to 105.0))

        val outcome = assertNotNull(OutcomeCalculator.calculate(warsaw("2026-07-29T17:14:57"), sessions, stock, emptyList()))

        assertEquals(LocalDate.parse("2026-07-30"), outcome.baseDate)
        assertEquals(5.0, outcome.reactionPct!!, 1e-9)
    }

    @Test
    fun `report during a session has that session as base`() {
        val sessions = calendar("2026-08-01", "2026-09-16")

        val outcome = assertNotNull(OutcomeCalculator.calculate(warsaw("2026-09-15T12:00:00"), sessions, quotes("S", sessions), emptyList()))

        assertEquals(LocalDate.parse("2026-09-15"), outcome.baseDate)
    }

    @Test
    fun `reaction is measured for the stock and the index`() {
        val sessions = calendar("2026-07-01", "2026-08-06")
        val stock = quotes("S", sessions, closes("2026-08-05" to 100.0, "2026-08-06" to 97.2))
        val index = quotes("WIG", sessions, closes("2026-08-05" to 2000.0, "2026-08-06" to 2020.0, default = 2000.0))

        val outcome = assertNotNull(OutcomeCalculator.calculate(midnightReport, sessions, stock, index))

        assertEquals(-2.8, outcome.reactionPct!!, 1e-9)
        assertEquals(1.0, outcome.reactionIndexPct!!, 1e-9)
    }

    @Test
    fun `ref close is carried forward from the latest earlier quote`() {
        val sessions = calendar("2026-07-01", "2026-08-06")
        val stock = quotes("S", sessions.filter(without("2026-08-05")), closes("2026-08-04" to 80.0, "2026-08-06" to 88.0))

        val outcome = assertNotNull(OutcomeCalculator.calculate(midnightReport, sessions, stock, emptyList()))

        assertEquals(10.0, outcome.reactionPct!!, 1e-9)
    }

    @Test
    fun `volume multiple uses only the 20 sessions ending at ref and counts a missing quote as zero`() {
        val sessions = calendar("2026-07-01", "2026-08-06")
        val volumes = mapOf(LocalDate.parse("2026-08-06") to 300L, LocalDate.parse("2026-07-08") to 1_000_000L)
        val stock = quotes("S", sessions.filter(without("2026-07-20")), volume = { volumes[it] ?: 100 })

        val outcome = assertNotNull(OutcomeCalculator.calculate(midnightReport, sessions, stock, emptyList()))

        assertEquals(300.0 / 95.0, outcome.volumeMultiple!!, 1e-9)
    }

    @Test
    fun `volume multiple is null when the mean volume is zero`() {
        val sessions = calendar("2026-07-01", "2026-08-06")
        val stock = quotes("S", sessions, volume = { if (it == LocalDate.parse("2026-08-06")) 300 else 0 })

        val outcome = assertNotNull(OutcomeCalculator.calculate(midnightReport, sessions, stock, emptyList()))

        assertNull(outcome.volumeMultiple)
    }

    @Test
    fun `missing stock quote on base gives no outcome`() {
        val sessions = calendar("2026-07-01", "2026-08-14")
        val stock = quotes("S", sessions.filter(without("2026-08-06")))

        assertNull(OutcomeCalculator.calculate(midnightReport, sessions, stock, quotes("WIG", sessions)))
    }

    @Test
    fun `only horizons present in the calendar are computed`() {
        val sessions = calendar("2026-07-01", "2026-08-14")
        val stock = quotes("S", sessions, closes("2026-08-06" to 100.0, "2026-08-13" to 110.0))
        val index = quotes("WIG", sessions, closes("2026-08-06" to 2000.0, "2026-08-13" to 1900.0, default = 2000.0))

        val outcome = assertNotNull(OutcomeCalculator.calculate(midnightReport, sessions, stock, index))

        assertEquals(10.0, outcome.return5Pct!!, 1e-9)
        assertEquals(-5.0, outcome.index5Pct!!, 1e-9)
        assertNull(outcome.return20Pct)
        assertNull(outcome.index20Pct)
        assertNull(outcome.return60Pct)
        assertNull(outcome.index60Pct)
    }

    @Test
    fun `all horizons are computed once the calendar reaches 60 sessions after base`() {
        val sessions = calendar("2026-07-01", "2026-11-30")
        val stock = quotes("S", sessions, closes("2026-08-06" to 100.0, "2026-09-03" to 120.0, "2026-10-29" to 150.0))
        val index = quotes("WIG", sessions, closes("2026-10-29" to 110.0))

        val outcome = assertNotNull(OutcomeCalculator.calculate(midnightReport, sessions, stock, index))

        assertEquals(20.0, outcome.return20Pct!!, 1e-9)
        assertEquals(50.0, outcome.return60Pct!!, 1e-9)
        assertEquals(10.0, outcome.index60Pct!!, 1e-9)
    }

    @Test
    fun `holiday missing from the index calendar counts as a session when the stock traded`() {
        val wig = calendar("2026-07-01", "2026-09-10").filter(without("2026-08-10"))
        val stock = quotes("S", calendar("2026-07-01", "2026-09-10"), closes("2026-08-13" to 110.0, "2026-08-14" to 120.0, "2026-09-03" to 140.0, "2026-09-04" to 150.0))
        val index = quotes("WIG", wig)

        val outcome = assertNotNull(OutcomeCalculator.calculate(midnightReport, wig, stock, index))

        assertEquals(10.0, outcome.return5Pct!!, 1e-9)
        assertEquals(40.0, outcome.return20Pct!!, 1e-9)
        assertEquals(0.0, outcome.index5Pct!!, 1e-9)
    }

    @Test
    fun `missing stock quote on the horizon session leaves both horizon fields null`() {
        val sessions = calendar("2026-07-01", "2026-09-10")
        val stock = quotes("S", sessions.filter(without("2026-08-13")))

        val outcome = assertNotNull(OutcomeCalculator.calculate(midnightReport, sessions, stock, quotes("WIG", sessions)))

        assertNull(outcome.return5Pct)
        assertNull(outcome.index5Pct)
        assertEquals(0.0, outcome.return20Pct!!, 1e-9)
    }

    @Test
    fun `missing index quote on the horizon session leaves both horizon fields null`() {
        val sessions = calendar("2026-07-01", "2026-11-30")
        val index = quotes("WIG", sessions.filter(without("2026-10-29")))

        val outcome = assertNotNull(OutcomeCalculator.calculate(midnightReport, sessions, quotes("S", sessions), index))

        assertNull(outcome.return60Pct)
        assertNull(outcome.index60Pct)
        assertEquals(0.0, outcome.index20Pct!!, 1e-9)
    }

    @Test
    fun `without index quotes the stock reaction is known but index fields and horizons are null`() {
        val sessions = calendar("2026-07-01", "2026-08-14")
        val stock = quotes("S", sessions, closes("2026-08-05" to 100.0, "2026-08-06" to 97.2))

        val outcome = assertNotNull(OutcomeCalculator.calculate(midnightReport, sessions, stock, emptyList()))

        assertEquals(-2.8, outcome.reactionPct!!, 1e-9)
        assertNull(outcome.reactionIndexPct)
        assertNull(outcome.return5Pct)
        assertNull(outcome.index5Pct)
    }

    @Test
    fun `no base session in the calendar yet gives no outcome`() {
        val sessions = calendar("2026-07-01", "2026-08-05")

        assertNull(OutcomeCalculator.calculate(midnightReport, sessions, quotes("S", sessions), emptyList()))
    }

    @Test
    fun `no session closed before publication gives no outcome`() {
        val sessions = calendar("2026-08-06", "2026-08-21")

        assertNull(OutcomeCalculator.calculate(midnightReport, sessions, quotes("S", sessions), quotes("WIG", sessions)))
    }
}
