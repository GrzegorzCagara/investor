package pl.investor.source.euronext

import org.slf4j.LoggerFactory
import org.springframework.web.client.HttpClientErrorException
import pl.investor.report.Market
import pl.investor.report.ReportRepository
import pl.investor.source.SourceUnavailableException
import pl.investor.source.gpw.ListingEntry
import pl.investor.support.AMSTERDAM
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class EuronextPoller(
    private val client: EuronextClient,
    private val reports: ReportRepository,
    private val clock: Clock,
    private val onNewEntry: (ListingEntry) -> Unit,
) {

    private val log = LoggerFactory.getLogger(EuronextPoller::class.java)
    private val dropped = ConcurrentHashMap<String, Instant>()

    fun poll(): Int {
        val now = clock.instant()
        dropped.values.removeIf { it.isBefore(now.minus(MAX_LOOKBACK)) }
        val fresh = withoutDuplicates(listedShares(newRows(oldestToRead(now)).sortedBy { it.publishedAt }))
        fresh.map(EuronextNewsItem::toListingEntry).forEach(onNewEntry)
        return fresh.size
    }

    private fun oldestToRead(now: Instant): Instant =
        if (reports.existsForMarket(Market.NL)) now.minus(MAX_LOOKBACK) else now.atZone(AMSTERDAM).toLocalDate().atStartOfDay(AMSTERDAM).toInstant()

    private fun newRows(oldest: Instant): List<EuronextRow> {
        val seen = mutableSetOf<String>()
        val found = mutableListOf<EuronextRow>()
        var pages = 0
        do {
            val page = client.page(pages)
            pages++
            val unseen = page.filter { seen.add(it.nid) }
            val recent = unseen.filter { !it.publishedAt.isBefore(oldest) }
            val (known, unknown) = recent.partition { dropped.containsKey(it.nid) || reports.existsBySourceRef(Market.NL, it.nid) }
            found += unknown
            val canContinue = page.size >= EuronextClient.PAGE_SIZE && unseen.isNotEmpty() && known.isEmpty() &&
                page.none { it.publishedAt.isBefore(oldest) } && pages < MAX_PAGES
        } while (canContinue)
        return found
    }

    private fun listedShares(rows: List<EuronextRow>): List<EuronextNewsItem> {
        val items = mutableListOf<EuronextNewsItem>()
        var withoutIdentity = 0
        var read = 0
        for (row in rows) {
            val issuer = try {
                client.issuer(row.nid)
            } catch (e: Exception) {
                if (e.isGone()) {
                    log.error("Euronext release {} ({}) is gone and is skipped: {}", row.nid, row.title, e.message)
                    drop(row)
                    continue
                }
                log.warn("Euronext release {} could not be read, it and newer releases wait for the next poll: {}", row.nid, e.message)
                break
            }
            read++
            val isin = issuer.isin
            val ticker = issuer.ticker
            if (isin != null && ticker != null) {
                items += EuronextNewsItem(row, isin, ticker, client.releaseUrl(row.nid))
            } else {
                log.info("Euronext release {} ({}, {}) has no ISIN or Symbol and is not stored", row.nid, row.company, row.title)
                withoutIdentity++
                drop(row)
            }
        }
        if (read >= MIN_ROWS_FOR_LAYOUT_CHECK && withoutIdentity == read) {
            log.warn("None of {} new Euronext releases had an ISIN and Symbol, the release page layout may have changed", read)
        }
        return items
    }

    private fun Exception.isGone(): Boolean =
        this is SourceUnavailableException && (cause as? HttpClientErrorException)?.statusCode?.value() in GONE_STATUSES

    private fun withoutDuplicates(items: List<EuronextNewsItem>): List<EuronextNewsItem> {
        val accepted = mutableListOf<EuronextNewsItem>()
        items.forEach { item ->
            val release = item.published()
            val pair = accepted.firstOrNull { EuronextDuplicates.isLanguagePair(release, it.published()) }
            when {
                isStoredAlready(release) || accepted.any { EuronextDuplicates.isSameRelease(release, it.published()) } -> drop(item.row)
                pair == null -> accepted += item
                EuronextDuplicates.isDutch(release.title) -> drop(item.row)
                else -> {
                    accepted -= pair
                    drop(pair.row)
                    accepted += item
                }
            }
        }
        return accepted
    }

    private fun isStoredAlready(release: PublishedRelease): Boolean =
        reports.titledOfIsinBetween(
            Market.NL,
            release.isin,
            release.publishedAt.minus(EuronextDuplicates.SAME_RELEASE_WINDOW),
            release.publishedAt.plus(EuronextDuplicates.SAME_RELEASE_WINDOW),
        )
            .map { PublishedRelease(release.isin, it.title.orEmpty(), it.publishedAt) }
            .any { EuronextDuplicates.isSameRelease(release, it) || EuronextDuplicates.isLanguagePair(release, it) }

    private fun drop(row: EuronextRow) {
        dropped[row.nid] = row.publishedAt
    }

    private companion object {
        val MAX_LOOKBACK: Duration = Duration.ofDays(7)
        const val MAX_PAGES = 10
        const val MIN_ROWS_FOR_LAYOUT_CHECK = 3
        val GONE_STATUSES = setOf(404, 410)
    }
}
