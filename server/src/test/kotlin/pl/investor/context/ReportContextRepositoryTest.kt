package pl.investor.context

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.EventType
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.scoring.Score
import pl.investor.scoring.ScoreComponents
import pl.investor.scoring.ScoreRepository
import pl.investor.source.consensus.BankierHeadline
import pl.investor.support.PostgresTestBase
import pl.investor.support.toUtc
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportContextRepositoryTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var contexts: ReportContextRepository
    @Autowired lateinit var scores: ScoreRepository

    private val publishedAt = Instant.parse("2026-11-05T18:45:00Z")

    private fun report(
        market: Market = Market.GPW,
        status: ReportStatus = ReportStatus.SCORED,
        eventType: EventType = EventType.RESULTS,
        changedAt: Instant = publishedAt,
        total: Double? = null,
    ): Long {
        val companyId = checkNotNull(companies.save(Company(null, "UNIMOT " + UUID.randomUUID(), null, market, null, null, false)).id)
        val id = checkNotNull(
            reports.saveIfNew(
                Report(null, companyId, market, ReportKind.CURRENT, "45/2026", UUID.randomUUID().toString(), "Wstępne dane", publishedAt, publishedAt, "https://example.test/r", status, eventType = eventType),
            )?.id,
        )
        jdbc.sql("UPDATE report SET status_changed_at = :at WHERE id = :id").param("at", changedAt.toUtc()).param("id", id).update()
        total?.let { scores.save(id, Score(it, ScoreComponents(10.0, 10.0, 10.0, 10.0, 10.0, 10.0), 0.0, emptySet()), "Powód.", changedAt) }
        return id
    }

    private val context = ReportContext(
        points = listOf(
            ContextPoint(ContextDirection.PLUS, "Zysk wyraźnie powyżej oczekiwań analityków."),
            ContextPoint(ContextDirection.MINUS, "Przychody zależą od cen ropy."),
        ),
        headlines = listOf(
            BankierHeadline("Unimot zaskoczył wynikami", "https://www.bankier.pl/wiadomosc/unimot-1.html", Instant.parse("2026-11-05T19:15:00Z")),
        ),
        createdAt = Instant.parse("2026-11-05T19:50:00Z"),
    )

    @Test
    fun `saved context reads back and a second save replaces it`() {
        val id = report()
        assertNull(contexts.find(id))
        assertFalse(contexts.exists(id))

        contexts.save(id, context)
        assertEquals(context, contexts.find(id))
        assertTrue(contexts.exists(id))

        val replaced = context.copy(points = context.points.take(1), headlines = emptyList(), createdAt = Instant.parse("2026-11-06T08:00:00Z"))
        contexts.save(id, replaced)
        assertEquals(replaced, contexts.find(id))
    }

    @Test
    fun `deleting the report deletes its context`() {
        val id = report()
        contexts.save(id, context)

        jdbc.sql("DELETE FROM report WHERE id = :id").param("id", id).update()

        assertNull(contexts.find(id))
    }

    @Test
    fun `due reports are recent scored GPW and NC results of at least the minimum score without a context, oldest first`() {
        jdbc.sql("TRUNCATE company RESTART IDENTITY CASCADE").update()
        val since = Instant.parse("2026-11-05T14:00:00Z")
        val recent = Instant.parse("2026-11-05T19:00:00Z")
        val later = report(changedAt = recent, total = 77.5)
        val earlier = report(market = Market.NC, status = ReportStatus.PUSH_PENDING, changedAt = Instant.parse("2026-11-05T18:00:00Z"), total = 50.0)
        report(changedAt = Instant.parse("2026-11-05T13:00:00Z"), total = 80.0)
        report(changedAt = recent, total = 49.9)
        report(market = Market.SE, changedAt = recent, total = 80.0)
        report(eventType = EventType.NEWS, changedAt = recent, total = 80.0)
        report(status = ReportStatus.AWAITING_CONSENSUS, changedAt = recent, total = 80.0)
        report(changedAt = recent)
        contexts.save(report(changedAt = recent, total = 80.0), context)

        val due = contexts.due(since, 50.0, 10)
        assertEquals(listOf(earlier, later), due)
        assertEquals(listOf(earlier), contexts.due(since, 50.0, 1))
    }
}
