package pl.investor.source.euronext

import org.springframework.stereotype.Component
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source

@Component
class EuronextClient(
    private val http: PoliteHttpClient,
    private val parser: EuronextReleaseParser,
    private val baseUrl: String = BASE_URL,
) {

    fun page(pageNo: Int): List<EuronextRow> = EuronextListingParser.parse(http.get(Source.EURONEXT, listingUrl(pageNo), AJAX_HEADERS))

    fun issuer(nid: String): EuronextIssuer = parser.issuer(http.get(Source.EURONEXT, releaseUrl(nid)))

    fun releaseUrl(nid: String): String = "$baseUrl/en/ajax/node/company-press-release/$nid"

    internal fun listingUrl(pageNo: Int): String =
        "$baseUrl/en/views/ajax?view_name=company_press_releases_view&view_display_id=page_1&view_args=$AMSTERDAM_MARKET" +
            "&view_path=%2Flistview%2Fcompany-press-releases-by-mkt%2F$AMSTERDAM_MARKET&pager_element=0&page=$pageNo&_drupal_ajax=1"

    companion object {
        const val PAGE_SIZE = 50
        private val AJAX_HEADERS = mapOf("X-Requested-With" to "XMLHttpRequest")
        private const val AMSTERDAM_MARKET = 404
        private const val BASE_URL = "https://live.euronext.com"
    }
}
