package pl.investor.source.avanza

import pl.investor.support.STOCKHOLM
import pl.investor.history.DailyQuote
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

object AvanzaParser {

    private val mapper = JsonMapper.builder().build()
    private val QUARTER_END_MONTHS = mapOf("Q1" to 3, "Q2" to 6, "Q3" to 9, "Q4" to 12)
    private val THOUSAND = BigDecimal(1000)

    fun searchHits(json: String): List<AvanzaSearchHit> =
        mapper.readTree(json).path("hits").elements().map { hit ->
            AvanzaSearchHit(
                orderBookId = hit.path("orderBookId").asString(),
                name = hit.path("title").asString("").substringBefore(" (").trim(),
                flagCode = hit.path("flagCode").textOrNull(),
                marketPlaceName = hit.path("marketPlaceName").textOrNull(),
            )
        }

    fun stock(json: String): AvanzaStock {
        val root = mapper.readTree(json)
        val listing = root.path("listing")
        return AvanzaStock(
            orderBookId = root.path("orderbookId").asString(),
            name = root.path("name").asString(""),
            isin = root.path("isin").textOrNull() ?: throw IllegalArgumentException("Avanza stock without ISIN"),
            ticker = listing.path("tickerSymbol").textOrNull(),
            currency = listing.path("currency").textOrNull(),
            listing = listing.path("marketListName").textOrNull(),
        )
    }

    fun quarters(json: String): List<AvanzaQuarter> {
        val root = mapper.readTree(json)
        val financials = root.path("companyFinancialsByQuarterQuarter")
        val ratios = root.path("stockKeyRatiosByQuarter")
        val sales = financials.path("sales").byQuarterEnd()
        val netProfit = financials.path("netProfit").byQuarterEnd()
        val pe = ratios.path("priceEarningsRatio").byQuarterEnd()
        val ps = ratios.path("priceSalesRatio").byQuarterEnd()
        val series = listOf(sales, netProfit, pe, ps)
        return series.flatMap { it.keys }.distinct().sortedDescending().map { quarterEnd ->
            AvanzaQuarter(
                quarterEnd = quarterEnd,
                reportDate = series.firstNotNullOfOrNull { it[quarterEnd]?.takeIf(QuarterValue::isReported)?.date },
                salesInThousands = sales[quarterEnd]?.value?.divide(THOUSAND),
                netProfitInThousands = netProfit[quarterEnd]?.value?.divide(THOUSAND),
                priceEarnings = pe[quarterEnd]?.value,
                priceSales = ps[quarterEnd]?.value,
            )
        }
    }

    fun dailyQuotes(json: String, instrument: String): List<DailyQuote> =
        mapper.readTree(json).path("ohlc").elements().mapNotNull { bar ->
            val close = bar.path("close").decimalOrNull() ?: return@mapNotNull null
            val volume = bar.path("totalVolumeTraded").asLong(0)
            DailyQuote(
                instrument = instrument,
                date = Instant.ofEpochMilli(bar.path("timestamp").asLong()).atZone(STOCKHOLM).toLocalDate(),
                close = close,
                volume = volume,
                turnover = (close * BigDecimal(volume)).setScale(2, RoundingMode.HALF_UP),
            )
        }

    private data class QuarterValue(val date: LocalDate?, val value: BigDecimal?) {
        val isReported: Boolean get() = value != null
    }

    private fun JsonNode.byQuarterEnd(): Map<LocalDate, QuarterValue> =
        elements().mapNotNull { node ->
            val month = QUARTER_END_MONTHS[node.path("reportType").asString("")] ?: return@mapNotNull null
            val year = node.path("financialYear").asInt(0).takeIf { it > 0 } ?: return@mapNotNull null
            YearMonth.of(year, month).atEndOfMonth() to QuarterValue(
                node.path("date").textOrNull()?.let(LocalDate::parse),
                node.path("value").decimalOrNull(),
            )
        }.toMap()

    private fun JsonNode.elements(): List<JsonNode> = iterator().asSequence().toList()

    private fun JsonNode.textOrNull(): String? = takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }

    private fun JsonNode.decimalOrNull(): BigDecimal? = takeIf { it.isNumber }?.decimalValue()
}
