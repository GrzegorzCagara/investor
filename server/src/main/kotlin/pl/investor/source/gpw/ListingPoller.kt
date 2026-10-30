package pl.investor.source.gpw

import org.slf4j.LoggerFactory
import pl.investor.report.Market
import pl.investor.report.ReportRepository
import pl.investor.settings.SettingRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

class ListingPoller(
    private val client: EspiListingClient,
    private val reports: ReportRepository,
    private val settings: SettingRepository,
    private val clock: Clock,
    private val onNewEntry: (ListingEntry) -> Unit,
) {

    private val log = LoggerFactory.getLogger(ListingPoller::class.java)

    fun poll(market: Market): Int {
        var delivered = 0
        var checkpointCanAdvance = true
        datesToRead(market).forEach { date ->
            val entries = runCatching { newEntriesOf(market, date) }
                .onFailure { log.warn("Reading {} listing for {} failed, will retry next poll", market, date, it) }
                .getOrNull()
            if (entries == null) {
                checkpointCanAdvance = false
            } else {
                entries.sortedBy { it.publishedAt }.forEach(onNewEntry)
                delivered += entries.size
                if (checkpointCanAdvance) settings.put(checkpointKey(market), date.toString())
            }
        }
        return delivered
    }

    private fun datesToRead(market: Market): List<LocalDate> {
        val now = clock.instant().atZone(WARSAW)
        val today = now.toLocalDate()
        val lastPolled = settings.find(checkpointKey(market))?.let(LocalDate::parse)
        val first = when {
            lastPolled != null -> maxOf(lastPolled, today.minusDays(MAX_DAYS_BACK))
            now.hour == 0 -> today.minusDays(1)
            else -> today
        }
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
    }

    private fun newEntriesOf(market: Market, date: LocalDate): List<ListingEntry> {
        val found = mutableListOf<ListingEntry>()
        var offset = 0
        do {
            val page = client.page(market, date, offset)
            val (known, unknown) = page.entries.partition { it.isKnown() }
            found += unknown
            offset += EspiListingClient.PAGE_SIZE
        } while (page.rawItemCount >= EspiListingClient.PAGE_SIZE && known.isEmpty())
        return found
    }

    private fun ListingEntry.isKnown() = reports.existsBySourceRef(market, sourceRef)

    private fun checkpointKey(market: Market) = "LAST_POLLED_DATE_${market.name}"

    private companion object {
        val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
        const val MAX_DAYS_BACK = 7L
    }
}
