package pl.investor.monitoring

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import pl.investor.report.Market
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class PublicationWindowsTest {

    private fun open(date: String, time: String, market: Market) =
        PublicationWindows.isOpen(
            LocalDateTime.parse("${date}T$time").atZone(ZoneId.of("Europe/Warsaw")).toInstant(),
            market,
        )

    @ParameterizedTest
    @CsvSource(
        "2026-01-15, 00:00, true",
        "2026-01-15, 00:59, true",
        "2026-01-15, 01:00, false",
        "2026-01-15, 06:59, false",
        "2026-01-15, 07:00, true",
        "2026-01-15, 08:59, true",
        "2026-01-15, 09:00, false",
        "2026-01-15, 16:29, false",
        "2026-01-15, 16:30, true",
        "2026-01-15, 23:59, true",
        "2026-07-15, 00:00, true",
        "2026-07-15, 00:59, true",
        "2026-07-15, 01:00, false",
        "2026-07-15, 06:59, false",
        "2026-07-15, 07:00, true",
        "2026-07-15, 08:59, true",
        "2026-07-15, 09:00, false",
        "2026-07-15, 16:29, false",
        "2026-07-15, 16:30, true",
        "2026-07-15, 23:59, true",
    )
    fun `GPW and NC windows follow Warsaw time in winter and summer`(date: String, time: String, expected: Boolean) {
        assertEquals(expected, open(date, time, Market.GPW))
        assertEquals(expected, open(date, time, Market.NC))
    }

    @Test
    fun `US is never open yet`() {
        assertFalse(open("2026-01-15", "07:30", Market.US))
    }
}
