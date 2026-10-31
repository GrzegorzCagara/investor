package pl.investor.outcome

import pl.investor.history.DailyQuote
import pl.investor.scoring.ReferenceSession
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

object OutcomeCalculator {

    private const val VOLUME_SESSIONS = 20
    private val HORIZONS = listOf(5, 20, 60)

    fun calculate(publishedAt: Instant, sessions: List<LocalDate>, stock: List<DailyQuote>, index: List<DailyQuote>): Outcome? {
        val calendar = (sessions + stock.map { it.date }).distinct().sorted()
        val (closedBefore, open) = calendar.partition { ReferenceSession.isClosed(it, publishedAt) }
        val ref = closedBefore.lastOrNull() ?: return null
        val base = open.firstOrNull() ?: return null
        val stockQuotes = Quotes(stock)
        val indexQuotes = Quotes(index)
        val baseQuote = stockQuotes.on(base) ?: return null
        val baseIndexClose = indexQuotes.on(base)?.close
        val horizons = HORIZONS.associateWith { sessionsAfter ->
            calendar.getOrNull(calendar.indexOf(base) + sessionsAfter)?.let { session ->
                val stockClose = stockQuotes.on(session)?.close
                val indexClose = indexQuotes.on(session)?.close
                if (stockClose != null && indexClose != null && baseIndexClose != null) {
                    Horizon(changePct(baseQuote.close, stockClose), changePct(baseIndexClose, indexClose))
                } else {
                    null
                }
            }
        }
        return Outcome(
            baseDate = base,
            reactionPct = stockQuotes.carriedTo(ref)?.let { changePct(it, baseQuote.close) },
            reactionIndexPct = baseIndexClose?.let { end -> indexQuotes.carriedTo(ref)?.let { changePct(it, end) } },
            volumeMultiple = volumeMultiple(stockQuotes, closedBefore.takeLast(VOLUME_SESSIONS), baseQuote.volume),
            return5Pct = horizons[5]?.stockPct,
            index5Pct = horizons[5]?.indexPct,
            return20Pct = horizons[20]?.stockPct,
            index20Pct = horizons[20]?.indexPct,
            return60Pct = horizons[60]?.stockPct,
            index60Pct = horizons[60]?.indexPct,
        )
    }

    private fun volumeMultiple(stock: Quotes, window: List<LocalDate>, baseVolume: Long): Double? {
        val mean = window.sumOf { stock.on(it)?.volume ?: 0L }.toDouble() / window.size
        return if (mean > 0) baseVolume / mean else null
    }

    private fun changePct(start: BigDecimal, end: BigDecimal): Double? =
        if (start.signum() > 0) (end.toDouble() / start.toDouble() - 1) * 100 else null

    private data class Horizon(val stockPct: Double?, val indexPct: Double?)

    private class Quotes(quotes: List<DailyQuote>) {
        private val byDate = quotes.associateBy { it.date }
        private val sorted = quotes.sortedBy { it.date }

        fun on(session: LocalDate): DailyQuote? = byDate[session]

        fun carriedTo(session: LocalDate): BigDecimal? = sorted.lastOrNull { !it.date.isAfter(session) }?.close
    }
}
