package pl.investor.source.quotes

import pl.investor.support.STOCKHOLM
import org.springframework.stereotype.Component
import pl.investor.history.DailyQuote
import pl.investor.history.QuoteRepository
import pl.investor.history.SWEDISH_INDEX
import pl.investor.report.Company
import pl.investor.report.Market
import pl.investor.scoring.ReferenceSession
import pl.investor.source.avanza.AvanzaClient
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate

@Component
class SwedishQuotes(
    private val avanza: AvanzaClient,
    private val yahoo: YahooChartClient,
    private val quotes: QuoteRepository,
    private val clock: Clock,
) {

    fun updateIndex() = update(SWEDISH_INDEX) { from, to -> yahoo.daily(OMXSPI_SYMBOL, SWEDISH_INDEX, from, to) }

    fun updateCompany(company: Company) {
        val isin = company.isin ?: return
        val orderBookId = company.avanzaId ?: return
        update(isin) { from, to -> avanza.dailyQuotes(orderBookId, isin, from, to) }
    }

    private fun update(instrument: String, load: (LocalDate, LocalDate) -> List<DailyQuote>) {
        val now = clock.instant()
        val today = now.atZone(STOCKHOLM).toLocalDate()
        val from = quotes.latestDate(instrument)?.plusDays(1) ?: today.minusDays(HISTORY_DAYS)
        val lastFinished = if (ReferenceSession.isClosed(today, now, Market.SE)) today else today.minusDays(1)
        if (!hasWeekdayBetween(from, lastFinished)) return
        load(from, lastFinished).filter { !it.date.isAfter(lastFinished) }.forEach(quotes::save)
    }

    private fun hasWeekdayBetween(from: LocalDate, to: LocalDate): Boolean =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.any { it.dayOfWeek !in WEEKEND }

    private companion object {
        const val OMXSPI_SYMBOL = "^OMXSPI"
        const val HISTORY_DAYS = 915L
        val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }
}
