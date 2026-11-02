package pl.investor.source.placera

import org.springframework.stereotype.Component
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source

@Component
class PlaceraClient(private val http: PoliteHttpClient, private val baseUrl: String = BASE_URL) {

    fun previewLinks(): List<String> = PlaceraParser.previewLinks(http.get(Source.PLACERA, "$baseUrl/telegram"), baseUrl)

    fun article(url: String): PlaceraArticle = PlaceraParser.article(http.get(Source.PLACERA, url))

    private companion object {
        const val BASE_URL = "https://www.placera.se"
    }
}
