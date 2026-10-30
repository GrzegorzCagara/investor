package pl.investor.source.gpw

import org.springframework.stereotype.Component
import pl.investor.report.Market
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Component
class EspiListingClient(
    private val http: PoliteHttpClient,
    private val parser: EspiListingParser,
) {

    fun page(market: Market, date: LocalDate, offset: Int): ListingPage =
        parser.parse(http.postForm(market.source(), market.endpoint(), form(market, date, offset)), market)

    internal fun form(market: Market, date: LocalDate, offset: Int): List<Pair<String, String>> =
        when (market) {
            Market.GPW -> gpwForm(offset, date)
            Market.NC -> ncForm(offset, date)
            Market.US -> error("No ESPI listing for $market")
        }

    private fun gpwForm(offset: Int, date: LocalDate) =
        baseForm(offset, date.format(DATE)) + listOf(
            "action" to "GPWEspiReportUnion",
            "start" to "ajaxSearch",
            "page" to "komunikaty",
            "categoryRaports[]" to "ESPI",
        ) + REPORT_TYPES.map { "typeRaports[]" to it }

    private fun ncForm(offset: Int, date: LocalDate) =
        baseForm(offset, date.format(DATE)) + listOf(
            "action" to "GPWEspiReportUnion",
            "start" to "ajaxSearch",
            "page" to "spolki-komunikaty-spolek",
            "categoryRaports[]" to "ESPI",
            "categoryRaports[]" to "EBI",
        )

    private fun baseForm(offset: Int, date: String) = listOf(
        "format" to "html",
        "lang" to "PL",
        "letter" to "",
        "offset" to offset.toString(),
        "limit" to PAGE_SIZE.toString(),
        "searchText" to "",
        "date" to date,
    )

    private fun Market.source() = when (this) {
        Market.GPW -> Source.GPW_LISTING
        Market.NC -> Source.NC_LISTING
        Market.US -> error("No ESPI listing for $this")
    }

    private fun Market.endpoint() = when (this) {
        Market.GPW -> GPW_ENDPOINT
        Market.NC -> "https://newconnect.pl/ajaxindex.php"
        Market.US -> error("No ESPI listing for $this")
    }

    companion object {
        const val PAGE_SIZE = 50
        private const val GPW_ENDPOINT = "https://www.gpw.pl/ajaxindex.php"
        private val REPORT_TYPES = listOf("RB", "Q", "P", "O", "R")
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    }
}
