package pl.investor.source.consensus

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.scoring.Score
import pl.investor.scoring.ScoreComponents
import pl.investor.scoring.ScoreRepository
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.support.PostgresTestBase
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConsensusJobTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var scores: ScoreRepository
    @Autowired lateinit var notes: ConsensusRepository

    private val now = Instant.parse("2026-08-06T05:15:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val client = mockk<ConsensusClient>()
    private val job by lazy { ConsensusJob(notes, companies, client, clock) }
    private val published = Instant.parse("2026-08-05T22:20:21Z")
    private val window = Duration.ofHours(36)
    private val note = ConsensusNote(
        "https://www.bankier.pl/wiadomosc/x-1.html",
        Instant.parse("2026-08-06T04:46:00Z"),
        listOf(
            ConsensusRow("Przychody", BigDecimal("1696.7"), BigDecimal("1686.3"), 0.6),
            ConsensusRow("EBITDA", BigDecimal("66.4"), BigDecimal("68.6"), -3.1),
        ),
    )

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company RESTART IDENTITY CASCADE").update()
        every { client.find(any(), any()) } answers { secondArg<List<NoteWindow>>().associateWith { note } }
    }

    private fun company(name: String, market: Market = Market.GPW): Company =
        companies.save(Company(null, name, "ISIN-$name", market, null, null, false))

    private fun report(
        company: Company,
        publishedAt: Instant = published,
        kind: ReportKind = ReportKind.HALF_YEARLY,
        title: String? = null,
        status: ReportStatus = ReportStatus.SCORED,
        scored: Boolean = true,
    ): Long {
        val id = checkNotNull(
            reports.saveIfNew(
                Report(
                    null, checkNotNull(company.id), company.market, kind, "1/2026", "ref-${company.id}-$publishedAt-$kind", title,
                    publishedAt, publishedAt, "https://example.test/${company.id}", status,
                )
            )?.id
        )
        if (scored) scores.save(id, Score(75.0, ScoreComponents(10.0, 10.0, 10.0, 10.0, 10.0, 25.0), 0.0, emptySet()), "Powód.", publishedAt)
        return id
    }

    @Test
    fun `runs hourly outside the evening publication peak`() {
        val scheduled = checkNotNull(ConsensusJob::class.java.getMethod("refresh").getAnnotation(Scheduled::class.java))

        assertEquals("0 15 6-16,19-23 * * *", scheduled.cron)
        assertEquals("Europe/Warsaw", scheduled.zone)
    }

    @Test
    fun `scored GPW half-year report gets its note saved`() {
        val asbis = company("ASBISC ENTERPRISES PLC")
        val id = report(asbis)

        job.refresh()

        assertEquals(note, notes.find(id))
        verify { client.find(match { it.id == asbis.id }, listOf(NoteWindow(published, published.plus(window)))) }
    }

    @Test
    fun `two reports of one company are looked up in a single call`() {
        val asbis = company("ASBIS")
        val first = report(asbis)
        val second = report(asbis, publishedAt = published.plusSeconds(60), kind = ReportKind.CURRENT, title = "Wstępne wyniki")

        job.refresh()

        verify(exactly = 1) { client.find(any(), any()) }
        verify {
            client.find(
                match { it.id == asbis.id },
                listOf(NoteWindow(published, published.plus(window)), NoteWindow(published.plusSeconds(60), published.plusSeconds(60).plus(window))),
            )
        }
        assertNotNull(notes.find(first))
        assertNotNull(notes.find(second))
    }

    @Test
    fun `current report with results in the title is checked`() {
        val id = report(company("XTB"), kind = ReportKind.CURRENT, title = "Wstępne WYNIKI finansowe za I półrocze 2026 roku")

        job.refresh()

        assertNotNull(notes.find(id))
    }

    @Test
    fun `push pending report with a score is checked`() {
        val id = report(company("PENDING"), status = ReportStatus.PUSH_PENDING)

        job.refresh()

        assertNotNull(notes.find(id))
    }

    @Test
    fun `scored report without a score row is skipped`() {
        val id = report(company("NOSCORE"), scored = false)

        job.refresh()

        assertNull(notes.find(id))
        verify(exactly = 0) { client.find(any(), any()) }
    }

    @Test
    fun `NewConnect report is skipped`() {
        val id = report(company("NC CO", Market.NC))

        job.refresh()

        assertNull(notes.find(id))
    }

    @Test
    fun `current monthly sales report is skipped`() {
        val id = report(company("ASBIS"), kind = ReportKind.CURRENT, title = "Szacunkowe przychody ze sprzedaży za lipiec 2026")

        job.refresh()

        assertNull(notes.find(id))
    }

    @Test
    fun `report older than 36 hours is skipped`() {
        val id = report(company("OLD"), publishedAt = now.minus(window).minusSeconds(60))

        job.refresh()

        assertNull(notes.find(id))
    }

    @Test
    fun `unscored report is skipped`() {
        val id = report(company("UNSCORED"), status = ReportStatus.UNSCORED_NO_DATA, scored = false)

        job.refresh()

        assertNull(notes.find(id))
    }

    @Test
    fun `report that already has a note is not looked up again`() {
        val id = report(company("ASBIS"))
        notes.save(id, note)

        job.refresh()

        verify(exactly = 0) { client.find(any(), any()) }
    }

    @Test
    fun `report without a published note stays pending`() {
        every { client.find(any(), any()) } returns emptyMap()
        val id = report(company("SMALL"))

        job.refresh()

        assertNull(notes.find(id))
        assertEquals(listOf(id), notes.pending(now.minus(window)).map { it.reportId })
    }

    @Test
    fun `failure for one company does not stop the next`() {
        val broken = company("BROKEN")
        val unavailable = company("UNAVAILABLE")
        val healthy = company("HEALTHY")
        every { client.find(match { it.id == broken.id }, any()) } throws IllegalStateException("boom")
        every { client.find(match { it.id == unavailable.id }, any()) } throws SourceUnavailableException(Source.BANKIER_NEWS, RuntimeException("down"))
        val brokenId = report(broken)
        val unavailableId = report(unavailable, publishedAt = published.plusSeconds(30))
        val healthyId = report(healthy, publishedAt = published.plusSeconds(60))

        job.refresh()

        assertNull(notes.find(brokenId))
        assertNull(notes.find(unavailableId))
        assertNotNull(notes.find(healthyId))
    }

    @Test
    fun `saving the same note twice keeps one row`() {
        val id = report(company("ASBIS"))

        notes.save(id, note)
        notes.save(id, note.copy(url = "https://www.bankier.pl/wiadomosc/x-2.html"))

        assertEquals("https://www.bankier.pl/wiadomosc/x-2.html", notes.find(id)?.url)
        assertEquals(1, jdbc.sql("SELECT count(*) FROM consensus_note WHERE report_id = :id").param("id", id).query(Int::class.java).single())
    }
}
