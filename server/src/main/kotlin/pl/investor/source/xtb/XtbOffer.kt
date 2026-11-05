package pl.investor.source.xtb

import pl.investor.report.CompanyNames

class XtbOffer(stocks: Map<XtbCountry, List<XtbStock>>) {

    private val tickers = stocks.mapValues { (_, list) -> list.mapTo(HashSet()) { it.ticker.tickerKey() } }
    private val names = stocks.mapValues { (_, list) -> list.mapTo(HashSet()) { CompanyNames.keyWithoutLegalForm(it.companyName) } - "" }

    fun lists(country: XtbCountry, tickers: List<String>, companyName: String): Boolean =
        tickers.any { it.tickerKey() in this.tickers[country].orEmpty() } ||
            CompanyNames.keyWithoutLegalForm(companyName).let { it.isNotEmpty() && it in names[country].orEmpty() }

    fun size(country: XtbCountry): Int = tickers[country].orEmpty().size

    private fun String.tickerKey() = uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
}
