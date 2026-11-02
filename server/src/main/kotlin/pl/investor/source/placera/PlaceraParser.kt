package pl.investor.source.placera

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import pl.investor.scoring.PastReaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

object PlaceraParser {

    private const val PREVIEW_MARKER = "vantas-redovisa"
    private const val HEADLINE_MARKER = " väntas redovisa"
    private const val CONSENSUS_COLUMN = 1
    private const val ESTIMATES_HEADER = "antal estimat"
    private const val REACTIONS_HEADER = "rapportdatum"
    private const val REPORT_DATE_LABEL = "rapportdag"
    private const val SOURCE_PREFIX = "källa:"
    private val REVENUE_LABELS = setOf("försäljning", "nettoomsättning", "omsättning", "intäkter")
    private val EBIT_LABELS = setOf("rörelseresultat", "rörelseresultat (ebit)", "ebit")
    private val UNITS = mapOf(
        "miljoner kronor" to AmountUnit("SEK", BigDecimal(1000)),
        "miljarder kronor" to AmountUnit("SEK", BigDecimal(1_000_000)),
        "tusen kronor" to AmountUnit("SEK", BigDecimal.ONE),
        "miljoner euro" to AmountUnit("EUR", BigDecimal(1000)),
        "miljoner dollar" to AmountUnit("USD", BigDecimal(1000)),
        "miljoner norska kronor" to AmountUnit("NOK", BigDecimal(1000)),
        "miljoner danska kronor" to AmountUnit("DKK", BigDecimal(1000)),
    )
    private val KNOWN_SOURCES = listOf("factset" to "FactSet", "modular" to "Modular Finance", "bloomberg" to "Bloomberg", "infront" to "Infront")
    private val DATE_PUBLISHED = Regex(""""datePublished"\s*:\s*"([^"]+)"""")
    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private const val TOPICS_HEADING = "Ämnen i artikeln"
    private val MINUS_SIGNS = Regex("[−–]")
    private val SPACES = Regex("[\\s  ]+")

    private data class AmountUnit(val currency: String, val toThousands: BigDecimal)

    fun previewLinks(html: String, baseUrl: String): List<String> =
        Jsoup.parse(html, baseUrl).select("a[href^=/telegram/]")
            .map { it.absUrl("href") }
            .filter { PREVIEW_MARKER in it }
            .distinct()

    fun article(html: String): PlaceraArticle {
        val document = Jsoup.parse(html)
        val rows = document.select("table tr").map { row -> row.select("td, th").map { it.text().replace(' ', ' ').trim() } }
        val estimatesHeader = rows.indexOfFirst { cells -> cells.any { it.lowercase() == ESTIMATES_HEADER } }
            .takeIf { it >= 0 } ?: throw UnparseablePreviewException("No consensus table")
        val header = rows[estimatesHeader]
        val unit = UNITS[header.first().lowercase().trim('(', ')')] ?: throw UnparseablePreviewException("Unknown unit ${header.first()}")
        val estimateRows = rows.drop(estimatesHeader + 1).takeWhile { !it.isSourceRow() }
        val sourceRow = rows.drop(estimatesHeader + 1).firstOrNull { it.isSourceRow() } ?: throw UnparseablePreviewException("No consensus source")
        val columns = EstimateColumns(header)
        val tag = topicTag(document, html)
        return PlaceraArticle(
            companyName = tag?.title ?: headlineCompany(document),
            isin = tag?.isin,
            reportDate = reportDate(rows),
            currency = unit.currency,
            revenue = estimateRows.firstOrNull { it.label() in REVENUE_LABELS }?.let { columns.estimate(it, unit) },
            ebit = estimateRows.firstOrNull { it.label() in EBIT_LABELS }?.let { columns.estimate(it, unit) },
            source = sourceName(sourceRow.first()),
            pastReactions = pastReactions(rows),
            publishedAt = publishedAt(html),
        )
    }

    private class EstimateColumns(header: List<String>) {
        private val analysts = header.indexOfFirst { it.lowercase() == ESTIMATES_HEADER }
        private val prevYear = header.indices.last.takeIf { it != analysts && it != CONSENSUS_COLUMN }

        fun estimate(cells: List<String>, unit: AmountUnit): ConsensusEstimate? {
            val value = cells.getOrNull(CONSENSUS_COLUMN)?.toDecimal() ?: return null
            return ConsensusEstimate(
                consensus = value * unit.toThousands,
                prevYear = prevYear?.let { cells.getOrNull(it)?.toDecimal() }?.let { it * unit.toThousands },
                analysts = cells.getOrNull(analysts)?.toIntOrNull(),
            )
        }
    }

    private data class Tag(val title: String, val isin: String?)

    private fun topicTag(document: Document, html: String): Tag? {
        val heading = document.select("h5").firstOrNull { it.text().trim() == TOPICS_HEADING } ?: return null
        val slug = heading.parent()?.selectFirst("a[href^=/taggar/]")?.attr("href")?.removePrefix("/taggar/") ?: return null
        val unescaped = html.replace("\\\"", "\"")
        val match = Regex(""""title":"([^"]+)","isin":(?:"([A-Z0-9]{12})"|null),"insref":(?:"[^"]*"|null),"slug":"${Regex.escape(slug)}"""")
            .find(unescaped) ?: return null
        return Tag(match.groupValues[1], match.groupValues[2].takeIf { it.isNotEmpty() })
    }

    private fun headlineCompany(document: Document): String =
        document.title().substringBefore(HEADLINE_MARKER).trim().takeIf { it.isNotEmpty() && HEADLINE_MARKER in document.title() }
            ?: throw UnparseablePreviewException("No company name")

    private fun reportDate(rows: List<List<String>>): LocalDate =
        rows.firstOrNull { it.firstOrNull()?.lowercase() == REPORT_DATE_LABEL }
            ?.firstNotNullOfOrNull { cell -> cell.takeIf { ISO_DATE.matches(it) }?.let(LocalDate::parse) }
            ?: throw UnparseablePreviewException("No report date")

    private fun pastReactions(rows: List<List<String>>): List<PastReaction> {
        val header = rows.indexOfFirst { cells -> cells.any { it.lowercase() == REACTIONS_HEADER } }.takeIf { it >= 0 } ?: return emptyList()
        val dateColumn = rows[header].indexOfFirst { it.lowercase() == REACTIONS_HEADER }
        return rows.drop(header + 1)
            .map { cells ->
                val date = cells.getOrNull(dateColumn)?.takeIf { ISO_DATE.matches(it) }?.let(LocalDate::parse)
                val pct = cells.getOrNull(dateColumn + 1)?.toDecimal()?.toDouble()
                if (date != null && pct != null) PastReaction(date, pct) else null
            }
            .takeWhile { it != null }
            .filterNotNull()
    }

    private fun publishedAt(html: String): Instant =
        DATE_PUBLISHED.find(html)?.groupValues?.get(1)?.let(Instant::parse) ?: throw UnparseablePreviewException("No datePublished")

    private fun sourceName(cell: String): String {
        val name = cell.substringAfter(':').trim()
        return KNOWN_SOURCES.firstOrNull { (key, _) -> key in name.lowercase() }?.second ?: name
    }

    private fun List<String>.isSourceRow() = firstOrNull()?.lowercase()?.startsWith(SOURCE_PREFIX) == true

    private fun List<String>.label() = firstOrNull()?.lowercase()?.trim()

    private fun String.toDecimal(): BigDecimal? =
        replace(MINUS_SIGNS, "-").replace(SPACES, "").replace(',', '.').removePrefix("+").toBigDecimalOrNull()
}
