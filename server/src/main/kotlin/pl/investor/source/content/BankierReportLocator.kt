package pl.investor.source.content

import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.investor.report.Company
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.source.biznesradar.BiznesRadarClient
import pl.investor.source.gpw.ListingEntry
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

@Component
class BankierReportLocator(
    private val http: PoliteHttpClient,
    private val biznesRadar: BiznesRadarClient,
    private val baseUrl: String = BASE_URL,
) {

    private data class Announcement(val publishedAt: Instant, val title: String, val url: String)

    private data class Resolved(val slug: String, val announcements: List<Announcement>)

    private class PeriodicKeywords(val exact: Set<String>, val prefixes: Set<String>)

    private val log = LoggerFactory.getLogger(BankierReportLocator::class.java)

    private val slugsByCompany = ConcurrentHashMap<String, String>()

    fun find(entry: ListingEntry): String? {
        if (entry.market == Market.US) return null
        val key = entry.isin ?: entry.companyName
        val known = slugsByCompany[key]?.let { knownAnnouncements(key, it) }
        if (known != null) return match(known, entry)
        val resolved = resolve(entry.companyName) ?: return null
        return match(resolved.announcements, entry)?.also { slugsByCompany[key] = resolved.slug }
    }

    fun confirmedSlug(company: Company): String? =
        if (company.market == Market.US) null else slugsByCompany[company.isin ?: company.name]

    fun hasAnnouncements(slug: String, source: Source): Boolean =
        http.probe(source, listUrl(slug))?.let { parseAnnouncements(it).isNotEmpty() } ?: false

    private fun knownAnnouncements(key: String, slug: String): List<Announcement>? {
        val announcements = try {
            announcements(slug)
        } catch (e: Exception) {
            log.warn("Bankier announcements for cached slug {} failed, re-resolving: {}", slug, e.message)
            emptyList()
        }
        if (announcements.isEmpty()) slugsByCompany.remove(key)
        return announcements.takeIf { it.isNotEmpty() }
    }

    private fun resolve(companyName: String): Resolved? {
        val candidates = slugCandidates(companyName)
        var anyCandidateResponded = false
        for (slug in candidates) {
            val html = http.probe(Source.BANKIER, listUrl(slug)) ?: continue
            anyCandidateResponded = true
            val announcements = parseAnnouncements(html)
            if (announcements.isNotEmpty()) return Resolved(slug, announcements)
        }
        if (!anyCandidateResponded && candidates.isNotEmpty()) {
            http.reportFailure(Source.BANKIER, "No Bankier slug candidate responded for $companyName")
        }
        return null
    }

    private fun announcements(slug: String): List<Announcement> = parseAnnouncements(http.get(Source.BANKIER, listUrl(slug)))

    private fun listUrl(slug: String) = "$baseUrl/gielda/notowania/akcje/$slug/komunikaty"

    private fun parseAnnouncements(html: String): List<Announcement> =
        Jsoup.parse(html, baseUrl)
            .select("li.m-quotes-announcements-list__item")
            .mapNotNull { item ->
                val anchor = item.selectFirst("a.m-quotes-announcements-item__anchor") ?: return@mapNotNull null
                val date = item.selectFirst(".m-quotes-announcements-item__date")?.text()?.trim() ?: return@mapNotNull null
                Announcement(
                    publishedAt = LocalDateTime.parse(date, DATE_FORMAT).atZone(WARSAW).toInstant(),
                    title = anchor.text().trim(),
                    url = anchor.absUrl("href"),
                )
            }

    private fun match(announcements: List<Announcement>, entry: ListingEntry): String? {
        val minute = entry.publishedAt.truncatedTo(ChronoUnit.MINUTES)
        val sameMinute = announcements.filter { it.publishedAt.truncatedTo(ChronoUnit.MINUTES) == minute }
        val sameTitle = sameMinute.filter { it.titleMatches(entry) }
        val candidates = sameTitle.ifEmpty { sameMinute.singleOrNull()?.takeIf { it.isLikelyCopyOf(entry) }?.let(::listOf).orEmpty() }
        return candidates.singleOrNull()?.url
    }

    private fun Announcement.isLikelyCopyOf(entry: ListingEntry): Boolean {
        val entryTitle = entry.title
        if (entryTitle.isNullOrBlank()) return title.normalizedTitle().startsWith(PERIODIC_TITLE_PREFIX)
        return title.significantTokens().jaccardWith(entryTitle.significantTokens()) >= MIN_TITLE_SIMILARITY
    }

    private fun String.significantTokens(): Set<String> =
        normalizedTitle().split(' ').filter { it.length >= MIN_TOKEN_LENGTH && it !in STOP_WORDS }.toSet()

    private fun Set<String>.jaccardWith(other: Set<String>): Double {
        val union = (this + other).size
        return if (union == 0) 0.0 else (this intersect other).size.toDouble() / union
    }

    private fun Announcement.titleMatches(entry: ListingEntry): Boolean {
        val entryTitle = entry.title
        if (entryTitle.isNullOrBlank()) return isPeriodicResultsTitleOf(entry.kind)
        val left = title.normalizedTitle()
        val right = entryTitle.normalizedTitle()
        return left.contains(right) || right.contains(left)
    }

    private fun Announcement.isPeriodicResultsTitleOf(kind: ReportKind): Boolean {
        val normalized = title.normalizedTitle()
        if (!normalized.startsWith(PERIODIC_TITLE_PREFIX)) return false
        val keywords = PERIODIC_KEYWORDS[kind] ?: return false
        return normalized.split(' ').any { token -> token in keywords.exact || keywords.prefixes.any(token::startsWith) }
    }

    private fun String.normalizedTitle(): String =
        Normalizer.normalize(lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace(INNER_PUNCTUATION, "")
            .replace(NON_ALPHANUMERIC, " ")
            .trim()

    private fun slugCandidates(companyName: String): List<String> {
        val word = companyName.trim().split(WHITESPACE).first().asciiAlphanumeric().uppercase()
        val heuristic = listOf(word, word.dropLast(1), word.dropLast(2)).filter { it.length >= MIN_SLUG_LENGTH }
        return (listOfNotNull(mediumName(companyName)) + heuristic).distinct()
    }

    private fun mediumName(companyName: String): String? =
        try {
            biznesRadar.mediumNameFor(companyName)
        } catch (e: SourceUnavailableException) {
            null
        }

    private fun String.asciiAlphanumeric() =
        Normalizer.normalize(replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFD)
            .filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }

    private companion object {
        const val BASE_URL = "https://www.bankier.pl"
        const val PERIODIC_TITLE_PREFIX = "wyniki finansowe"
        const val MIN_SLUG_LENGTH = 3
        const val MIN_TOKEN_LENGTH = 3
        const val MIN_TITLE_SIMILARITY = 0.5
        val STOP_WORDS = setOf("raport", "biezacy", "informacja", "spolki", "spolka", "sa")
        val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        val WHITESPACE = Regex("[\\s\\u00A0]+")
        val DIACRITICS = Regex("\\p{M}+")
        val INNER_PUNCTUATION = Regex("[.'’]")
        val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
        val PERIODIC_KEYWORDS = mapOf(
            ReportKind.QUARTERLY to PeriodicKeywords(setOf("qs", "qsr"), setOf("kwarta")),
            ReportKind.HALF_YEARLY to PeriodicKeywords(setOf("ps", "psr"), setOf("polroc")),
            ReportKind.ANNUAL to PeriodicKeywords(setOf("rs", "rr", "srs", "srr"), setOf("roczn")),
        )
    }
}
