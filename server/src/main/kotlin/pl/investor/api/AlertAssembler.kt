package pl.investor.api

import org.springframework.stereotype.Component
import pl.investor.extraction.ExtractionRepository
import pl.investor.extraction.OutlookTone
import pl.investor.history.PeriodKind
import pl.investor.history.QuarterResultRepository
import pl.investor.history.RevenuePeriodRepository
import pl.investor.history.indexInstrument
import pl.investor.notification.firstSentence
import pl.investor.outcome.Outcome
import pl.investor.outcome.OutcomeRepository
import pl.investor.report.EventType
import pl.investor.report.Market
import pl.investor.report.ReportStatus
import pl.investor.scoring.ReferenceSession
import pl.investor.scoring.ScoreComponents
import pl.investor.scoring.ScoreRepository
import pl.investor.scoring.WarningLabels
import pl.investor.scoring.primaryPeriod
import pl.investor.scoring.roundedHalfUp
import pl.investor.settings.PushThreshold
import pl.investor.source.consensus.ConsensusRepository
import pl.investor.support.toWarsawDate
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
                eventType = EventType.RESULTS,
                publishedAt = row.publishedAt,
                score = score,
                belowThreshold = row.scoredTotal()?.let { it < threshold } ?: false,
                summary = row.summary().firstSentence(),
                status = row.status,
            )
        }
    }

    fun detail(row: AlertRow): AlertDetail {
        val stored = scores.find(row.id).takeIf { row.hasScore() }
        val extraction = extractions.find(row.id)?.extraction
        val outlook = extraction?.outlook
        return AlertDetail(
            id = row.id,
            companyName = row.companyName,
            market = row.market,
            reportNumber = row.reportNumber,
            reportKind = row.reportKind,
            publishedAt = row.publishedAt,
            score = row.score(),
            components = stored?.score?.components?.views().orEmpty(),
            penalty = stored?.score?.penalty ?: 0.0,
            warnings = stored?.score?.warnings.orEmpty().map(WarningLabels::label),
            reason = row.summary(),
            outlookQuote = outlook?.quote?.takeIf { outlook.tone != OutlookTone.NONE },
            revenueHistory = extraction?.primaryPeriod()?.let { revenueHistory(row, it.kind) }.orEmpty(),
            sourceUrl = row.detailUrl,
            reaction = outcomes.find(row.id)?.reactionView(row.market),
            consensus = consensusNotes.find(row.id)?.rows.orEmpty().map { ConsensusRowView(it.label, it.actual, it.consensus, it.differencePct) },
            companyHistory = scoredOutcomes(alerts.scoredBefore(row.companyId, row.publishedAt, COMPANY_HISTORY_SIZE)).map { it.toOutcomeItem() },
        )
    }

    private fun scoredOutcomes(rows: List<AlertRow>): List<ScoredOutcome> {
        val byReport = outcomes.findAll(rows.map { it.id })
        return rows.mapNotNull { row ->
            row.scoredTotal()?.let { total ->
                ScoredOutcome(row.id, row.companyName, row.market, EventType.RESULTS, row.publishedAt, total, byReport[row.id])
            }
        }
    }

    private fun Outcome.reactionView(market: Market): ReactionView? {
        val stockPct = reactionPct ?: return null
        if (!ReferenceSession.isClosed(baseDate, clock.instant())) return null
        return ReactionView(stockPct, reactionIndexPct, market.indexInstrument(), volumeMultiple, reactionCategory(stockPct, reactionIndexPct))
    }

    private fun revenueHistory(row: AlertRow, kind: PeriodKind): List<RevenuePoint> = when (kind) {
        PeriodKind.MONTH -> revenuePeriods.publishedBefore(row.companyId, kind, row.publishedAt.plusMillis(1), HISTORY_SIZE)
            .map { RevenuePoint(it.periodEnd.format(MONTH_LABEL), it.revenue.toDouble(), it.currency) }
        PeriodKind.QUARTER -> quarterResults.publishedBefore(row.companyId, row.publishedAt.toWarsawDate().plusDays(1), HISTORY_SIZE)
            .mapNotNull { result -> result.revenue?.let { RevenuePoint(result.quarterEnd.quarterLabel(), it.toDouble(), PLN) } }
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

    private fun ScoreComponents.views() = listOf(
        ComponentView("Niespodzianka", surprise, 30.0),
        ComponentView("Wzrost przychodów", growth, 20.0),
        ComponentView("Jakość: zysk i marża", quality, 15.0),
        ComponentView("Seria mocnych raportów", series, 10.0),
        ComponentView("Zapowiedzi zarządu", outlook, 10.0),
        ComponentView("Wycena („czy już w cenie”)", valuation, 15.0),
    )

    private fun LocalDate.quarterLabel() = "$year Q${(monthValue - 1) / 3 + 1}"

    private companion object {
        const val HISTORY_SIZE = 8
        const val COMPANY_HISTORY_SIZE = 5
        const val PLN = "PLN"
        val SCORED_STATUSES = setOf(ReportStatus.SCORED, ReportStatus.PUSH_PENDING)
        val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.yyyy")
    }
}
