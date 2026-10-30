package pl.investor.pipeline

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.investor.report.Report
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class PendingReportsJob(
    private val reports: ReportRepository,
    private val pipeline: ReportPipeline,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(PendingReportsJob::class.java)

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    fun run() {
        val startedAt = clock.instant()
        val deadline = startedAt.plus(RUN_BUDGET)
        val batch = collect(startedAt)
        for (report in batch) {
            if (clock.instant() >= deadline) {
                log.warn("Pending reports run hit its time budget, the rest waits for the next run")
                return
            }
            runCatching { handle(report, clock.instant()) }
                .onFailure { log.error("Handling pending report {} failed", report.sourceRef, it) }
        }
    }

    private fun collect(now: Instant): List<Report> =
        listOf(ReportStatus.PUSH_PENDING, ReportStatus.NEW, ReportStatus.PENDING_ATTACHMENTS, ReportStatus.PENDING_AI)
            .flatMap { reports.findByStatus(it, LIMIT_PER_STATUS) } +
            reports.findPendingData(now.minus(DATA_RECHECK), LIMIT_PER_STATUS)

    private fun handle(report: Report, now: Instant) {
        val id = checkNotNull(report.id)
        val sinceDetected = Duration.between(report.detectedAt, now)
        val sinceStatusChange = Duration.between(report.statusChangedAt ?: report.detectedAt, now)
        when (report.status) {
            ReportStatus.NEW -> handleNew(report, id, sinceDetected, now)
            ReportStatus.PENDING_ATTACHMENTS -> pipeline.reprocess(id, allowWithoutAttachments = sinceDetected >= ATTACHMENTS_PATIENCE)
            ReportStatus.PENDING_DATA -> handlePendingData(id, sinceDetected, now)
            ReportStatus.PENDING_AI ->
                if (sinceStatusChange >= AI_PATIENCE) reports.updateStatus(id, ReportStatus.FAILED, now) else pipeline.reprocess(id, allowWithoutAttachments = false)
            ReportStatus.PUSH_PENDING -> handlePushPending(report, id, sinceStatusChange, now)
            else -> Unit
        }
    }

    private fun handleNew(report: Report, id: Long, age: Duration, now: Instant) {
        when {
            age >= NEW_PATIENCE -> {
                log.error("Report {} is still NEW {} hours after detection, marking FAILED", report.sourceRef, age.toHours())
                reports.updateStatus(id, ReportStatus.FAILED, now)
            }
            age > NEW_GRACE -> pipeline.reprocess(id, allowWithoutAttachments = false)
        }
    }

    private fun handlePendingData(id: Long, age: Duration, now: Instant) {
        if (age >= DATA_PATIENCE) {
            reports.updateStatus(id, ReportStatus.UNSCORED_NO_DATA, now)
        } else if (pipeline.retryPendingData(id)) {
            reports.markAttempt(id, now)
        }
    }

    private fun handlePushPending(report: Report, id: Long, age: Duration, now: Instant) {
        if (age < PUSH_PATIENCE) {
            pipeline.retryPush(id)
        } else {
            log.error("Report {} push abandoned after {} hours", report.sourceRef, age.toHours())
            reports.updateStatus(id, ReportStatus.SCORED, now)
        }
    }

    private companion object {
        const val LIMIT_PER_STATUS = 50
        val RUN_BUDGET: Duration = Duration.ofMinutes(4)
        val NEW_GRACE: Duration = Duration.ofMinutes(10)
        val NEW_PATIENCE: Duration = Duration.ofHours(24)
        val ATTACHMENTS_PATIENCE: Duration = Duration.ofHours(2)
        val DATA_RECHECK: Duration = Duration.ofMinutes(55)
        val DATA_PATIENCE: Duration = Duration.ofHours(48)
        val AI_PATIENCE: Duration = Duration.ofHours(2)
        val PUSH_PATIENCE: Duration = Duration.ofHours(24)
    }
}
