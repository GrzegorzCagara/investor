package pl.investor.pipeline

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.investor.history.QuarterResult
import pl.investor.history.QuarterResultRepository
import pl.investor.history.ValuationPoint
import pl.investor.history.ValuationRepository
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.settings.SettingRepository
import pl.investor.source.SourceUnavailableException
import pl.investor.source.biznesradar.BiznesRadarClient
import pl.investor.source.biznesradar.SymbolNotFoundException
import pl.investor.support.toWarsawDate
import java.time.Clock
import java.time.LocalDate

@Component
class CompanyDataRefresher(
    private val biznesRadar: BiznesRadarClient,
    private val companies: CompanyRepository,
    private val quarterResults: QuarterResultRepository,
    private val valuations: ValuationRepository,
    private val settings: SettingRepository,
    private val clock: Clock,
    private val avanza: AvanzaCompanyRefresher,
) {

    fun refresh(company: Company, force: Boolean = false) {
        if (company.market.isCoveredByAvanza) avanza.refresh(company, force) else refreshFromBiznesRadar(company, force)
    }

    private val log = LoggerFactory.getLogger(CompanyDataRefresher::class.java)

    fun avanzaIdentification(company: Company): AvanzaIdentification =
        runCatching { avanza.identification(company) }
            .onFailure { log.warn("Identifying {} failed, it will be retried", company.name, it) }
            .getOrDefault(AvanzaIdentification.Unavailable)

    fun tradingIdentity(company: Company): TradingIdentity = when {
        company.market.isCoveredByAvanza -> when (avanzaIdentification(company)) {
            is AvanzaIdentification.Identified -> TradingIdentity.KNOWN
            AvanzaIdentification.NotListed -> TradingIdentity.NOT_FOUND
            AvanzaIdentification.Unavailable -> TradingIdentity.UNAVAILABLE
        }
        company.market in POLISH_MARKETS -> biznesRadarIdentity(company)
        else -> TradingIdentity.NOT_FOUND
    }

    private fun biznesRadarIdentity(company: Company): TradingIdentity {
        if (company.biznesradarSymbol != null) return TradingIdentity.KNOWN
        val symbol = try {
            biznesRadar.symbolFor(company)
        } catch (e: SymbolNotFoundException) {
            return TradingIdentity.NOT_FOUND
        } catch (e: SourceUnavailableException) {
            log.warn("BiznesRadar unavailable while identifying {}, it will be retried", company.name)
            return TradingIdentity.UNAVAILABLE
        }
        val shortName = runCatching { biznesRadar.mediumNameFor(company.name) }.getOrNull()
        companies.updateBiznesradarIdentity(checkNotNull(company.id), symbol, shortName)
        return TradingIdentity.KNOWN
    }

    private fun refreshFromBiznesRadar(company: Company, force: Boolean) {
        val companyId = checkNotNull(company.id)
        val today = clock.instant().toWarsawDate()
        val symbol = company.biznesradarSymbol ?: lookUpSymbolOncePerDay(company, today) ?: return
        val lastRefresh = settings.find(refreshKey(companyId))?.let(LocalDate::parse)
        if (!force && lastRefresh != null && !lastRefresh.isBefore(today)) return
        biznesRadar.quarterly(symbol).forEach {
            quarterResults.save(QuarterResult(companyId, it.quarterEnd, it.revenue, it.operatingProfit, it.netProfit, it.publishedOn))
        }
        biznesRadar.valuation(symbol).forEach {
            valuations.save(ValuationPoint(companyId, it.quarterEnd, it.price, it.shares?.toLong(), it.priceToEarnings, it.priceToRevenue))
        }
        settings.put(refreshKey(companyId), today.toString())
    }

    private fun lookUpSymbolOncePerDay(company: Company, today: LocalDate): String? {
        val companyId = checkNotNull(company.id)
        if (!settings.putIfAbsent(symbolTriedKey(companyId, today), today.toString())) return null
        val symbol = try {
            biznesRadar.symbolFor(company)
        } catch (e: SymbolNotFoundException) {
            return null
        } catch (e: SourceUnavailableException) {
            return null
        }
        val shortName = try {
            biznesRadar.mediumNameFor(company.name)
        } catch (e: SourceUnavailableException) {
            null
        }
        companies.updateBiznesradarIdentity(companyId, symbol, shortName)
        return symbol
    }

    private fun refreshKey(companyId: Long) = "BR_REFRESHED_$companyId"

    private fun symbolTriedKey(companyId: Long, day: LocalDate) = "BR_SYMBOL_TRIED_${companyId}_$day"

    private companion object {
        val POLISH_MARKETS = setOf(Market.GPW, Market.NC)
    }
}

enum class TradingIdentity { KNOWN, NOT_FOUND, UNAVAILABLE }
