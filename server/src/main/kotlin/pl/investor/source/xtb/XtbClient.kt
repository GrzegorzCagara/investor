package pl.investor.source.xtb

import org.springframework.stereotype.Component
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

enum class XtbCountry { PL, SE }

data class XtbStock(val ticker: String, val companyName: String)

class UnexpectedXtbListException(message: String) : RuntimeException(message)

@Component
class XtbClient(private val http: PoliteHttpClient, private val baseUrl: String = BASE_URL) {

    fun stocks(country: XtbCountry): List<XtbStock> =
        parse(http.get(Source.XTB, "$baseUrl$SPECIFICATION_PATH?country=${country.name}&pageSize=$PAGE_SIZE"), country)

    companion object {
        private const val BASE_URL = "https://www.xtb.com"
        private const val SPECIFICATION_PATH = "/web-api/v3/languages/pl/branches/pl/instrument-specification"
        private const val PAGE_SIZE = 5000
        private const val STOCKS = "cashstocks"
        private const val CLOSE_ONLY_NAME_PREFIX = "CLOSE ONLY"
        private val mapper = JsonMapper.builder().build()

        fun parse(json: String, country: XtbCountry): List<XtbStock> {
            val root = mapper.readTree(json)
            val stocks = root.path("items").filter { it.path("typeSlug").asString("") == STOCKS }
            val expected = root.path("instrumentCounts").path(STOCKS).asInt(0)
            if (stocks.size != expected) throw UnexpectedXtbListException("XTB returned ${stocks.size} of $expected stocks for $country")
            val suffix = ".${country.name}"
            return stocks
                .filter { it.path("symbol").asString("").endsWith(suffix) && !it.isCloseOnly() }
                .map { XtbStock(it.path("symbol").asString().removeSuffix(suffix), it.path("companyName").asString("")) }
                .ifEmpty { throw UnexpectedXtbListException("XTB list has no tradable stocks for $country") }
        }

        private fun JsonNode.isCloseOnly(): Boolean =
            path("closeOnly").asBoolean(false) || path("companyName").asString("").trimStart().startsWith(CLOSE_ONLY_NAME_PREFIX, ignoreCase = true)
    }
}
