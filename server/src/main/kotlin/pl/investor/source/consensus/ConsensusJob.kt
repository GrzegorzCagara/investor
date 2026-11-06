package pl.investor.source.consensus

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.investor.pipeline.ReportPipeline
import pl.investor.source.SourceUnavailableException
import java.time.Clock
import java.time.Instant

@Component
class ConsensusJob(
    private val notes: ConsensusRepository,
    private val finder: ConsensusNoteFinder,
    private val pipeline: ReportPipeline,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(ConsensusJob::class.java)

    @Scheduled(cron = "0 15 6-16,19-23 * * *", zone = "Europe/Warsaw")
    fun refresh() {
        val since = clock.instant().minus(ConsensusNoteFinder.NOTE_WINDOW)
        saveNewNotes(since)
        notes.savedAfterScoring(since).forEach(::rescore)
    }

    private fun saveNewNotes(since: Instant) {
        notes.pending(since).groupBy { it.companyId }.forEach { (companyId, reports) ->
            runCatching { finder.findAndSave(companyId, reports) }
                .onFailure { error ->
                    val reportIds = reports.map { it.reportId }
                    when (error) {
                        is SourceUnavailableException -> log.warn("Consensus notes of reports {} unavailable: {}", reportIds, error.message)
                        else -> log.error("Consensus notes of reports {} failed", reportIds, error)
                    }
                }
        }
    }

    private fun rescore(reportId: Long) {
        runCatching { pipeline.rescoreWithConsensus(reportId) }
            .onFailure { log.error("Re-scoring report {} with its consensus note failed", reportId, it) }
    }
}
