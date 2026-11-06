package pl.investor.api

import org.springframework.stereotype.Component
import pl.investor.context.ReportContextRepository
import pl.investor.extraction.ExtractionRepository
import pl.investor.extraction.OutlookTone
import pl.investor.history.PeriodKind
import pl.investor.history.QuarterResultRepository
import pl.investor.history.RevenuePeriodRepository
import pl.investor.history.indexInstrument
import pl.investor.news.NewsAssessment
import pl.investor.news.NewsAssessmentRepository
import pl.investor.news.contractValueText
import pl.investor.notification.firstSentence
import pl.investor.outcome.Outcome
import pl.investor.outcome.OutcomeRepository
import pl.investor.report.EventType
import pl.investor.report.Market
import pl.investor.report.ReportStatus
import pl.investor.scoring.ConsensusComparison
import pl.investor.scoring.ConsensusMetric
import pl.investor.scoring.ReferenceSession
import pl.investor.scoring.ScoreComponent
import pl.investor.scoring.ScoreComponents
import pl.investor.scoring.ScoreRepository
import pl.investor.scoring.WarningLabels
import pl.investor.scoring.primaryPeriod
import pl.investor.scoring.roundedHalfUp
import pl.investor.settings.PushThreshold
import pl.investor.source.consensus.ConsensusRepository
import pl.investor.support.toWarsawDate
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Component
class AlertAssembler(
    private val pushThreshold: PushThreshold,
    private val scores: ScoreRepository,
    private val extractions: ExtractionRepository,
    private val revenuePeriods: RevenuePeriodRepository,
    private val quarterResults: QuarterResultRepository,
    private val alerts: AlertRepository,
    private val outcomes: OutcomeRepository,
    private val consensusNotes: ConsensusRepository,
    private val newsAssessments: NewsAssessmentRepository,
    private val reportContexts: ReportContextRepository,
    private val clock: Clock,
) {

    fun items(rows: List<AlertRow>): List<AlertItem> {
        val threshold = pushThreshold.current()
        return rows.map { row ->
            val score = row.score()
            AlertItem(
                id = row.id,
                companyName = row.companyName,
                market = row.market,
                eventType = row.eventType,
                publishedAt = row.publishedAt,
                score = score,
                belowThreshold = row.scoredTotal()?.let { it < threshold } ?: false,
                summary = row.summary().firstSentence(),
                status = row.status,
                availableOnXtb = row.availableOnXtb,
            )
        }
    }

    fun detail(row: AlertRow): AlertDetail {
        val stored = scores.find(row.id).takeIf { row.hasScore() }
        val news = row.takeIf { it.eventType == EventType.NEWS }?.let { newsAssessments.find(it.id) }
        val extraction = extractions.find(row.id)?.extraction.takeIf { row.eventType == EventType.RESULTS }
        val outlook = extraction?.outlook
        val context = stored?.score?.context?.takeIf { row.eventType == EventType.RESULTS }
        val reportContext = reportContexts.find(row.id)?.takeIf { row.eventType == EventType.RESULTS }
        return AlertDetail(
            id = row.id,
            companyName = row.companyName,
            market = row.market,
            reportNumber = row.displayedNumber(),
            reportKind = row.reportKind,
            publishedAt = row.publishedAt,
            score = row.score(),
            components = stored?.score?.components?.takeIf { row.eventType == EventType.RESULTS }?.views().orEmpty(),
            penalty = stored?.score?.penalty ?: 0.0,
            warnings = stored?.score?.warnings.orEmpty().map(WarningLabels::label),
            reason = row.summary(),
            outlookQuote = outlook?.quote?.takeIf { outlook.tone != OutlookTone.NONE },
            revenueHistory = extraction?.primaryPeriod()?.let { revenueHistory(row, it.kind) }.orEmpty(),
            sourceUrl = row.detailUrl,
            reaction = outcomes.find(row.id)?.reactionView(row.market),
            consensus = consensusRows(row, context?.consensus.orEmpty()),
            companyHistory = scoredOutcomes(alerts.scoredBefore(row.companyId, row.publishedAt, COMPANY_HISTORY_SIZE)).map { it.toOutcomeItem() },
            eventType = row.eventType,
            news = news?.view(),
            listing = row.listing,
            ytdChangePct = context?.ytdChangePct,
            ordersYoyPct = context?.ordersYoyPct,
            pastReactions = context?.pastReactions.orEmpty().map { PastReactionView(it.date, it.pct) },
            signals = stored?.score?.signals.orEmpty().sortedBy { it.ordinal }.map { it.label },
            availableOnXtb = row.availableOnXtb,
            context = reportContext?.points.orEmpty().map { ContextPointView(it.direction, it.text) },
            headlines = reportContext?.headlines.orEmpty().map { HeadlineView(it.title, it.url, it.publishedAt) },
        )
    }

    private fun consensusRows(row: AlertRow, comparisons: List<ConsensusComparison>): List<ConsensusRowView> {
        val comparisonRows = comparisons.map { it.view() }
        if (row.market != Market.GPW && comparisonRows.isNotEmpty()) return comparisonRows
        return consensusNotes.find(row.id)?.rows.orEmpty()
            .map { ConsensusRowView(it.label, it.actual, it.consensus, it.differencePct) }
            .ifEmpty { comparisonRows }
    }

    private fun AlertRow.displayedNumber(): String = when (market) {
        Market.SE -> SWEDISH_REPORT_NUMBER
        Market.DE -> GERMAN_REPORT_NUMBER
        else -> reportNumber
    }

    private fun ConsensusComparison.view() = ConsensusRowView(
        label = "${metric.label()} vs konsensus $source${analysts?.let { " (${it.analystsText()})" }.orEmpty()}",
        actual = actual.inMillions(),
        consensus = consensus.inMillions(),
        differencePct = differencePct(),
        currency = currency,
    )

    private fun ConsensusMetric.label() = when (this) {
        ConsensusMetric.REVENUE -> "Sprzedaż"
        ConsensusMetric.EBIT -> "Zysk operacyjny"
    }

    private fun Int.analystsText() = if (this == 1) "1 analityka" else "$this analityków"

    private fun BigDecimal.inMillions(): BigDecimal = divide(THOUSAND).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    private fun scoredOutcomes(rows: List<AlertRow>): List<ScoredOutcome> {
        val byReport = outcomes.findAll(rows.map { it.id })
        return rows.mapNotNull { row ->
            row.scoredTotal()?.let { total ->
                ScoredOutcome(row.id, row.companyName, row.market, row.eventType, row.publishedAt, total, byReport[row.id])
            }
        }
    }

    private fun NewsAssessment.view() = NewsView(category.label, direction.label, contractValueText(), revenueSharePct)

    private fun Outcome.reactionView(market: Market): ReactionView? {
        val stockPct = reactionPct ?: return null
        if (!ReferenceSession.isClosed(baseDate, clock.instant(), market)) return null
        return ReactionView(stockPct, reactionIndexPct, market.indexInstrument(), volumeMultiple, reactionCategory(stockPct, reactionIndexPct))
    }

    private fun revenueHistory(row: AlertRow, kind: PeriodKind): List<RevenuePoint> = when (kind) {
        PeriodKind.MONTH -> revenuePeriods.publishedBefore(row.companyId, kind, row.publishedAt.plusMillis(1), HISTORY_SIZE)
            .map { RevenuePoint(it.periodEnd.format(MONTH_LABEL), it.revenue.toDouble(), it.currency) }
        PeriodKind.QUARTER -> quarterResults.publishedBefore(row.companyId, row.publishedAt.toWarsawDate().plusDays(1), HISTORY_SIZE)
            .mapNotNull { result -> result.revenue?.let { RevenuePoint(result.quarterEnd.quarterLabel(), it.toDouble(), row.market.currency) } }
        PeriodKind.HALF_YEAR, PeriodKind.YEAR -> emptyList()
    }.reversed()

    private fun AlertRow.hasScore(): Boolean = status in SCORED_STATUSES

    private fun AlertRow.scoredTotal(): Double? = total?.takeIf { hasScore() }

    private fun AlertRow.score(): Int? = scoredTotal()?.roundedHalfUp()?.toInt()

    private fun AlertRow.summary(): String = when (status) {
        ReportStatus.PENDING_ATTACHMENTS -> "Czekam na załączniki"
        ReportStatus.PENDING_AI -> "Czekam na AI"
        ReportStatus.PENDING_DATA -> "Czekam na dane finansowe"
        ReportStatus.UNSCORED_NO_AI -> "Bez oceny: wyczerpany limit AI"
        ReportStatus.UNSCORED_NO_DATA -> "Bez oceny: brak danych finansowych"
        else -> reason.orEmpty()
    }

    private fun ScoreComponents.views() = ScoreComponent.entries.map { ComponentView(it.label, it.pointsOf(this), it.max) }

    private fun LocalDate.quarterLabel() = "$year Q${(monthValue - 1) / 3 + 1}"

    private companion object {
        const val HISTORY_SIZE = 8
        const val SWEDISH_REPORT_NUMBER = "Nasdaq"
        const val GERMAN_REPORT_NUMBER = "EQS"
        val THOUSAND = BigDecimal(1000)
        const val COMPANY_HISTORY_SIZE = 5
        val SCORED_STATUSES = setOf(ReportStatus.SCORED, ReportStatus.PUSH_PENDING)
        val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.yyyy")
    }
}
