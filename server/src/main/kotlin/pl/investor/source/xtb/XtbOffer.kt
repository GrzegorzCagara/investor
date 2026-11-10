package pl.investor.source.xtb

import pl.investor.report.CompanyNames

class XtbOffer(private val stocks: Map<XtbCountry, List<XtbStock>>) {

    private val tickers = stocks.mapValues { (_, list) -> list.mapTo(HashSet()) { it.ticker.tickerKey() } }
    private val names = stocks.mapValues { (_, list) -> list.flatMapTo(HashSet()) { it.nameKeys() } - "" }

    fun lists(countries: List<XtbCountry>, tickers: List<String>, companyName: String): Boolean {
        val tickerKeys = tickers.flatMap { listOf(it, it.replace(TRAILING_CURRENCY, "")) }.map { it.tickerKey() }.toSet()
        val nameKey = CompanyNames.keyWithoutLegalForm(companyName)
        return countries.any { country ->
            tickerKeys.any { it in this.tickers[country].orEmpty() } || (nameKey.isNotEmpty() && nameKey in names[country].orEmpty())
        }
    }

    fun listsTicker(country: XtbCountry, ticker: String): Boolean = ticker.tickerKey() in tickers[country].orEmpty()

    fun tickerNamed(country: XtbCountry, companyName: String): String? {
        val nameKey = CompanyNames.keyWithoutLegalForm(companyName).takeIf { it.isNotEmpty() } ?: return null
        return stocks[country].orEmpty().firstOrNull { nameKey in it.nameKeys() }?.ticker
    }

    fun size(country: XtbCountry): Int = tickers[country].orEmpty().size

    private fun XtbStock.nameKeys(): List<String> =
        listOfNotNull(companyName, companyName.replace(SHARE_CLASS_SUFFIX, ""), friendlyName).map(CompanyNames::keyWithoutLegalForm)

    private fun String.tickerKey() = uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    private companion object {
        val TRAILING_CURRENCY = Regex("\\s+(SEK|EUR|DKK|NOK|USD)\\s*$", RegexOption.IGNORE_CASE)
        val SHARE_CLASS_SUFFIX = Regex("\\s+-\\s+class\\s+\\w+\\s*$", RegexOption.IGNORE_CASE)
    }
}
