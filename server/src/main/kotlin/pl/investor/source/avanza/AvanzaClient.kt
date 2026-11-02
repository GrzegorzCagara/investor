package pl.investor.source.avanza

import org.springframework.stereotype.Component
import pl.investor.history.DailyQuote
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import tools.jackson.databind.json.JsonMapper
import java.text.Normalizer
import java.time.LocalDate

@Component
class AvanzaClient(private val http: PoliteHttpClient, private val baseUrl: String = BASE_URL) {

    fun findStock(companyName: String): AvanzaSearchHit {
        val query = searchQuery(companyName)
        val hits = AvanzaParser.searchHits(http.postJson(Source.AVANZA, "$baseUrl/_api/search/filtered-search", searchBody(query)))
        return bestMatch(query, hits) ?: throw AvanzaStockNotFoundException(companyName)
    }

    fun stock(orderBookId: String): AvanzaStock =
        AvanzaParser.stock(http.get(Source.AVANZA, "$baseUrl/_api/market-guide/stock/$orderBookId"))

    fun quarters(orderBookId: String): List<AvanzaQuarter> =
        AvanzaParser.quarters(http.get(Source.AVANZA, "$baseUrl/_api/market-guide/stock/$orderBookId/analysis"))

    fun dailyQuotes(orderBookId: String, instrument: String, from: LocalDate, to: LocalDate): List<DailyQuote> =
        AvanzaParser.dailyQuotes(
            http.get(Source.AVANZA, "$baseUrl/_api/price-chart/stock/$orderBookId?from=$from&to=$to&resolution=day"),
            instrument,
        )

    private fun searchBody(query: String): String =
        mapper.writeValueAsString(
            mapOf(
                "query" to query,
                "searchFilter" to mapOf("types" to listOf("STOCK")),
                "pagination" to mapOf("from" to 0, "size" to SEARCH_SIZE),
            ),
        )

    companion object {
        private const val BASE_URL = "https://www.avanza.se"
        private const val SWEDEN = "SE"
        private val COVERED_VENUES = setOf("Stockholmsbörsen", "First North Stockholm")
        private const val SEARCH_SIZE = 5
        private val mapper = JsonMapper.builder().build()
        private val LEGAL_FORM = Regex("\\(publ\\)|(?<![\\p{L}\\p{N}])(AB|publ|Oyj|ASA|plc|Ltd)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        private val PUNCTUATION = Regex("[^\\p{L}\\p{N}&\\s-]+")
        private val WHITESPACE = Regex("\\s+")
        private val DIACRITICS = Regex("\\p{M}+")
        private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

        fun searchQuery(companyName: String): String =
            companyName.replace(LEGAL_FORM, " ").replace(PUNCTUATION, " ").replace(WHITESPACE, " ").trim()

        fun bestMatch(query: String, hits: List<AvanzaSearchHit>): AvanzaSearchHit? {
            val swedish = hits.filter { it.flagCode == SWEDEN && it.marketPlaceName in COVERED_VENUES }
            val wanted = query.comparable().takeIf { it.isNotEmpty() } ?: return null
            return swedish.firstOrNull { it.name.comparable() == wanted }
                ?: swedish.firstOrNull { hit -> hit.name.comparable().let { it.isNotEmpty() && (it.startsWith(wanted) || wanted.startsWith(it)) } }
        }

        private fun String.comparable(): String =
            Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "").replace(NON_ALPHANUMERIC, "")
    }
}
