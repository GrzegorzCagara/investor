package pl.investor.source.eqs

import org.springframework.stereotype.Component
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source

@Component
class EqsListingClient(private val http: PoliteHttpClient, private val endpoint: String = ENDPOINT) {

    fun page(pageNo: Int): List<EqsNewsItem> = EqsListingParser.parse(http.get(Source.EQS, url(pageNo)))

    internal fun url(pageNo: Int): String =
        "$endpoint?lang=en&action=fetch_realtime_news_data&recordsFrom%5B0%5D%5Bapi_type%5D=news&recordsFrom%5B0%5D%5Bcategory%5D=" +
            "&pageLimit=$PAGE_SIZE&pageNo=$pageNo&additional%5Bis_new%5D=false&loadFrom=mysql"

    companion object {
        const val PAGE_SIZE = 100
        private const val ENDPOINT = "https://www.eqs-news.com/wp/wp-admin/admin-ajax.php"
    }
}
