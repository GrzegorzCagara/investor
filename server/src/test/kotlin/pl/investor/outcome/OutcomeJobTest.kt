package pl.investor.outcome

import io.mockk.every
import io.mockk.spyk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.history.DailyQuote
import pl.investor.history.QuoteRepository
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.scoring.ScoreRepository
import pl.investor.support.PostgresTestBase
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutcomeJobTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var scores: ScoreRepository
    @Autowired lateinit var quotes: QuoteRepository
    @Autowired lateinit var outcomes: OutcomeRepository

    private val clock = Clock.fixed(Instant.parse("2026-10-30T21:00:00Z"), ZoneOffset.UTC)
    private val data by lazy { OutcomeTestData(companies, reports, scores, quotes) }
    private val job by lazy { OutcomeJob(outcomes, quotes, clock) }
    private val published = Instant.parse("2026-09-15T10:00:00Z")
    private val reactionDay = LocalDate.parse("2026-09-15")
    private val early = Instant.parse("2026-07-15T10:00:00Z")
    private val earlyHorizon60 = LocalDate.parse("2026-10-07")

    private fun pendingIds() = outcomes.pending(clock.instant().minus(Duration.ofDays(120))).map { it.reportId }

    private fun deleteQuote(instrument: String, date: LocalDate) {
        jdbc.sql("DELETE FROM daily_quote WHERE instrument = :instrument AND date = :date")
            .param("instrument", instrument)
            .param("date", date)
            .update()
    }

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company, daily_quote RESTART IDENTITY CASCADE").update()
        data.quotes("WIG", "2026-05-01", "2026-10-30") { if (it >= reactionDay) 2020.0 else 2000.0 }
        data.quotes("NCINDEX", "2026-05-01", "2026-10-30") { if (it >= reactionDay) 950.0 else 1000.0 }
        data.quotes("PLGPW0000001", "2026-05-01", "2026-10-30", volume = { if (it == reactionDay) 250 else 100 }) { if (it >= reactionDay) 97.2 else 100.0 }
        data.quotes("PLNC00000001", "2026-05-01", "2026-10-30") { if (it >= reactionDay) 110.0 else 100.0 }
    }

    @Test
    fun `scored report gets an outcome`() {
        val id = data.report(data.company("GPW CO", "PLGPW0000001", Market.GPW), Market.GPW, published)

        job.refresh()

        val outcome = assertNotNull(outcomes.find(id))
        assertEquals(reactionDay, outcome.baseDate)
        assertEquals(-2.8, outcome.reactionPct!!, 1e-4)
        assertEquals(1.0, outcome.reactionIndexPct!!, 1e-4)
        assertEquals(2.5, outcome.volumeMultiple!!, 1e-9)
        assertEquals(0.0, outcome.return20Pct!!, 1e-4)
        assertNull(outcome.return60Pct)
    }

    @Test
    fun `report older than 120 days is ignored`() {
        val id = data.report(data.company("GPW CO", "PLGPW0000001", Market.GPW), Market.GPW, Instant.parse("2026-06-15T10:00:00Z"))

        job.refresh()

        assertNull(outcomes.find(id))
    }

    @Test
    fun `complete outcome is not recomputed while an incomplete one is`() {
        val company = data.company("GPW CO", "PLGPW0000001", Market.GPW)
        val complete = data.report(company, Market.GPW, published)
        val incomplete = data.report(company, Market.GPW, published.plusSeconds(60))
        val stale = Outcome(reactionDay, 99.0, null, null, null, null, null, null, null, null)
        outcomes.save(complete, stale.copy(return60Pct = 5.0, index60Pct = 1.0), published)
        outcomes.save(incomplete, stale, published)

        job.refresh()

        assertEquals(99.0, outcomes.find(complete)!!.reactionPct)
        assertEquals(-2.8, outcomes.find(incomplete)!!.reactionPct!!, 1e-4)
    }

    @Test
    fun `unscored and irrelevant reports are ignored`() {
        val company = data.company("GPW CO", "PLGPW0000001", Market.GPW)
        val irrelevant = data.report(company, Market.GPW, published, ReportStatus.IRRELEVANT, scored = false)
        val unscored = data.report(company, Market.GPW, published.plusSeconds(60), ReportStatus.UNSCORED_NO_DATA, scored = false)

        job.refresh()

        assertNull(outcomes.find(irrelevant))
        assertNull(outcomes.find(unscored))
    }

    @Test
    fun `NewConnect report is compared with NCINDEX`() {
        val id = data.report(data.company("NC CO", "PLNC00000001", Market.NC), Market.NC, published)

        job.refresh()

        val outcome = assertNotNull(outcomes.find(id))
        assertEquals(10.0, outcome.reactionPct!!, 1e-4)
        assertEquals(-5.0, outcome.reactionIndexPct!!, 1e-4)
    }

    @Test
    fun `US report is skipped`() {
        val id = data.report(data.company("US CO", "US0000000001", Market.US), Market.US, published)

        job.refresh()

        assertNull(outcomes.find(id))
    }

    @Test
    fun `a failing report does not stop the next one`() {
        val broken = data.report(data.company("BROKEN", "PLBROKEN0001", Market.GPW), Market.GPW, published)
        val healthy = data.report(data.company("GPW CO", "PLGPW0000001", Market.GPW), Market.GPW, published.plusSeconds(60))
        val failingQuotes = spyk(QuoteRepository(jdbc))
        every { failingQuotes.between("PLBROKEN0001", any(), any()) } throws IllegalStateException("boom")

        OutcomeJob(outcomes, failingQuotes, clock).refresh()

        assertNull(outcomes.find(broken))
        assertNotNull(outcomes.find(healthy))
    }

    @Test
    fun `missing stock quote on base plus 5 leaves return5 null and keeps the report pending`() {
        val id = data.report(data.company("GPW CO", "PLGPW0000001", Market.GPW), Market.GPW, published)
        deleteQuote("PLGPW0000001", LocalDate.parse("2026-09-22"))

        job.refresh()

        val outcome = assertNotNull(outcomes.find(id))
        assertNull(outcome.return5Pct)
        assertNull(outcome.index5Pct)
        assertNotNull(outcome.return20Pct)
        assertTrue(id in pendingIds())
    }

    @Test
    fun `report with all horizons known is complete`() {
        val id = data.report(data.company("GPW CO", "PLGPW0000001", Market.GPW), Market.GPW, early)

        job.refresh()

        val outcome = assertNotNull(outcomes.find(id))
        assertEquals(-2.8, outcome.return60Pct!!, 1e-4)
        assertEquals(1.0, outcome.index60Pct!!, 1e-4)
        assertFalse(id in pendingIds())
    }

    @Test
    fun `missing index quote on base plus 60 keeps the report pending`() {
        val id = data.report(data.company("GPW CO", "PLGPW0000001", Market.GPW), Market.GPW, early)
        deleteQuote("WIG", earlyHorizon60)

        job.refresh()

        val outcome = assertNotNull(outcomes.find(id))
        assertNull(outcome.return60Pct)
        assertNull(outcome.index60Pct)
        assertTrue(id in pendingIds())
    }

    @Test
    fun `recompute all refreshes a complete outcome after a quote changes while the default run does not`() {
        val id = data.report(data.company("GPW CO", "PLGPW0000001", Market.GPW), Market.GPW, early)
        job.refresh()
        quotes.save(DailyQuote("PLGPW0000001", earlyHorizon60, BigDecimal("200"), 100, null))

        job.refresh()
        assertEquals(-2.8, outcomes.find(id)!!.return60Pct!!, 1e-4)

        job.refresh(recomputeAll = true)
        assertEquals(100.0, outcomes.find(id)!!.return60Pct!!, 1e-4)
    }
}
