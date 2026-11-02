package pl.investor.source.nasdaq

import pl.investor.support.STOCKHOLM
import org.slf4j.LoggerFactory
import pl.investor.report.Market
import pl.investor.report.ReportRepository
import pl.investor.source.gpw.ListingEntry
import java.time.Clock
import java.time.Duration
import java.time.Instant

class NasdaqNordicPoller(
    private val client: NasdaqNordicListingClient,
    private val reports: ReportRepository,
    private val clock: Clock,
    private val onNewEntry: (ListingEntry) -> Unit,
) {

    private val log = LoggerFactory.getLogger(NasdaqNordicPoller::class.java)

    fun poll(): Int {
        val oldest = oldestToRead()
        val fresh = NasdaqList.entries
            .flatMap { list ->
                runCatching { newItemsOf(list, oldest) }
                    .onFailure { log.warn("Reading Nasdaq Nordic {} news failed, will retry next poll", list, it) }
                    .getOrDefault(emptyList())
            }
            .distinctBy { it.disclosureId }
            .sortedWith(compareBy<NasdaqNewsItem>({ !it.isEnglish }, { it.publishedAt }))
        fresh.map(NasdaqNewsItem::toListingEntry).forEach(onNewEntry)
        return fresh.size
    }

    private fun oldestToRead(): Instant {
        val now = clock.instant()
        return if (reports.existsForMarket(Market.SE)) now.minus(MAX_LOOKBACK) else now.atZone(STOCKHOLM).toLocalDate().atStartOfDay(STOCKHOLM).toInstant()
    }

    private fun newItemsOf(list: NasdaqList, oldest: Instant): List<NasdaqNewsItem> {
        val seen = mutableSetOf<String>()
        val found = mutableListOf<NasdaqNewsItem>()
        var pages = 0
        do {
            val page = client.page(list, pages * NasdaqNordicListingClient.PAGE_SIZE)
            pages++
            val unseen = page.filter { seen.add(it.disclosureId) }
            val ofList = unseen.filter { it.market == list.marketName && !it.publishedAt.isBefore(oldest) }
            val (known, unknown) = ofList.partition { reports.existsBySourceRef(Market.SE, it.disclosureId) }
            found += unknown
            val canContinue = page.size >= NasdaqNordicListingClient.PAGE_SIZE && unseen.isNotEmpty() && known.isEmpty() &&
                page.none { it.publishedAt.isBefore(oldest) } && pages < MAX_PAGES
        } while (canContinue)
        return found
    }

    private companion object {
        val MAX_LOOKBACK: Duration = Duration.ofDays(7)
        const val MAX_PAGES = 10
    }
}
