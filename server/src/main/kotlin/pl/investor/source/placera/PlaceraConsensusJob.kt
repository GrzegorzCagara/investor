package pl.investor.source.placera

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.investor.source.SourceUnavailableException
import pl.investor.source.avanza.AvanzaClient
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

@Component
class PlaceraConsensusJob(
    private val placera: PlaceraClient,
    private val previews: ConsensusPreviewRepository,
    private val avanza: AvanzaClient,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(PlaceraConsensusJob::class.java)
    private val unparseable = ConcurrentHashMap.newKeySet<String>()

    @Scheduled(cron = "0 0/15 6-21 * * *", zone = "Europe/Stockholm")
    fun duringTheDay() = refresh()

    @Scheduled(cron = "0 0 22 * * *", zone = "Europe/Stockholm")
    fun lastOfTheDay() = refresh()

    fun refresh() {
        val links = try {
            placera.previewLinks()
        } catch (e: SourceUnavailableException) {
            log.warn("Placera telegram listing unavailable: {}", e.message)
            return
        }
        links.filterNot { it in unparseable || previews.exists(it) }.forEach(::fetch)
    }

    private fun fetch(url: String) {
        try {
            val article = placera.article(url)
            previews.save(article.toPreview(url, isinOf(article), clock.instant()))
        } catch (e: SourceUnavailableException) {
            log.warn("Placera preview {} unavailable, retrying on the next run: {}", url, e.message)
        } catch (e: Exception) {
            log.warn("Placera preview {} skipped: {}", url, e.message)
            unparseable += url
        }
    }

    private fun isinOf(article: PlaceraArticle): String =
        article.isin ?: avanza.stock(avanza.findStock(article.companyName).orderBookId).isin
}
