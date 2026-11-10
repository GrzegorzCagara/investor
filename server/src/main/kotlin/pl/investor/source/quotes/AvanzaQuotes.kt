package pl.investor.source.quotes

import pl.investor.support.STOCKHOLM
import org.springframework.stereotype.Component
import pl.investor.history.DailyQuote
import pl.investor.history.DUTCH_INDEX
import pl.investor.history.FINNISH_INDEX
import pl.investor.history.GERMAN_INDEX
import pl.investor.history.QuoteRepository
import pl.investor.history.SWEDISH_INDEX
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.scoring.ReferenceSession
import pl.investor.source.avanza.AvanzaClient
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate

@Component
class AvanzaQuotes(
    private val avanza: AvanzaClient,
    private val yahoo: YahooChartClient,
    private val quotes: QuoteRepository,
    private val clock: Clock,
    private val companies: CompanyRepository,
) {

    fun updateIndex(market: Market) {
        val index = YAHOO_INDICES[market] ?: error("No Yahoo index for $market")
        update(index.instrument, market) { from, to -> yahoo.daily(index.symbol, index.instrument, from, to) }
    }

    fun updateCompany(company: Company) {
        val isin = company.isin ?: return
        val orderBookId = company.avanzaId ?: return
        if (companies.hasEarlierNamesakeByIsin(company, AVANZA_MARKETS)) return
        update(isin, company.market) { from, to -> avanza.dailyQuotes(orderBookId, isin, from, to) }
    }

    private fun update(instrument: String, market: Market, load: (LocalDate, LocalDate) -> List<DailyQuote>) {
        val now = clock.instant()
        val today = now.atZone(STOCKHOLM).toLocalDate()
        val from = quotes.latestDate(instrument)?.plusDays(1) ?: today.minusDays(HISTORY_DAYS)
        val lastFinished = if (ReferenceSession.isClosed(today, now, market)) today else today.minusDays(1)
        if (!hasWeekdayBetween(from, lastFinished)) return
        load(from, lastFinished).filter { !it.date.isAfter(lastFinished) }.forEach(quotes::save)
    }

    private fun hasWeekdayBetween(from: LocalDate, to: LocalDate): Boolean =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.any { it.dayOfWeek !in WEEKEND }

    private class YahooIndex(val symbol: String, val instrument: String)

    private companion object {
        val YAHOO_INDICES = mapOf(
            Market.SE to YahooIndex("^OMXSPI", SWEDISH_INDEX),
            Market.DE to YahooIndex("^GDAXI", GERMAN_INDEX),
            Market.NL to YahooIndex("^AEX", DUTCH_INDEX),
            Market.FI to YahooIndex("^OMXH25", FINNISH_INDEX),
        )
        val AVANZA_MARKETS = Market.entries.filter { it.isCoveredByAvanza }
        const val HISTORY_DAYS = 915L
        val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }
}
