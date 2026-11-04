package pl.investor.source.biznesradar

import org.springframework.stereotype.Component
import pl.investor.report.Company
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class BiznesRadarClient(
    private val http: PoliteHttpClient,
    private val quarterlyParser: QuarterlyResultsParser,
    private val valuationParser: ValuationParser,
    private val clock: Clock,
) {

    @Volatile
    private var loaded: LoadedDirectory? = null

    private val symbolDirectory: SymbolDirectory
        get() {
            val now = clock.instant()
            loaded?.takeIf { Duration.between(it.loadedAt, now) < DIRECTORY_MAX_AGE }?.let { return it.directory }
            return try {
                SymbolDirectory.parse(http.get(Source.BIZNESRADAR, "$BASE/service-data-js/8")).also { loaded = LoadedDirectory(it, now) }
            } catch (e: SourceUnavailableException) {
                loaded?.directory ?: throw e
            }
        }

    fun quarterly(symbol: String): List<ParsedQuarter> =
        quarterlyParser.parse(http.get(Source.BIZNESRADAR, "$BASE/raporty-finansowe-rachunek-zyskow-i-strat/$symbol,Q"))

    fun valuation(symbol: String): List<ParsedValuation> =
        valuationParser.parse(http.get(Source.BIZNESRADAR, "$BASE/wskazniki-wartosci-rynkowej/$symbol"))

    fun symbolFor(company: Company): String = symbolDirectory.symbolFor(company)

    fun mediumNameFor(companyName: String): String? = symbolDirectory.mediumNameFor(companyName)

    fun tickersBySymbol(): Map<String, String> = symbolDirectory.tickersBySymbol

    private class LoadedDirectory(val directory: SymbolDirectory, val loadedAt: Instant)

    private companion object {
        const val BASE = "https://www.biznesradar.pl"
        val DIRECTORY_MAX_AGE: Duration = Duration.ofDays(1)
    }
}
