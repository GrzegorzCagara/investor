package pl.investor.source.consensus

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import pl.investor.report.Company
import pl.investor.report.Market
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.source.biznesradar.BiznesRadarClient
import pl.investor.source.content.BankierReportLocator
import pl.investor.support.toWarsawDate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

data class NoteWindow(val after: Instant, val until: Instant) {
    fun contains(at: Instant): Boolean = at > after && at <= until
}

@Component
class ConsensusClient(
    private val http: PoliteHttpClient,
    private val locator: BankierReportLocator,
    private val biznesRadar: BiznesRadarClient,
    private val parser: ConsensusNoteParser,
    private val clock: Clock,
    private val baseUrl: String = BASE_URL,
) {

    private val log = LoggerFactory.getLogger(ConsensusClient::class.java)

    private val slugsByCompanyId = ConcurrentHashMap<Long, String>()

    private val unresolvableOn = ConcurrentHashMap<Long, LocalDate>()

    fun find(company: Company, windows: List<NoteWindow>): Map<NoteWindow, ConsensusNote> {
        if (windows.isEmpty()) return emptyMap()
        val slug = slugOf(company) ?: return emptyMap()
        val notes = parser.noteLinks(http.get(Source.BANKIER_NEWS, "$baseUrl/gielda/notowania/akcje/$slug/wiadomosci"), baseUrl)
            .filter { link -> windows.any { link.mayBePublishedIn(it) } }
            .mapNotNull { note(it) }
        return windows
            .mapNotNull { window -> notes.filter { window.contains(it.publishedAt) }.minByOrNull { it.publishedAt }?.let { window to it } }
            .toMap()
    }

    private fun slugOf(company: Company): String? {
        if (company.market == Market.US) return null
        val id = checkNotNull(company.id) { "Company without id" }
        locator.confirmedSlug(company)?.let { return it }
        slugsByCompanyId[id]?.let { return it }
        val today = clock.instant().toWarsawDate()
        if (unresolvableOn[id] == today) return null
        val slug = biznesRadar.mediumNameFor(company.name)?.takeIf { locator.hasAnnouncements(it, Source.BANKIER_NEWS) }
        if (slug == null) unresolvableOn[id] = today else slugsByCompanyId[id] = slug
        return slug
    }

    private fun note(link: ConsensusLink): ConsensusNote? {
        val html = try {
            http.get(Source.BANKIER_NEWS, link.url)
        } catch (e: SourceUnavailableException) {
            if (e.cause !is HttpClientErrorException) throw e
            log.warn("Consensus note {} is not available: {}", link.url, e.cause?.message)
            return null
        }
        val note = runCatching { parser.parseNote(html, link.url) }.getOrElse {
            log.warn("Consensus note {} could not be parsed: {}", link.url, it.message)
            return null
        }
        if (note.rows.isEmpty()) {
            log.warn("Consensus note {} has no rows", link.url)
            return null
        }
        return note
    }

    private fun ConsensusLink.mayBePublishedIn(window: NoteWindow): Boolean =
        listedAt == null || (listedAt.plus(LIST_PRECISION) > window.after && listedAt <= window.until)

    private companion object {
        const val BASE_URL = "https://www.bankier.pl"
        val LIST_PRECISION: Duration = Duration.ofMinutes(1)
    }
}
