package pl.investor.source.xtb

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.source.SourceUnavailableException
import pl.investor.source.biznesradar.BiznesRadarClient
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class XtbAvailabilityJob(
    private val xtb: XtbClient,
    private val biznesRadar: BiznesRadarClient,
    private val companies: CompanyRepository,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(XtbAvailabilityJob::class.java)

    @Volatile
    private var latest: FetchedOffer? = null

    @Scheduled(initialDelay = FIRST_RUN_DELAY_MS, fixedDelay = MATCH_INTERVAL_MS)
    fun refresh() {
        val offer = currentOffer() ?: return
        val gpwTickers = try {
            biznesRadar.tickersBySymbol()
        } catch (e: SourceUnavailableException) {
            log.warn("BiznesRadar unavailable, XTB availability left unchanged: {}", e.message)
            return
        }
        val (listed, notListed) = companies.findInMarkets(XTB_COUNTRIES.keys).partition { offer.lists(it, gpwTickers) }
        val gained = listed.filterNot { it.availableOnXtb }.mapNotNull { it.id }
        val lost = notListed.filter { it.availableOnXtb }.mapNotNull { it.id }
        companies.updateXtbAvailability(gained, available = true)
        companies.updateXtbAvailability(lost, available = false)
        if (gained.isNotEmpty() || lost.isNotEmpty()) {
            log.info("XTB availability: {} companies listed, {} added, {} removed", listed.size, gained.size, lost.size)
        }
    }

    private fun XtbOffer.lists(company: Company, gpwTickers: Map<String, String>): Boolean =
        lists(XTB_COUNTRIES.getValue(company.market), company.tradingTicker(gpwTickers), company.name)

    private fun Company.tradingTicker(gpwTickers: Map<String, String>): String? =
        if (market == Market.SE) ticker else biznesradarSymbol?.let(gpwTickers::get)

    private fun currentOffer(): XtbOffer? {
        val now = clock.instant()
        latest?.takeIf { Duration.between(it.fetchedAt, now) < OFFER_MAX_AGE }?.let { return it.offer }
        return try {
            XtbOffer(XtbCountry.entries.associateWith(xtb::stocks)).also { offer ->
                latest = FetchedOffer(offer, now)
                log.info("XTB stock list: {} PL, {} SE", offer.size(XtbCountry.PL), offer.size(XtbCountry.SE))
            }
        } catch (e: Exception) {
            log.warn("XTB stock list unavailable, keeping the previous one: {}", e.message)
            latest?.offer
        }
    }

    private class FetchedOffer(val offer: XtbOffer, val fetchedAt: Instant)

    private companion object {
        const val FIRST_RUN_DELAY_MS = 180_000L
        const val MATCH_INTERVAL_MS = 3_600_000L
        val OFFER_MAX_AGE: Duration = Duration.ofHours(23)
        val XTB_COUNTRIES = mapOf(Market.GPW to XtbCountry.PL, Market.NC to XtbCountry.PL, Market.SE to XtbCountry.SE)
    }
}
