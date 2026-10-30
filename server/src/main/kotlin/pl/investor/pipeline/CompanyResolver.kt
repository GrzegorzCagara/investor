package pl.investor.pipeline

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.source.SourceUnavailableException
import pl.investor.source.biznesradar.BiznesRadarClient
import pl.investor.source.biznesradar.SymbolNotFoundException
import pl.investor.source.gpw.ListingEntry

@Component
class CompanyResolver(private val companies: CompanyRepository, private val biznesRadar: BiznesRadarClient) {

    private val log = LoggerFactory.getLogger(CompanyResolver::class.java)

    fun resolve(entry: ListingEntry): Company =
        entry.isin?.let(companies::findByIsin)
            ?: companies.findByName(entry.companyName)
            ?: companies.save(newCompany(entry))

    private fun newCompany(entry: ListingEntry): Company {
        val draft = Company(
            id = null,
            name = entry.companyName,
            isin = entry.isin,
            market = entry.market,
            ticker = null,
            biznesradarSymbol = null,
            publishesMonthlySales = false,
        )
        return draft.copy(
            biznesradarSymbol = unlessUnavailable(entry) { biznesRadar.symbolFor(draft) },
            shortName = unlessUnavailable(entry) { biznesRadar.mediumNameFor(entry.companyName) },
        )
    }

    private fun <T> unlessUnavailable(entry: ListingEntry, lookup: () -> T?): T? =
        try {
            lookup()
        } catch (e: SymbolNotFoundException) {
            null
        } catch (e: SourceUnavailableException) {
            log.warn("BiznesRadar unavailable while creating {}, symbol will be retried later", entry.companyName)
            null
        }
}
