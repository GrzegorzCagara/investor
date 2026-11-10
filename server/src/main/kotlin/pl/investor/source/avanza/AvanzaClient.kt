package pl.investor.source.avanza

import org.springframework.stereotype.Component
import pl.investor.history.DailyQuote
import pl.investor.report.CompanyNames
import pl.investor.report.Market
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

@Component
class AvanzaClient(private val http: PoliteHttpClient, private val baseUrl: String = BASE_URL) {

    fun findStock(companyName: String): AvanzaSearchHit = findShareClasses(companyName, Market.SE).first()

    fun findShareClasses(companyName: String, market: Market): List<AvanzaSearchHit> {
        val wanted = searchQuery(companyName)
        return searchQueries(companyName).asSequence()
            .map { matches(wanted, search(it), market) }
            .firstOrNull { it.isNotEmpty() }
            ?: throw AvanzaStockNotFoundException(companyName)
    }

    fun findStockByIsin(isin: String, market: Market): AvanzaStock =
        isinCandidates(search(isin), market).asSequence()
            .take(MAX_ISIN_CANDIDATES)
            .map { stock(it.orderBookId) }
            .firstOrNull { it.isin == isin }
            ?: throw AvanzaStockNotFoundException(isin)

    fun findStockByTicker(ticker: String, market: Market): AvanzaStock {
        val venues = COVERED_VENUES.getValue(market)
        return search(ticker).asSequence()
            .filter { it.flagCode == venues.flagCode && it.marketPlaceName in venues.marketPlaces }
            .take(MAX_ISIN_CANDIDATES)
            .map { stock(it.orderBookId) }
            .firstOrNull { it.ticker.equals(ticker, ignoreCase = true) }
            ?: throw AvanzaStockNotFoundException(ticker)
    }

    private fun isinCandidates(hits: List<AvanzaSearchHit>, market: Market): List<AvanzaSearchHit> = when (market) {
        Market.NL -> hits.filter { it.marketPlaceName == DUTCH_VENUE }
        else -> hits.sortedByDescending { it.marketPlaceName == GERMAN_VENUE }
    }

    private fun search(query: String): List<AvanzaSearchHit> =
        AvanzaParser.searchHits(http.postJson(Source.AVANZA, "$baseUrl/_api/search/filtered-search", searchBody(query)))

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
        private val COVERED_VENUES = mapOf(
            Market.SE to NameSearchVenues("SE", setOf("Stockholmsbörsen", "First North Stockholm")),
            Market.FI to NameSearchVenues("FI", setOf("Helsingforsbörsen", "First North Helsingfors")),
        )
        private const val GERMAN_VENUE = "Xetra"
        private const val DUTCH_VENUE = "Euronext Amsterdam"
        private const val MAX_ISIN_CANDIDATES = 3
        private const val SEARCH_SIZE = 5
        private const val MAX_SEARCHES = 3
        private const val MIN_FIRST_WORD_LETTERS = 4
        private val GENERIC_TRAILING_WORDS = setOf("group", "holding", "holdings", "bank")
        private val SHARE_CLASS_WORD = Regex("[a-z]{1,2}|\\p{L}*osake")
        private val FIRST_WORD =Regex("^[\\p{L}\\p{N}]+")
        private val mapper = JsonMapper.builder().build()

        fun searchQuery(companyName: String): String = CompanyNames.withoutLegalForm(companyName)

        fun searchQueries(companyName: String): List<String> {
            val full = searchQuery(companyName)
            val withoutGenericWords = full.split(" ").dropLastWhile { it.lowercase() in GENERIC_TRAILING_WORDS }.joinToString(" ")
            val firstWord = FIRST_WORD.find(companyName.trim())?.value?.takeIf { word -> word.count(Char::isLetter) >= MIN_FIRST_WORD_LETTERS }
            return listOfNotNull(full, withoutGenericWords, firstWord).filter { it.isNotBlank() }.distinct().take(MAX_SEARCHES)
        }

        fun matches(query: String, hits: List<AvanzaSearchHit>, market: Market): List<AvanzaSearchHit> {
            val venues = COVERED_VENUES.getValue(market)
            val covered = hits.filter { it.flagCode == venues.flagCode && it.marketPlaceName in venues.marketPlaces }
            val wanted = CompanyNames.key(query).takeIf { it.isNotEmpty() } ?: return emptyList()
            val byName = covered.filter { CompanyNames.key(it.name) == wanted }
                .ifEmpty { covered.filter { hit -> CompanyNames.key(hit.name).let { it.isNotEmpty() && (it.startsWith(wanted) || wanted.startsWith(it)) } } }
            return if (market == Market.FI) shareClassesOf(query, covered).ifEmpty { byName.take(1) } else byName
        }

        private fun shareClassesOf(query: String, hits: List<AvanzaSearchHit>): List<AvanzaSearchHit> {
            val wanted = words(query)
            return hits.filter { hit ->
                val name = words(hit.name)
                name.take(wanted.size) == wanted && name.drop(wanted.size).let { extra -> extra.isEmpty() || (extra.size == 1 && SHARE_CLASS_WORD.matches(extra.single())) }
            }
        }

        private fun words(name: String): List<String> = CompanyNames.withoutLegalForm(name).split(" ").map(CompanyNames::key).filter { it.isNotEmpty() }
    }

    private data class NameSearchVenues(val flagCode: String, val marketPlaces: Set<String>)
}
