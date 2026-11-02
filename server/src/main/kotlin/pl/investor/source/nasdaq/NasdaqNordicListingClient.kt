package pl.investor.source.nasdaq

import org.springframework.stereotype.Component
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Component
class NasdaqNordicListingClient(private val http: PoliteHttpClient, private val endpoint: String = ENDPOINT) {

    fun page(list: NasdaqList, start: Int): List<NasdaqNewsItem> =
        NasdaqNewsParser.parse(http.get(Source.NASDAQ_NORDIC, url(list, start)))

    internal fun url(list: NasdaqList, start: Int): String =
        "$endpoint?type=json&showAttachments=true&showCnsSpecific=true&showCompany=true&countResults=false" +
            "&globalGroup=exchangeNotice&globalName=NordicAllMarkets&displayLanguage=en&timeZone=CET" +
            "&dateMask=yyyy-MM-dd+HH%3Amm%3Ass&limit=$PAGE_SIZE&start=$start&dir=DESC&market=${list.marketName.encoded()}"

    private fun String.encoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

    companion object {
        const val PAGE_SIZE = 200
        private const val ENDPOINT = "https://api.news.eu.nasdaq.com/news/query.action"
    }
}
