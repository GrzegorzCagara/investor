package pl.investor.source.biznesradar

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

data class ParsedValuation(
    val quarterEnd: LocalDate,
    val price: BigDecimal?,
    val shares: BigDecimal?,
    val priceToEarnings: BigDecimal?,
    val priceToRevenue: BigDecimal?,
)

@Component
class ValuationParser {

    fun parse(html: String): List<ParsedValuation> {
        val table = QuarterTable.parse(html)
        return table.quarterEnds.mapIndexed { column, quarterEnd ->
            ParsedValuation(
                quarterEnd = quarterEnd,
                price = table.number(PRICE, column)?.takeIf { it.signum() > 0 },
                shares = table.number(SHARES, column),
                priceToEarnings = table.number(PRICE_TO_EARNINGS, column),
                priceToRevenue = table.number(PRICE_TO_REVENUE, column),
            )
        }
    }

    private companion object {
        const val PRICE = "Quote"
        const val SHARES = "ShareAmount"
        const val PRICE_TO_EARNINGS = "CZ"
        const val PRICE_TO_REVENUE = "CP"
    }
}
