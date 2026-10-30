package pl.investor.source.biznesradar

import org.springframework.stereotype.Component
import pl.investor.report.Company
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source

@Component
class BiznesRadarClient(
    private val http: PoliteHttpClient,
    private val quarterlyParser: QuarterlyResultsParser,
    private val valuationParser: ValuationParser,
) {

    private val symbolDirectory: SymbolDirectory by lazy {
        SymbolDirectory.parse(http.get(Source.BIZNESRADAR, "$BASE/service-data-js/8"))
    }

    fun quarterly(symbol: String): List<ParsedQuarter> =
        quarterlyParser.parse(http.get(Source.BIZNESRADAR, "$BASE/raporty-finansowe-rachunek-zyskow-i-strat/$symbol,Q"))

    fun valuation(symbol: String): List<ParsedValuation> =
        valuationParser.parse(http.get(Source.BIZNESRADAR, "$BASE/wskazniki-wartosci-rynkowej/$symbol"))

    fun symbolFor(company: Company): String = symbolDirectory.symbolFor(company)

    fun mediumNameFor(companyName: String): String? = symbolDirectory.mediumNameFor(companyName)

    private companion object {
        const val BASE = "https://www.biznesradar.pl"
    }
}
