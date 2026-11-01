package pl.investor.outcome

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.investor.history.DailyQuote
import pl.investor.history.QuoteRepository
import pl.investor.history.SESSION_CALENDAR
import pl.investor.history.indexInstrument
import pl.investor.support.toWarsawDate
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

@Component
class OutcomeJob(
    private val outcomes: OutcomeRepository,
    private val quotes: QuoteRepository,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(OutcomeJob::class.java)

    @Scheduled(cron = "0 0 22 * * MON-FRI", zone = "Europe/Warsaw")
    fun scheduledRefresh() = refresh()

    fun refresh(recomputeAll: Boolean = false) {
        val now = clock.instant()
        val today = now.toWarsawDate()
        val pending = outcomes.pending(now.minus(TRACKING_PERIOD), includeComplete = recomputeAll)
        val earliest = pending.minOfOrNull { it.quotesFrom() } ?: return
        val instruments = mutableMapOf<String, List<DailyQuote>>()
        fun instrument(name: String) = instruments.getOrPut(name) { quotes.between(name, earliest, today) }
        pending.forEach { report ->
            runCatching { refresh(report, today, ::instrument) }
                .onFailure { log.error("Outcome of report {} failed", report.reportId, it) }
        }
        log.info("Outcomes refreshed for {} reports", pending.size)
    }

    private fun refresh(report: PendingOutcome, today: LocalDate, instrument: (String) -> List<DailyQuote>) {
        val index = report.market.indexInstrument() ?: return
        val from = report.quotesFrom()
        val sessions = instrument(SESSION_CALENDAR).filter { it.date >= from }.map { it.date }
        val indexQuotes = instrument(index).filter { it.date >= from }
        OutcomeCalculator.calculate(report.publishedAt, sessions, quotes.between(report.isin, from, today), indexQuotes)
            ?.let { outcomes.save(report.reportId, it, clock.instant()) }
    }

    private fun PendingOutcome.quotesFrom(): LocalDate = publishedAt.toWarsawDate().minusDays(QUOTE_LOOKBACK_DAYS)

    private companion object {
        val TRACKING_PERIOD: Duration = Duration.ofDays(120)
        const val QUOTE_LOOKBACK_DAYS = 40L
    }
}
