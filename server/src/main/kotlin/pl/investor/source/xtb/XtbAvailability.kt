package pl.investor.source.xtb

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.source.biznesradar.BiznesRadarClient
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class XtbAvailability(
    private val xtb: XtbClient,
    private val biznesRadar: BiznesRadarClient,
    private val companies: CompanyRepository,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(XtbAvailability::class.java)

    @Volatile
    private var latest: FetchedOffer? = null

    fun isAvailable(company: Company): Boolean {
        val offer = offer()
        val tickers = if (company.market in POLISH_MARKETS) runCatching { biznesRadar.tickersBySymbol() } else Result.success(emptyMap())
        val listed = offer.lists(company, tickers.getOrDefault(emptyMap()))
        tickers.exceptionOrNull()?.takeIf { !listed }?.let { throw it as? SourceUnavailableException ?: SourceUnavailableException(Source.BIZNESRADAR, it) }
        if (listed != company.availableOnXtb) companies.updateXtbAvailability(listOf(checkNotNull(company.id)), listed)
        return listed
    }

    fun refreshAll() {
        val offer = try {
            offer()
        } catch (e: SourceUnavailableException) {
            return
        }
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
        lists(XTB_COUNTRIES.getValue(company.market), company.tradingTickers(gpwTickers), company.name)

    private fun Company.tradingTickers(gpwTickers: Map<String, String>): List<String> =
        if (market.isCoveredByAvanza) listOfNotNull(ticker, exchangeTicker) else listOfNotNull(biznesradarSymbol?.let(gpwTickers::get))

    private fun offer(): XtbOffer {
        val now = clock.instant()
        latest?.takeIf { Duration.between(it.fetchedAt, now) < OFFER_MAX_AGE }?.let { return it.offer }
        return try {
            XtbOffer(XtbCountry.entries.associateWith(xtb::stocks)).also { offer ->
                latest = FetchedOffer(offer, now)
                log.info("XTB stock list: {}", XtbCountry.entries.joinToString { "${offer.size(it)} $it" })
            }
        } catch (e: Exception) {
            log.warn("XTB stock list unavailable, keeping the previous one: {}", e.message)
            latest?.offer ?: throw (e as? SourceUnavailableException ?: SourceUnavailableException(Source.XTB, e))
        }
    }

    private class FetchedOffer(val offer: XtbOffer, val fetchedAt: Instant)

    private companion object {
        val OFFER_MAX_AGE: Duration = Duration.ofHours(23)
        val POLISH_MARKETS = setOf(Market.GPW, Market.NC)
        val XTB_COUNTRIES = mapOf(
            Market.GPW to XtbCountry.PL, Market.NC to XtbCountry.PL, Market.SE to XtbCountry.SE, Market.DE to XtbCountry.DE,
            Market.NL to XtbCountry.NL,
        )
    }
}
