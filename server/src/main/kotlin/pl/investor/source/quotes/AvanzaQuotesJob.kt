package pl.investor.source.quotes

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.investor.outcome.OutcomeRepository
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import java.time.Clock
import java.time.Duration

@Component
class AvanzaQuotesJob(
    private val avanzaQuotes: AvanzaQuotes,
    private val outcomes: OutcomeRepository,
    private val companies: CompanyRepository,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(AvanzaQuotesJob::class.java)

    @Scheduled(cron = "0 45 18 * * MON-FRI", zone = "Europe/Stockholm")
    fun afterClose() = run()

    @Scheduled(cron = "0 30 21 * * MON-FRI", zone = "Europe/Stockholm")
    fun retry() = run()

    fun run() {
        AVANZA_MARKETS.forEach { market ->
            runCatching { avanzaQuotes.updateIndex(market) }.onFailure { log.error("{} index quotes update failed", market, it) }
        }
        outcomes.pending(clock.instant().minus(TRACKING_PERIOD))
            .filter { it.market in AVANZA_MARKETS }
            .map { it.market to it.isin }
            .distinct()
            .forEach { (market, isin) ->
                runCatching { companies.findByIsin(isin, market)?.let(avanzaQuotes::updateCompany) }
                    .onFailure { log.error("Quotes update of {} failed", isin, it) }
            }
    }

    fun backfillHistory(market: Market) {
        avanzaQuotes.updateIndex(market)
        companies.findInMarkets(listOf(market)).forEach { company ->
            runCatching { avanzaQuotes.updateCompany(company) }.onFailure { log.error("Quotes backfill of {} failed", company.name, it) }
        }
    }

    private companion object {
        val TRACKING_PERIOD: Duration = Duration.ofDays(120)
        val AVANZA_MARKETS = Market.entries.filter { it.isCoveredByAvanza }
    }
}
