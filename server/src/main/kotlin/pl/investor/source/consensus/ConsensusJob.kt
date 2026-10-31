package pl.investor.source.consensus

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.investor.report.CompanyRepository
import pl.investor.source.SourceUnavailableException
import java.time.Clock
import java.time.Duration

@Component
class ConsensusJob(
    private val notes: ConsensusRepository,
    private val companies: CompanyRepository,
    private val client: ConsensusClient,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(ConsensusJob::class.java)

    @Scheduled(cron = "0 15 6-16,19-23 * * *", zone = "Europe/Warsaw")
    fun refresh() {
        notes.pending(clock.instant().minus(WINDOW)).groupBy { it.companyId }.forEach { (companyId, reports) ->
            runCatching { refresh(companyId, reports) }.onFailure { error ->
                val reportIds = reports.map { it.reportId }
                when (error) {
                    is SourceUnavailableException -> log.warn("Consensus notes of reports {} unavailable: {}", reportIds, error.message)
                    else -> log.error("Consensus notes of reports {} failed", reportIds, error)
                }
            }
        }
    }

    private fun refresh(companyId: Long, reports: List<PendingConsensus>) {
        val windows = reports.associateWith { NoteWindow(it.publishedAt, it.publishedAt.plus(WINDOW)) }
        val found = client.find(companies.findById(companyId), windows.values.toList())
        windows.forEach { (report, window) -> found[window]?.let { notes.save(report.reportId, it) } }
    }

    private companion object {
        val WINDOW: Duration = Duration.ofHours(36)
    }
}
