package pl.investor.pipeline

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.source.xtb.XtbAvailability

@Component
class XtbFilter(
    @Value("\${investor.xtb-only-markets:DE,GPW,NC,SE}") xtbOnlyMarkets: String,
    private val availability: XtbAvailability,
    private val refresher: CompanyDataRefresher,
    private val companies: CompanyRepository,
) {

    private val markets: Set<Market> = xtbOnlyMarkets.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.map(Market::valueOf).toSet()

    fun withholdsFromAi(company: Company): Boolean {
        if (company.market !in markets) return false
        val identity = refresher.tradingIdentity(company)
        if (availability.isAvailable(companies.findById(checkNotNull(company.id)))) return false
        if (identity == TradingIdentity.UNAVAILABLE) {
            throw SourceUnavailableException(company.identitySource(), IllegalStateException("${company.name} has no trading ticker yet"))
        }
        return true
    }

    private fun Company.identitySource(): Source = if (market.isCoveredByAvanza) Source.AVANZA else Source.BIZNESRADAR
}
