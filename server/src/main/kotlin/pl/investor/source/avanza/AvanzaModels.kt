package pl.investor.source.avanza

import java.math.BigDecimal
import java.time.LocalDate

data class AvanzaSearchHit(val orderBookId: String, val name: String, val flagCode: String?, val marketPlaceName: String?)

data class AvanzaStock(
    val orderBookId: String,
    val name: String,
    val isin: String,
    val ticker: String?,
    val currency: String?,
    val listing: String?,
) {
    fun identity() = AvanzaIdentity(avanzaId = orderBookId, isin = isin, ticker = ticker, listing = listing, shortName = name, quoteCurrency = currency)
}

data class AvanzaIdentity(
    val avanzaId: String,
    val isin: String,
    val ticker: String?,
    val listing: String?,
    val shortName: String?,
    val quoteCurrency: String?,
)

data class AvanzaQuarter(
    val quarterEnd: LocalDate,
    val reportDate: LocalDate?,
    val salesInThousands: BigDecimal?,
    val netProfitInThousands: BigDecimal?,
    val priceEarnings: BigDecimal?,
    val priceSales: BigDecimal?,
)

class AvanzaStockNotFoundException(companyName: String) : RuntimeException("No Avanza stock for $companyName")
