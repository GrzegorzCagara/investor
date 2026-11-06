package pl.investor.context

import org.slf4j.LoggerFactory
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.stereotype.Component
import pl.investor.extraction.ExtractionRepository
import pl.investor.news.EarlierReport
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.EventType
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportRepository
import pl.investor.scoring.Score
import pl.investor.scoring.ScoreRepository
import pl.investor.scoring.primaryPeriod
import pl.investor.source.SourceUnavailableException
import pl.investor.source.consensus.BankierHeadline
import pl.investor.source.consensus.ConsensusClient
import pl.investor.source.consensus.ConsensusRepository
import pl.investor.support.toWarsawDate
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class ReportContextWriter(
    private val reports: ReportRepository,
    private val companies: CompanyRepository,
    private val extractions: ExtractionRepository,
    private val scores: ScoreRepository,
    private val consensusNotes: ConsensusRepository,
    private val consensusClient: ConsensusClient,
    private val contexts: ReportContextRepository,
    private val pointsWriter: ContextPointsWriter,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(ReportContextWriter::class.java)

    fun dueReportIds(): List<Long> = contexts.due(clock.instant().minus(SWEEP_WINDOW), MIN_SCORE, SWEEP_LIMIT)

    fun writeIfDue(reportId: Long): ContextOutcome = write(reportId, rewrite = false)

    fun rewrite(reportId: Long): ContextOutcome = write(reportId, rewrite = true)

    private fun write(reportId: Long, rewrite: Boolean): ContextOutcome =
        try {
            val report = reportOrNull(reportId)
            val score = scores.find(reportId)?.score
            when {
                report == null -> ContextOutcome.NOT_FOUND
                score == null || !report.mayHaveContext() -> ContextOutcome.NOT_ELIGIBLE
                !rewrite && (score.total < MIN_SCORE || contexts.exists(reportId)) -> ContextOutcome.NOT_ELIGIBLE
                else -> writeFor(report, score)
            }
        } catch (e: SourceUnavailableException) {
            log.warn("Bankier unavailable, context of report {} waits for the next run", reportId, e)
            ContextOutcome.SOURCE_UNAVAILABLE
        } catch (e: Exception) {
            log.warn("Context of report {} not written", reportId, e)
            ContextOutcome.FAILED
        }

    private fun reportOrNull(reportId: Long): Report? =
        try {
            reports.findById(reportId)
        } catch (e: EmptyResultDataAccessException) {
            null
        }

    private fun writeFor(report: Report, score: Score): ContextOutcome {
        val id = checkNotNull(report.id)
        val company = companies.findById(report.companyId)
        val headlines = consensusClient.headlines(company, report.publishedAt.minus(HEADLINES_BEFORE), headlinesUntil(report), HEADLINES_LIMIT)
        val earlierReports = earlierReports(company, report)
        if (headlines.isEmpty() && earlierReports.isEmpty()) {
            contexts.save(id, ReportContext(emptyList(), emptyList(), clock.instant()))
            return ContextOutcome.NOTHING_TO_ADD
        }
        val points = pointsWriter.write(input(company, report, score, headlines, earlierReports))
        if (points.isEmpty()) {
            log.warn("Context of report {} has no usable points", report.sourceRef)
            return ContextOutcome.FAILED
        }
        contexts.save(id, ReportContext(points, headlines, clock.instant()))
        return ContextOutcome.WRITTEN
    }

    private fun headlinesUntil(report: Report): Instant = minOf(clock.instant(), report.publishedAt.plus(HEADLINES_AFTER))

    private fun input(company: Company, report: Report, score: Score, headlines: List<BankierHeadline>, earlierReports: List<EarlierReport>): ContextInput {
        val id = checkNotNull(report.id)
        return ContextInput(
            companyName = company.name,
            reportTitle = report.title,
            publishedAt = report.publishedAt,
            period = extractions.find(id)?.extraction?.primaryPeriod(),
            consensus = consensusNotes.find(id)?.rows.orEmpty(),
            components = score.components,
            warnings = score.warnings,
            ytdChangePct = score.context.ytdChangePct,
            pastReactions = score.context.pastReactions,
            headlines = headlines,
            earlierReports = earlierReports,
        )
    }

    private fun earlierReports(company: Company, report: Report): List<EarlierReport> =
        reports.relevantBetween(
            checkNotNull(company.id),
            report.publishedAt.minus(Duration.ofDays(ContextPrompt.EARLIER_REPORTS_DAYS)),
            report.publishedAt,
            EARLIER_REPORTS_LIMIT + 1,
        )
            .filter { it.id != report.id }
            .take(EARLIER_REPORTS_LIMIT)
            .map { EarlierReport(it.publishedAt.toWarsawDate(), checkNotNull(it.title)) }

    private fun Report.mayHaveContext(): Boolean = market in CONTEXT_MARKETS && eventType == EventType.RESULTS

    private companion object {
        const val MIN_SCORE = 50.0
        const val HEADLINES_LIMIT = 15
        const val EARLIER_REPORTS_LIMIT = 10
        const val SWEEP_LIMIT = 10
        val SWEEP_WINDOW: Duration = Duration.ofHours(6)
        val HEADLINES_BEFORE: Duration = Duration.ofDays(14)
        val HEADLINES_AFTER: Duration = Duration.ofDays(2)
        val CONTEXT_MARKETS = setOf(Market.GPW, Market.NC)
    }
}
