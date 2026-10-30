package pl.investor.source.biznesradar

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

data class ParsedQuarter(
    val quarterEnd: LocalDate,
    val revenue: BigDecimal?,
    val operatingProfit: BigDecimal?,
    val netProfit: BigDecimal?,
    val publishedOn: LocalDate?,
)

@Component
class QuarterlyResultsParser {

    fun parse(html: String): List<ParsedQuarter> {
        val table = QuarterTable.parse(html)
        return table.quarterEnds.mapIndexed { column, quarterEnd ->
            ParsedQuarter(
                quarterEnd = quarterEnd,
                revenue = table.number(REVENUE, column),
                operatingProfit = table.number(OPERATING_PROFIT, column),
                netProfit = table.number(NET_PROFIT, column),
                publishedOn = table.date(PUBLISHED_ON, column),
            )
        }
    }

    private companion object {
        const val REVENUE = "IncomeRevenues"
        const val OPERATING_PROFIT = "IncomeEBIT"
        const val NET_PROFIT = "IncomeShareholderNetProfit"
        const val PUBLISHED_ON = "PrimaryReport"
    }
}
