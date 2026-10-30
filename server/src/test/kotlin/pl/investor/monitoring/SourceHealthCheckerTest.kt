package pl.investor.monitoring

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.notification.PushMessageFactory
import pl.investor.notification.PushNotifier
import pl.investor.source.Source
import pl.investor.support.PostgresTestBase
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceHealthCheckerTest : PostgresTestBase() {

    @Autowired lateinit var tracker: SourceHealthTracker
    @Autowired lateinit var jdbc: JdbcClient

    private val notifier = mockk<PushNotifier>(relaxed = true)
    private var now = Instant.parse("2026-09-21T15:00:00Z")
    private val clock = mockk<Clock> { every { instant() } answers { now } }

    private fun checker() = SourceHealthChecker(tracker, notifier, PushMessageFactory(), clock)

    @BeforeEach
    fun clean() {
        jdbc.sql("DELETE FROM source_health").update()
    }

    @Test
    fun `outage longer than 30 minutes inside a window pushes once`() {
        tracker.recordSuccess(Source.GPW_LISTING, Instant.parse("2026-09-21T14:00:00Z"))

        checker().check()
        checker().check()

        verify(exactly = 1) {
            notifier.send(match { it.title == "Źródło GPW_LISTING nie działa" && it.body == "Brak danych od 16:00" })
        }
    }

    @Test
    fun `outage outside the publication windows is not reported`() {
        now = Instant.parse("2026-09-21T11:00:00Z")
        tracker.recordSuccess(Source.GPW_LISTING, Instant.parse("2026-09-21T09:00:00Z"))

        checker().check()

        verify(exactly = 0) { notifier.send(any()) }
    }

    @Test
    fun `a gap shorter than 30 minutes is not an outage`() {
        tracker.recordSuccess(Source.NC_LISTING, Instant.parse("2026-09-21T14:40:00Z"))

        checker().check()

        verify(exactly = 0) { notifier.send(any()) }
    }

    @Test
    fun `a success between the check and the mark leaves the flag unset and sends nothing`() {
        tracker.recordSuccess(Source.GPW_LISTING, Instant.parse("2026-09-21T14:00:00Z"))
        val racing = spyk(tracker)
        every { racing.snapshot() } answers {
            callOriginal().also { tracker.recordSuccess(Source.GPW_LISTING, Instant.parse("2026-09-21T14:59:00Z")) }
        }

        SourceHealthChecker(racing, notifier, PushMessageFactory(), clock).check()

        verify(exactly = 0) { notifier.send(any()) }
        assertFalse(tracker.snapshot().single { it.source == Source.GPW_LISTING }.outageNotified)
    }

    @Test
    fun `the flag stays set when the push fails`() {
        tracker.recordSuccess(Source.GPW_LISTING, Instant.parse("2026-09-21T14:00:00Z"))
        every { notifier.send(any()) } throws IllegalStateException("fcm down")

        runCatching { checker().check() }
        checker().check()

        verify(exactly = 1) { notifier.send(any()) }
        assertTrue(tracker.snapshot().single { it.source == Source.GPW_LISTING }.outageNotified)
    }

    @Test
    fun `a failed push does not stop checking the next source`() {
        tracker.recordSuccess(Source.GPW_LISTING, Instant.parse("2026-09-21T14:00:00Z"))
        tracker.recordSuccess(Source.NC_LISTING, Instant.parse("2026-09-21T14:00:00Z"))
        every { notifier.send(match { it.title.contains("GPW_LISTING") }) } throws IllegalStateException("fcm down")

        checker().check()

        verify(exactly = 1) { notifier.send(match { it.title == "Źródło NC_LISTING nie działa" }) }
    }

    @Test
    fun `success after an outage clears the flag so a new outage is reported again`() {
        tracker.recordSuccess(Source.GPW_LISTING, Instant.parse("2026-09-21T14:00:00Z"))
        checker().check()

        tracker.recordSuccess(Source.GPW_LISTING, Instant.parse("2026-09-21T15:01:00Z"))
        assertFalse(tracker.snapshot().single { it.source == Source.GPW_LISTING }.outageNotified)

        now = Instant.parse("2026-09-21T15:40:00Z")
        checker().check()

        verify(exactly = 2) { notifier.send(any()) }
    }
}
