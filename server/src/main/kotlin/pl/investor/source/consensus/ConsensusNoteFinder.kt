package pl.investor.source.consensus

import org.springframework.stereotype.Component
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Report
import java.time.Clock
import java.time.Duration

@Component
class ConsensusNoteFinder(
    private val notes: ConsensusRepository,
    private val companies: CompanyRepository,
    private val client: ConsensusClient,
    private val clock: Clock,
) {

    fun findAndSave(companyId: Long, reports: List<PendingConsensus>): List<Long> {
        val windows = reports.associateWith { NoteWindow(it.publishedAt, it.publishedAt.plus(NOTE_WINDOW)) }
        val found = client.find(companies.findById(companyId), windows.values.distinct())
        return windows.mapNotNull { (report, window) ->
            found[window]?.let {
                notes.save(report.reportId, it, clock.instant())
                report.reportId
            }
        }
    }

    fun findAndSave(report: Report): Boolean =
        findAndSave(report.companyId, listOf(PendingConsensus(checkNotNull(report.id), report.companyId, report.publishedAt))).isNotEmpty()

    fun hasNote(reportId: Long): Boolean = notes.exists(reportId)

    fun canFindNotesOf(company: Company): Boolean = client.hasNewsPage(company)

    companion object {
        val NOTE_WINDOW: Duration = Duration.ofHours(36)
    }
}
