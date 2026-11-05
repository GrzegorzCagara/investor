package pl.investor.report

data class Company(
    val id: Long?,
    val name: String,
    val isin: String?,
    val market: Market,
    val ticker: String?,
    val biznesradarSymbol: String?,
    val publishesMonthlySales: Boolean,
    val shortName: String? = null,
    val avanzaId: String? = null,
    val listing: String? = null,
    val quoteCurrency: String? = null,
    val availableOnXtb: Boolean = false,
    val exchangeTicker: String? = null,
) {
    fun isQuotedInMarketCurrency(): Boolean = quoteCurrency == null || quoteCurrency == market.currency
}
