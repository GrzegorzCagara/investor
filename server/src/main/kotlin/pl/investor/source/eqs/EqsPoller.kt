package pl.investor.source.eqs

import pl.investor.report.Market
import pl.investor.report.ReportRepository
import pl.investor.source.gpw.ListingEntry
import pl.investor.support.BERLIN
import java.time.Clock
import java.time.Duration
import java.time.Instant

class EqsPoller(
    private val client: EqsListingClient,
    private val reports: ReportRepository,
    private val clock: Clock,
    private val onNewEntry: (ListingEntry) -> Unit,
) {

    fun poll(): Int {
        val fresh = newItems(oldestToRead()).sortedBy { it.publishedAt }
        fresh.map(EqsNewsItem::toListingEntry).forEach(onNewEntry)
        return fresh.size
    }

    private fun oldestToRead(): Instant {
        val now = clock.instant()
        return if (reports.existsForMarket(Market.DE)) now.minus(MAX_LOOKBACK) else now.atZone(BERLIN).toLocalDate().atStartOfDay(BERLIN).toInstant()
    }

    private fun newItems(oldest: Instant): List<EqsNewsItem> {
        val seen = mutableSetOf<String>()
        val found = mutableListOf<EqsNewsItem>()
        var pages = 0
        do {
            val page = client.page(pages + 1)
            pages++
            val unseen = page.filter { seen.add(it.newsId) }
            val covered = unseen.filter { it.isCovered && !it.publishedAt.isBefore(oldest) }
            val (known, unknown) = covered.partition { reports.existsBySourceRef(Market.DE, it.newsId) }
            found += unknown
            val canContinue = page.size >= EqsListingClient.PAGE_SIZE && unseen.isNotEmpty() && known.isEmpty() &&
                page.none { it.publishedAt.isBefore(oldest) } && pages < MAX_PAGES
        } while (canContinue)
        return found
    }

    private companion object {
        val MAX_LOOKBACK: Duration = Duration.ofDays(7)
        const val MAX_PAGES = 10
    }
}
