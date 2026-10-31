package pl.investor.outcome

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.history.QuoteRepository
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.scoring.ScoreRepository
import pl.investor.support.PostgresTestBase
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutcomeRepositoryTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var scores: ScoreRepository
    @Autowired lateinit var quotes: QuoteRepository
    @Autowired lateinit var outcomes: OutcomeRepository

    private val data by lazy { OutcomeTestData(companies, reports, scores, quotes) }
    private val since = Instant.parse("2026-07-02T00:00:00Z")
    private val published = Instant.parse("2026-09-15T10:00:00Z")
    private var gpwId = 0L

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company, daily_quote RESTART IDENTITY CASCADE").update()
        gpwId = data.company("ASBIS", "CY0101562015", Market.GPW)
    }

    @Test
    fun `saved outcome is found and upserted`() {
        val id = data.report(gpwId, Market.GPW, published)

        outcomes.save(id, outcome(return60Pct = null), published)
        outcomes.save(id, outcome(return60Pct = 12.5), published)

        assertEquals(outcome(return60Pct = 12.5), outcomes.find(id))
        assertNull(outcomes.find(id + 100))
    }

    @Test
    fun `findAll returns outcomes keyed by report id`() {
        val first = data.report(gpwId, Market.GPW, published)
        val second = data.report(gpwId, Market.GPW, published.plusSeconds(60))
        outcomes.save(first, outcome(return60Pct = 1.0), published)

        assertEquals(mapOf(first to outcome(return60Pct = 1.0)), outcomes.findAll(listOf(first, second)))
        assertEquals(emptyMap(), outcomes.findAll(emptyList()))
    }

    @Test
    fun `pending lists scored results without a complete outcome`() {
        val fresh = data.report(gpwId, Market.GPW, published)
        val pushPending = data.report(gpwId, Market.GPW, published.plusSeconds(60), ReportStatus.PUSH_PENDING)
        val incomplete = data.report(gpwId, Market.GPW, published.plusSeconds(120))
        val complete = data.report(gpwId, Market.GPW, published.plusSeconds(180))
        val indexMissing = data.report(gpwId, Market.GPW, published.plusSeconds(420))
        data.report(gpwId, Market.GPW, Instant.parse("2026-06-15T10:00:00Z"))
        data.report(gpwId, Market.GPW, published.plusSeconds(240), ReportStatus.IRRELEVANT, scored = false)
        data.report(gpwId, Market.GPW, published.plusSeconds(300), ReportStatus.UNSCORED_NO_DATA, scored = false)
        data.report(gpwId, Market.GPW, published.plusSeconds(360), ReportStatus.SCORED, scored = false)
        data.report(data.company("NO ISIN", null, Market.NC), Market.NC, published)
        outcomes.save(incomplete, outcome(return60Pct = null), published)
        outcomes.save(complete, outcome(return60Pct = 3.0), published)
        outcomes.save(indexMissing, outcome(return60Pct = 3.0).copy(index60Pct = null), published)

        val pending = outcomes.pending(since)

        assertEquals(setOf(fresh, pushPending, incomplete, indexMissing), pending.map { it.reportId }.toSet())
        assertEquals(setOf(fresh, pushPending, incomplete, indexMissing, complete), outcomes.pending(since, includeComplete = true).map { it.reportId }.toSet())
        assertEquals(PendingOutcome(fresh, "CY0101562015", Market.GPW, published), pending.single { it.reportId == fresh })
    }

    private fun outcome(return60Pct: Double?) = Outcome(
        baseDate = LocalDate.parse("2026-09-15"),
        reactionPct = -2.8,
        reactionIndexPct = 1.0,
        volumeMultiple = 3.0,
        return5Pct = 10.0,
        index5Pct = -5.0,
        return20Pct = null,
        index20Pct = null,
        return60Pct = return60Pct,
        index60Pct = return60Pct?.let { 0.5 },
    )
}
