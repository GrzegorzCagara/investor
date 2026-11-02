package pl.investor.scoring

import org.springframework.stereotype.Component
import pl.investor.history.DailyQuote
import pl.investor.history.QuarterResultRepository
import pl.investor.history.QuoteRepository
import pl.investor.outcome.OutcomeRepository
import pl.investor.report.Company
import pl.investor.report.Market
import pl.investor.source.placera.ConsensusPreviewRepository
import pl.investor.support.toWarsawDate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

@Component
class PastReactionHistory(
    private val previews: ConsensusPreviewRepository,
    private val quarterResults: QuarterResultRepository,
    private val quotes: QuoteRepository,
    private val outcomes: OutcomeRepository,
) {

    fun before(company: Company, publishedAt: Instant): List<PastReaction> {
        val reportDate = publishedAt.toWarsawDate()
        val history = company.isin?.let { isin ->
            val computed = computedReactions(company, isin, reportDate)
            placeraReactions(company, isin, publishedAt)?.filledFrom(computed) ?: computed
        }.orEmpty()
        val own = outcomes.periodicReportDayReactions(checkNotNull(company.id), publishedAt)
            .map { PastReaction(it.publishedAt.toWarsawDate(), it.reactionPct) }
        return history.overriddenBy(own)
            .filter { it.date < reportDate }
            .sortedByDescending { it.date }
            .take(REACTIONS)
    }

    private fun List<PastReaction>.filledFrom(computed: List<PastReaction>): List<PastReaction> =
        if (size >= REACTIONS) this else this + computed.filterNot { candidate -> any { it.isSameReportDay(candidate) } }

    private fun List<PastReaction>.overriddenBy(own: List<PastReaction>): List<PastReaction> {
        val overridden = map { reaction -> own.firstOrNull { it.isSameReportDay(reaction) }?.let { reaction.copy(pct = it.pct) } ?: reaction }
        return overridden + own.filterNot { candidate -> any { it.isSameReportDay(candidate) } }
    }

    private fun PastReaction.isSameReportDay(other: PastReaction): Boolean =
        abs(date.toEpochDay() - other.date.toEpochDay()) <= SAME_REPORT_DAY_TOLERANCE

    private fun placeraReactions(company: Company, isin: String, publishedAt: Instant): List<PastReaction>? =
        if (company.market == Market.SE) previews.latestBefore(isin, publishedAt)?.pastReactions?.takeIf { it.isNotEmpty() } else null

    private fun computedReactions(company: Company, isin: String, reportDate: LocalDate): List<PastReaction> {
        val dates = quarterResults.publishedBefore(checkNotNull(company.id), reportDate, CANDIDATE_REPORTS)
            .mapNotNull { it.publishedOn }
            .distinct()
            .sortedDescending()
        if (dates.isEmpty()) return emptyList()
        val history = quotes.between(isin, dates.min().minusDays(MAX_GAP_DAYS), reportDate)
        return dates.mapNotNull { date ->
            val pct = if (company.market == Market.SE) history.sameDayChangePct(date) else history.overnightChangePct(date)
            pct?.let { PastReaction(date, it) }
        }
    }

    private fun List<DailyQuote>.sameDayChangePct(date: LocalDate): Double? {
        val session = firstOrNull { it.date == date } ?: return null
        val previous = lastOrNull { it.date < date && it.date >= date.minusDays(MAX_GAP_DAYS) } ?: return null
        return changePct(previous.close, session.close)
    }

    private fun List<DailyQuote>.overnightChangePct(date: LocalDate): Double? {
        val previous = lastOrNull { it.date < date && it.date >= date.minusDays(MAX_GAP_DAYS) } ?: return null
        val next = firstOrNull { it.date > date && it.date <= date.plusDays(MAX_GAP_DAYS) } ?: return null
        return changePct(previous.close, next.close)
    }

    private fun changePct(start: BigDecimal, end: BigDecimal): Double? =
        if (start.signum() > 0) (end.toDouble() / start.toDouble() - 1) * 100 else null

    private companion object {
        const val REACTIONS = 8
        const val CANDIDATE_REPORTS = 12
        const val MAX_GAP_DAYS = 7L
        const val SAME_REPORT_DAY_TOLERANCE = 2L
    }
}
