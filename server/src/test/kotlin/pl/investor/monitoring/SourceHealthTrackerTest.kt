package pl.investor.monitoring

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.source.Source
import pl.investor.support.PostgresTestBase
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceHealthTrackerTest : PostgresTestBase() {

    @Autowired
    lateinit var tracker: SourceHealthTracker

    @Autowired
    lateinit var jdbc: JdbcClient

    @BeforeTest
    fun clean() {
        jdbc.sql("DELETE FROM source_health").update()
    }

    private fun health(source: Source) = tracker.snapshot().single { it.source == source }

    @Test
    fun `records success and failure`() {
        val ok = Instant.parse("2026-10-30T07:00:00Z")
        val bad = Instant.parse("2026-10-30T07:05:00Z")

        tracker.recordSuccess(Source.GPW_LISTING, ok)
        tracker.recordFailure(Source.GPW_LISTING, bad, "boom")

        assertEquals(
            SourceHealth(Source.GPW_LISTING, ok, bad, "boom", false),
            health(Source.GPW_LISTING),
        )
    }

    @Test
    fun `failure without prior success keeps last success null`() {
        tracker.recordFailure(Source.NC_LISTING, Instant.parse("2026-10-30T07:05:00Z"), "boom")

        assertEquals(null, health(Source.NC_LISTING).lastSuccessAt)
    }

    @Test
    fun `success after outage clears the notified flag`() {
        tracker.recordSuccess(Source.GPW_LISTING, Instant.parse("2026-10-30T07:00:00Z"))
        tracker.markOutageNotified(Source.GPW_LISTING)
        assertTrue(health(Source.GPW_LISTING).outageNotified)

        tracker.recordSuccess(Source.GPW_LISTING, Instant.parse("2026-10-30T07:50:00Z"))

        assertFalse(health(Source.GPW_LISTING).outageNotified)
    }
}
