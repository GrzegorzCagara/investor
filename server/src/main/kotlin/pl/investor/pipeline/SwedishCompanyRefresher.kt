package pl.investor.pipeline

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.investor.history.QuarterResult
import pl.investor.history.QuarterResultRepository
import pl.investor.history.QuoteRepository
import pl.investor.history.ValuationPoint
import pl.investor.history.ValuationRepository
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.settings.SettingRepository
import pl.investor.source.SourceUnavailableException
import pl.investor.source.avanza.AvanzaClient
import pl.investor.source.avanza.AvanzaQuarter
import pl.investor.source.avanza.AvanzaStockNotFoundException
import pl.investor.source.quotes.SwedishQuotes
import pl.investor.support.toWarsawDate
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

@Component
class SwedishCompanyRefresher(
    private val avanza: AvanzaClient,
    private val swedishQuotes: SwedishQuotes,
    private val companies: CompanyRepository,
    private val quarterResults: QuarterResultRepository,
    private val valuations: ValuationRepository,
    private val quotes: QuoteRepository,
    private val settings: SettingRepository,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(SwedishCompanyRefresher::class.java)

    fun refresh(company: Company, force: Boolean = false) {
        val companyId = checkNotNull(company.id)
        val today = clock.instant().toWarsawDate()
        val identified = (identification(company) as? SwedishIdentification.Identified)?.company ?: return
        runCatching { swedishQuotes.updateIndex() }.onFailure { log.warn("OMXSPI quotes update failed: {}", it.message) }
        swedishQuotes.updateCompany(identified)
        val lastRefresh = settings.find(refreshKey(companyId))?.let(LocalDate::parse)
        if (!force && lastRefresh != null && !lastRefresh.isBefore(today)) return
        avanza.quarters(checkNotNull(identified.avanzaId)).forEach { save(companyId, checkNotNull(identified.isin), it) }
        settings.put(refreshKey(companyId), today.toString())
    }

    private fun save(companyId: Long, isin: String, quarter: AvanzaQuarter) {
        quarterResults.save(
            QuarterResult(companyId, quarter.quarterEnd, quarter.salesInThousands, null, quarter.netProfitInThousands, quarter.reportDate),
        )
        valuations.save(ValuationPoint(companyId, quarter.quarterEnd, closeAt(isin, quarter.quarterEnd), null, quarter.priceEarnings, quarter.priceSales))
    }

    private fun closeAt(isin: String, quarterEnd: LocalDate): BigDecimal? =
        quotes.closesUpTo(isin, quarterEnd, 1).firstOrNull()?.takeIf { it.date.isAfter(quarterEnd.minusDays(MAX_QUOTE_GAP_DAYS)) }?.close

    fun identification(company: Company): SwedishIdentification {
        val current = companies.findById(checkNotNull(company.id))
        if (current.isIdentified()) return SwedishIdentification.Identified(current)
        val attemptKey = identityTriedKey(checkNotNull(current.id), clock.instant().toWarsawDate())
        return if (settings.putIfAbsent(attemptKey, IdentityAttempt.TRIED.name)) identify(current, attemptKey) else earlierAttempt(attemptKey)
    }

    private fun Company.isIdentified(): Boolean = avanzaId != null && isin != null

    private fun earlierAttempt(attemptKey: String): SwedishIdentification =
        if (settings.find(attemptKey) == IdentityAttempt.NOT_LISTED.name) SwedishIdentification.NotListed else SwedishIdentification.Unavailable

    private fun identify(company: Company, attemptKey: String): SwedishIdentification {
        val companyId = checkNotNull(company.id)
        val stock = try {
            avanza.stock(avanza.findStock(company.name).orderBookId)
        } catch (e: AvanzaStockNotFoundException) {
            log.info("{} has no share on Stockholmsbörsen or First North Stockholm, its releases are skipped until tomorrow", company.name)
            settings.put(attemptKey, IdentityAttempt.NOT_LISTED.name)
            return SwedishIdentification.NotListed
        } catch (e: SourceUnavailableException) {
            log.warn("Avanza unavailable while identifying {}, retrying tomorrow", company.name)
            return SwedishIdentification.Unavailable
        }
        companies.updateAvanzaIdentity(companyId, stock.identity())
        return companies.findById(companyId).takeIf { it.isIdentified() }?.let(SwedishIdentification::Identified) ?: SwedishIdentification.Unavailable
    }

    private fun refreshKey(companyId: Long) = "AVANZA_REFRESHED_$companyId"

    private fun identityTriedKey(companyId: Long, day: LocalDate) = "AVANZA_IDENTITY_TRIED_${companyId}_$day"

    private enum class IdentityAttempt { TRIED, NOT_LISTED }

    private companion object {
        const val MAX_QUOTE_GAP_DAYS = 10L
    }
}
