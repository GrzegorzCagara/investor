package pl.investor.source.consensus

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import pl.investor.source.biznesradar.PolishNumbers
import pl.investor.support.WARSAW
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

@Component
class ConsensusNoteParser {

    private class Columns(val actual: Int, val consensus: Int, val difference: Int)

    private val mapper = JsonMapper.builder().build()

    fun articleLinks(html: String, baseUrl: String): List<ConsensusLink> =
        listedAnchors(html, baseUrl)
            .map { ConsensusLink(it.absUrl("href"), it.listedAt()) }
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }

    fun headlines(html: String, baseUrl: String): List<BankierHeadline> =
        listedAnchors(html, baseUrl)
            .mapNotNull { anchor ->
                val url = anchor.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = anchor.title()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                anchor.listedAt()?.let { BankierHeadline(title, url, it) }
            }
            .distinctBy { it.url }

    private fun listedAnchors(html: String, baseUrl: String): List<Element> =
        Jsoup.parse(html, baseUrl).select("a.m-listing-article-list__anchor")

    private fun Element.title(): String? {
        val title = selectFirst(".m-listing-article-list__title")?.clone() ?: return null
        val labels = title.select(".m-listing-article-list__title-label")
        val label = labels.text().trim().takeIf { it.isNotBlank() }
        labels.remove()
        val text = title.text().trim().takeIf { it.isNotBlank() } ?: return null
        return label?.let { "[$it] $text" } ?: text
    }

    fun parseArticle(html: String): BankierArticle {
        val publishedAt = publishedAt(html)
        val document = Jsoup.parse(html)
        val newsArticle = document.select("script[type=application/ld+json]").firstNotNullOfOrNull { it.data().newsArticle() }
        return BankierArticle(
            publishedAt = publishedAt,
            tableRows = document.select("table").firstNotNullOfOrNull { it.consensusRows() }.orEmpty(),
            headline = newsArticle?.text("headline"),
            body = newsArticle?.text("articleBody"),
        )
    }

    fun mentionsConsensus(text: String): Boolean = CONSENSUS in text.normalized()

    private fun String.newsArticle(): JsonNode? =
        runCatching { mapper.readTree(this) }.getOrNull()?.takeIf { it.has("articleBody") }

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }

    private fun Element.listedAt(): Instant? =
        selectFirst(".m-listing-article-list__date-time")?.text()?.trim()
            ?.let { runCatching { LocalDateTime.parse(it, LIST_DATE_FORMAT).atZone(WARSAW).toInstant() }.getOrNull() }

    private fun publishedAt(html: String): Instant {
        val value = checkNotNull(DATE_PUBLISHED.find(html)) { "Consensus note without datePublished" }.groupValues[1]
        val parsed = PUBLISHED_FORMAT.parse(value.trim())
        return if (parsed.isSupported(ChronoField.OFFSET_SECONDS)) OffsetDateTime.from(parsed).toInstant()
        else LocalDateTime.from(parsed).atZone(WARSAW).toInstant()
    }

    private fun Element.consensusRows(): List<ConsensusRow>? {
        val rows = select("tr").map { row -> row.select("td, th").map { it.text().replace(' ', ' ').trim() } }
        val headerIndex = rows.indexOfFirst { it.columns() != null }
        if (headerIndex < 0) return null
        val columns = checkNotNull(rows[headerIndex].columns())
        return rows.drop(headerIndex + 1).mapNotNull { it.toRow(columns) }
    }

    private fun List<String>.columns(): Columns? {
        val header = map { it.normalized() }
        val consensus = header.indexOfFirst { "kons" in it }
        val difference = header.indexOfFirst { "roznica" in it }
        if (consensus < 0 || difference < 0) return null
        val actual = header.indexOfFirst { "wynik" in it }.takeIf { it >= 0 } ?: (consensus - 1)
        return Columns(actual, consensus, difference).takeIf { actual > 0 }
    }

    private fun List<String>.toRow(columns: Columns): ConsensusRow? {
        val label = firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val difference = getOrNull(columns.difference)?.takeIf { it.endsWith("%") } ?: return null
        return ConsensusRow(
            label = label,
            actual = getOrNull(columns.actual)?.toDecimal() ?: return null,
            consensus = getOrNull(columns.consensus)?.toDecimal() ?: return null,
            differencePct = difference.removeSuffix("%").toDecimal()?.toDouble() ?: return null,
        )
    }

    private fun String.toDecimal() = PolishNumbers.parse(replace(MINUS_SIGNS, "-").trim().removePrefix("+"))

    private fun String.normalized(): String =
        Normalizer.normalize(lowercase().replace('ł', 'l'), Normalizer.Form.NFD).replace(DIACRITICS, "")

    private companion object {
        val DATE_PUBLISHED = Regex(""""datePublished"\s*:\s*"([^"]+)"""")
        val PUBLISHED_FORMAT: DateTimeFormatter = DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm")
            .optionalStart().appendPattern(":ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .optionalEnd()
            .optionalStart().appendPattern("XXX").optionalEnd()
            .optionalStart().appendPattern("XX").optionalEnd()
            .toFormatter()
        val LIST_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val MINUS_SIGNS = Regex("[−–]")
        val DIACRITICS = Regex("\\p{M}+")
        const val CONSENSUS = "konsensus"
    }
}
