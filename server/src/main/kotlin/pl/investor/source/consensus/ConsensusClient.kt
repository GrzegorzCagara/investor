package pl.investor.source.consensus

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import pl.investor.extraction.AiAuthenticationException
import pl.investor.extraction.AiBudgetExhaustedException
import pl.investor.extraction.AiUnavailableException
import pl.investor.report.Company
import pl.investor.report.Market
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.source.biznesradar.BiznesRadarClient
import pl.investor.source.content.BankierReportLocator
import pl.investor.support.foldedTitle
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
    private val reader: ConsensusTextReader,
    private val clock: Clock,
    private val baseUrl: String = BASE_URL,
) {

    private val log = LoggerFactory.getLogger(ConsensusClient::class.java)

    private val slugsByCompanyId = ConcurrentHashMap<Long, String>()

    private val unresolvableOn = ConcurrentHashMap<Long, LocalDate>()

    private val articlesByUrl = ConcurrentHashMap<String, BankierArticle>()

    private val textRowsByArticle = ConcurrentHashMap<CompanyArticle, List<ConsensusRow>>()

    private val goneUrls = ConcurrentHashMap.newKeySet<String>()

    fun find(company: Company, windows: List<NoteWindow>): Map<NoteWindow, ConsensusNote> {
        if (windows.isEmpty()) return emptyMap()
        val slug = slugOf(company) ?: return emptyMap()
        val candidates = parser.articleLinks(newsPage(slug), baseUrl)
            .sortedWith(compareBy(nullsLast<Instant>()) { it.listedAt })
        return windows.mapNotNull { window ->
            candidates.asSequence()
                .filter { it.mayBePublishedIn(window) }
                .mapNotNull { note(it, company, window) }
                .firstOrNull()
                ?.let { window to it }
        }.toMap()
    }

    fun headlines(company: Company, from: Instant, until: Instant, limit: Int): List<BankierHeadline> {
        val slug = slugOf(company) ?: return emptyList()
        return parser.headlines(newsPage(slug), baseUrl)
            .filter { it.url.startsWith("$baseUrl/") && it.publishedAt >= from && it.publishedAt <= until }
            .sortedByDescending { it.publishedAt }
            .take(limit)
    }

    fun hasNewsPage(company: Company): Boolean = slugOf(company) != null

    private fun newsPage(slug: String): String = http.get(Source.BANKIER_NEWS, "$baseUrl/gielda/notowania/akcje/$slug/wiadomosci")

    private fun slugOf(company: Company): String? {
        if (company.market != Market.GPW && company.market != Market.NC) return null
        val id = checkNotNull(company.id) { "Company without id" }
        locator.confirmedSlug(company)?.let { return it }
        slugsByCompanyId[id]?.let { return it }
        val today = clock.instant().toWarsawDate()
        if (unresolvableOn[id] == today) return null
        val slug = biznesRadar.mediumNameFor(company.name)?.takeIf { locator.hasAnnouncements(it, Source.BANKIER_NEWS) }
        if (slug == null) unresolvableOn[id] = today else slugsByCompanyId[id] = slug
        return slug
    }

    private fun note(link: ConsensusLink, company: Company, window: NoteWindow): ConsensusNote? {
        if (link.url in goneUrls) return null
        val article = articlesByUrl[link.url] ?: fetchArticle(link.url) ?: return null
        if (!window.contains(article.publishedAt)) return null
        val rows = article.tableRows.ifEmpty { textRows(link.url, company, article) }
        return rows.takeIf { it.isNotEmpty() }?.let { ConsensusNote(link.url, article.publishedAt, it) }
    }

    private fun fetchArticle(url: String): BankierArticle? {
        val html = try {
            http.get(Source.BANKIER_NEWS, url)
        } catch (e: SourceUnavailableException) {
            val clientError = e.cause as? HttpClientErrorException ?: throw e
            log.warn("Bankier article {} is not available: {}", url, clientError.message)
            if (clientError.statusCode.value() in GONE_STATUSES) goneUrls += url
            return null
        }
        val article = runCatching { parser.parseArticle(html) }.getOrElse {
            log.warn("Bankier article {} could not be parsed: {}", url, it.message)
            return null
        }
        return article.copy(body = article.body?.takeIf { article.tableRows.isEmpty() && parser.mentionsConsensus(it) })
            .also { if (it.tableRows.isNotEmpty() || it.isSettled()) articlesByUrl[url] = it }
    }

    private fun textRows(url: String, company: Company, article: BankierArticle): List<ConsensusRow> {
        val body = article.body?.takeIf { company.isNamedIn(article) } ?: return emptyList()
        val key = CompanyArticle(url, checkNotNull(company.id))
        textRowsByArticle[key]?.let { return it }
        val rows = try {
            reader.read(company.name, body)
        } catch (e: RuntimeException) {
            when (e) {
                is MalformedConsensusReadingException -> emptyList()
                is ConsensusReadingFailedException, is AiBudgetExhaustedException, is AiUnavailableException, is AiAuthenticationException -> {
                    log.warn("Consensus text of {} not read now: {}", url, e.message)
                    return emptyList()
                }
                else -> throw e
            }
        }
        if (rows.isNotEmpty() || article.isSettled()) textRowsByArticle[key] = rows
        return rows
    }

    private fun BankierArticle.isSettled(): Boolean = publishedAt.plus(SETTLE_TIME) < clock.instant()

    private fun Company.isNamedIn(article: BankierArticle): Boolean {
        val text = listOfNotNull(article.headline, article.body).joinToString(" ").foldedTitle()
        return nameStems().any { stem -> Regex("(?<![a-z0-9])${Regex.escape(stem)}").containsMatchIn(text) }
    }

    private fun Company.nameStems(): List<String> =
        listOfNotNull(shortName, name.foldedTitle().split(NON_ALPHANUMERIC).firstOrNull { it.length >= MIN_NAME_WORD && it !in LEGAL_FORMS })
            .map { it.foldedTitle() }
            .filter { it.isNotBlank() }
            .map { it.take(maxOf(MIN_STEM, it.length - 1)) }
            .distinct()

    private fun ConsensusLink.mayBePublishedIn(window: NoteWindow): Boolean =
        listedAt == null || (listedAt.plus(LIST_PRECISION) > window.after && listedAt <= window.until)

    private data class CompanyArticle(val url: String, val companyId: Long)

    private companion object {
        const val BASE_URL = "https://www.bankier.pl"
        val LIST_PRECISION: Duration = Duration.ofMinutes(1)
        val SETTLE_TIME: Duration = Duration.ofHours(2)
        val GONE_STATUSES = setOf(404, 410)
        const val MIN_NAME_WORD = 3
        const val MIN_STEM = 4
        val LEGAL_FORMS = setOf("spolka", "akcyjna", "plc", "grupa")
        val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    }
}
