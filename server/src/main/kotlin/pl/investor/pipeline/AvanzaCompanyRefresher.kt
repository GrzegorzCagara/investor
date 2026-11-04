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
import pl.investor.report.Market
import pl.investor.settings.SettingRepository
import pl.investor.source.SourceUnavailableException
import pl.investor.source.avanza.AvanzaClient
import pl.investor.source.avanza.AvanzaQuarter
import pl.investor.source.avanza.AvanzaStock
import pl.investor.source.avanza.AvanzaStockNotFoundException
import pl.investor.source.quotes.AvanzaQuotes
import pl.investor.support.toWarsawDate
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@Component
class AvanzaCompanyRefresher(
    private val avanza: AvanzaClient,
    private val avanzaQuotes: AvanzaQuotes,
    private val companies: CompanyRepository,
    private val quarterResults: QuarterResultRepository,
    private val valuations: ValuationRepository,
    private val quotes: QuoteRepository,
    private val settings: SettingRepository,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(AvanzaCompanyRefresher::class.java)

    fun refresh(company: Company, force: Boolean = false) {
        val companyId = checkNotNull(company.id)
        val today = clock.instant().toWarsawDate()
        val identified = (identification(company) as? AvanzaIdentification.Identified)?.company ?: return
        runCatching { avanzaQuotes.updateIndex(identified.market) }.onFailure { log.warn("{} index quotes update failed: {}", identified.market, it.message) }
        avanzaQuotes.updateCompany(identified)
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

    fun identification(company: Company): AvanzaIdentification {
        val current = companies.findById(checkNotNull(company.id))
        if (current.isIdentified()) return AvanzaIdentification.Identified(current)
        val attemptKey = identityTriedKey(checkNotNull(current.id), clock.instant().toWarsawDate())
        return if (settings.putIfAbsent(attemptKey, IdentityAttempt.TRIED.name)) identify(current, attemptKey) else earlierAttempt(current, attemptKey)
    }

    private fun Company.isIdentified(): Boolean = avanzaId != null && isin != null

    private fun earlierAttempt(company: Company, attemptKey: String): AvanzaIdentification {
        val attempt = settings.find(attemptKey).orEmpty()
        return when {
            attempt == IdentityAttempt.NOT_LISTED.name -> AvanzaIdentification.NotListed
            attempt.unavailableSince()?.let { Duration.between(it, clock.instant()) >= UNAVAILABLE_RETRY_AFTER } == true -> identify(company, attemptKey)
            else -> AvanzaIdentification.Unavailable
        }
    }

    private fun String.unavailableSince(): Instant? =
        takeIf { it.startsWith(UNAVAILABLE_PREFIX) }?.removePrefix(UNAVAILABLE_PREFIX)?.let(Instant::parse)

    private fun identify(company: Company, attemptKey: String): AvanzaIdentification {
        val companyId = checkNotNull(company.id)
        val stock = try {
            coveredStock(company)
        } catch (e: AvanzaStockNotFoundException) {
            log.info("{} has no share covered by Avanza in {}, next identification attempt tomorrow", company.name, company.market)
            settings.put(attemptKey, IdentityAttempt.NOT_LISTED.name)
            return AvanzaIdentification.NotListed
        } catch (e: SourceUnavailableException) {
            log.warn("Avanza unavailable while identifying {}, retrying in {} min", company.name, UNAVAILABLE_RETRY_AFTER.toMinutes())
            settings.put(attemptKey, UNAVAILABLE_PREFIX + clock.instant())
            return AvanzaIdentification.Unavailable
        }
        companies.updateAvanzaIdentity(companyId, stock.identity())
        return companies.findById(companyId).takeIf { it.isIdentified() }?.let(AvanzaIdentification::Identified) ?: AvanzaIdentification.Unavailable
    }

    private fun coveredStock(company: Company): AvanzaStock = when (company.market) {
        Market.DE -> avanza.findStockByIsin(company.isin ?: throw AvanzaStockNotFoundException(company.name))
        else -> avanza.stock(avanza.findStock(company.name).orderBookId)
    }

    private fun refreshKey(companyId: Long) = "AVANZA_REFRESHED_$companyId"

    private fun identityTriedKey(companyId: Long, day: LocalDate) = "AVANZA_IDENTITY_TRIED_${companyId}_$day"

    private enum class IdentityAttempt { TRIED, NOT_LISTED }

    private companion object {
        const val MAX_QUOTE_GAP_DAYS = 10L
        const val UNAVAILABLE_PREFIX = "UNAVAILABLE_AT_"
        val UNAVAILABLE_RETRY_AFTER: Duration = Duration.ofMinutes(15)
    }
}
