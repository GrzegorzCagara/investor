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
)
