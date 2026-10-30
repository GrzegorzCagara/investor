package pl.investor.pipeline

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.support.PostgresTestBase
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

class PendingReportsJobTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var reports: ReportRepository

    private var now = Instant.parse("2026-09-21T12:00:00Z")
    private val clock = mockk<Clock> { every { instant() } answers { now } }
    private val pipeline = mockk<ReportPipeline>(relaxed = true)
    private val job by lazy { PendingReportsJob(reports, pipeline, clock) }
    private var companyId = 0L

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company RESTART IDENTITY CASCADE").update()
        companyId = checkNotNull(companies.save(Company(null, "ASBISC ENTERPRISES PLC", "CY1000031710", Market.GPW, null, "ASBISC", false)).id)
    }

    private fun report(status: ReportStatus, age: Duration, sourceRef: String = "r1", lastAttemptAt: Instant? = null): Long {
        val id = checkNotNull(
            reports.saveIfNew(
                Report(
                    id = null,
                    companyId = companyId,
                    market = Market.GPW,
                    kind = ReportKind.QUARTERLY,
                    number = "1/2026",
                    sourceRef = sourceRef,
                    title = "Raport kwartalny",
                    publishedAt = now.minus(age),
                    detectedAt = now.minus(age),
                    detailUrl = "https://www.gpw.pl/komunikat?geru_id=$sourceRef",
                    status = status,
                )
            )?.id
        )
        lastAttemptAt?.let { reports.markAttempt(id, it) }
        return id
    }

    private fun statusOf(id: Long) = reports.findById(id).status

    @Test
    fun `NEW report older than 10 minutes is reprocessed`() {
        val id = report(ReportStatus.NEW, Duration.ofMinutes(11))

        job.run()

        verify(exactly = 1) { pipeline.reprocess(id, false) }
    }

    @Test
    fun `NEW report younger than 10 minutes is left alone`() {
        report(ReportStatus.NEW, Duration.ofMinutes(9))

        job.run()

        verify(exactly = 0) { pipeline.reprocess(any(), any()) }
    }

    @Test
    fun `PENDING_ATTACHMENTS younger than 2 hours is reprocessed with attachments required`() {
        val id = report(ReportStatus.PENDING_ATTACHMENTS, Duration.ofMinutes(119))

        job.run()

        verify(exactly = 1) { pipeline.reprocess(id, false) }
    }

    @Test
    fun `PENDING_ATTACHMENTS from 2 hours is reprocessed without attachments`() {
        val id = report(ReportStatus.PENDING_ATTACHMENTS, Duration.ofHours(2))

        job.run()

        verify(exactly = 1) { pipeline.reprocess(id, true) }
    }

    @Test
    fun `PENDING_DATA is checked and the attempt time is stored`() {
        val id = report(ReportStatus.PENDING_DATA, Duration.ofHours(3))
        every { pipeline.retryPendingData(id) } returns true

        job.run()

        verify(exactly = 1) { pipeline.retryPendingData(id) }
        assertEquals(now, reports.findById(id).lastAttemptAt)
    }

    @Test
    fun `PENDING_DATA attempted less than 55 minutes ago is skipped`() {
        report(ReportStatus.PENDING_DATA, Duration.ofHours(3), lastAttemptAt = now.minus(Duration.ofMinutes(54)))

        job.run()

        verify(exactly = 0) { pipeline.retryPendingData(any()) }
    }

    @Test
    fun `PENDING_DATA attempted 55 minutes ago is checked again`() {
        val id = report(ReportStatus.PENDING_DATA, Duration.ofHours(3), lastAttemptAt = now.minus(Duration.ofMinutes(55)))

        job.run()

        verify(exactly = 1) { pipeline.retryPendingData(id) }
    }

    @Test
    fun `PENDING_DATA from 48 hours becomes UNSCORED_NO_DATA`() {
        val id = report(ReportStatus.PENDING_DATA, Duration.ofHours(48))

        job.run()

        assertEquals(ReportStatus.UNSCORED_NO_DATA, statusOf(id))
        verify(exactly = 0) { pipeline.retryPendingData(any()) }
    }

    @Test
    fun `PENDING_AI younger than 2 hours is reprocessed`() {
        val id = report(ReportStatus.PENDING_AI, Duration.ofMinutes(90))

        job.run()

        verify(exactly = 1) { pipeline.reprocess(id, false) }
        assertEquals(ReportStatus.PENDING_AI, statusOf(id))
    }

    @Test
    fun `PENDING_AI from 2 hours becomes FAILED`() {
        val id = report(ReportStatus.PENDING_AI, Duration.ofHours(2))

        job.run()

        assertEquals(ReportStatus.FAILED, statusOf(id))
        verify(exactly = 0) { pipeline.reprocess(any(), any()) }
    }

    @Test
    fun `PUSH_PENDING younger than 24 hours retries the push`() {
        val id = report(ReportStatus.PUSH_PENDING, Duration.ofHours(23))

        job.run()

        verify(exactly = 1) { pipeline.retryPush(id) }
    }

    @Test
    fun `PUSH_PENDING from 24 hours is abandoned as SCORED`() {
        val id = report(ReportStatus.PUSH_PENDING, Duration.ofHours(24))

        job.run()

        assertEquals(ReportStatus.SCORED, statusOf(id))
        verify(exactly = 0) { pipeline.retryPush(any()) }
    }

    @Test
    fun `one failing report does not stop the rest`() {
        val failing = report(ReportStatus.NEW, Duration.ofMinutes(30), "bad")
        val healthy = report(ReportStatus.NEW, Duration.ofMinutes(20), "good")
        every { pipeline.reprocess(failing, any()) } throws IllegalStateException("boom")

        job.run()

        verify(exactly = 1) { pipeline.reprocess(healthy, false) }
    }

    @Test
    fun `PENDING_ATTACHMENTS pushed into PENDING_AI is not failed in the same run`() {
        val id = report(ReportStatus.PENDING_ATTACHMENTS, Duration.ofHours(3))
        every { pipeline.reprocess(id, true) } answers {
            reports.updateStatus(id, ReportStatus.PENDING_AI, now)
            true
        }

        job.run()

        assertEquals(ReportStatus.PENDING_AI, statusOf(id))
        job.run()
        verify(exactly = 1) { pipeline.reprocess(id, false) }
    }

    @Test
    fun `PENDING_DATA pushed into PUSH_PENDING is not abandoned in the same run`() {
        val id = report(ReportStatus.PENDING_DATA, Duration.ofHours(30))
        every { pipeline.retryPendingData(id) } answers {
            reports.updateStatus(id, ReportStatus.PUSH_PENDING, now)
            true
        }

        job.run()

        assertEquals(ReportStatus.PUSH_PENDING, statusOf(id))
        verify(exactly = 0) { pipeline.retryPush(any()) }
        job.run()
        verify(exactly = 1) { pipeline.retryPush(id) }
    }

    @Test
    fun `PENDING_AI is measured from the status change not from detection`() {
        val id = report(ReportStatus.PENDING_AI, Duration.ofHours(5))
        jdbc.sql("UPDATE report SET status_changed_at = :at WHERE id = :id")
            .param("at", now.minus(Duration.ofMinutes(10)).atOffset(java.time.ZoneOffset.UTC)).param("id", id).update()

        job.run()

        assertEquals(ReportStatus.PENDING_AI, statusOf(id))
        verify(exactly = 1) { pipeline.reprocess(id, false) }
    }

    @Test
    fun `PUSH_PENDING is handled before NEW`() {
        val newId = report(ReportStatus.NEW, Duration.ofMinutes(30), "n")
        val pushId = report(ReportStatus.PUSH_PENDING, Duration.ofMinutes(30), "p")

        job.run()

        verifyOrder {
            pipeline.retryPush(pushId)
            pipeline.reprocess(newId, false)
        }
    }

    @Test
    fun `run stops starting new work after the four minute budget`() {
        val first = report(ReportStatus.NEW, Duration.ofMinutes(40), "a")
        val second = report(ReportStatus.NEW, Duration.ofMinutes(30), "b")
        every { pipeline.reprocess(first, any()) } answers {
            now = now.plus(Duration.ofMinutes(5))
            true
        }

        job.run()

        verify(exactly = 1) { pipeline.reprocess(first, false) }
        verify(exactly = 0) { pipeline.reprocess(second, any()) }
    }

    @Test
    fun `NEW report 24 hours after detection is FAILED`() {
        val id = report(ReportStatus.NEW, Duration.ofHours(24))

        job.run()

        assertEquals(ReportStatus.FAILED, statusOf(id))
        verify(exactly = 0) { pipeline.reprocess(any(), any()) }
    }
}
