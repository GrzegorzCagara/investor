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
class SwedishQuotesJob(
    private val swedishQuotes: SwedishQuotes,
    private val outcomes: OutcomeRepository,
    private val companies: CompanyRepository,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(SwedishQuotesJob::class.java)

    @Scheduled(cron = "0 45 18 * * MON-FRI", zone = "Europe/Stockholm")
    fun afterClose() = run()

    @Scheduled(cron = "0 30 21 * * MON-FRI", zone = "Europe/Stockholm")
    fun retry() = run()

    fun run() {
        runCatching { swedishQuotes.updateIndex() }.onFailure { log.error("OMXSPI quotes update failed", it) }
        outcomes.pending(clock.instant().minus(TRACKING_PERIOD))
            .filter { it.market == Market.SE }
            .map { it.isin }
            .distinct()
            .forEach { isin ->
                runCatching { companies.findByIsin(isin, Market.SE)?.let(swedishQuotes::updateCompany) }
                    .onFailure { log.error("Quotes update of {} failed", isin, it) }
            }
    }

    private companion object {
        val TRACKING_PERIOD: Duration = Duration.ofDays(120)
    }
}
