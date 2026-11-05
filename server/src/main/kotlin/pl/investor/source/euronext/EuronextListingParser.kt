package pl.investor.source.euronext

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import pl.investor.support.AMSTERDAM
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object EuronextListingParser {

    private val log = LoggerFactory.getLogger(EuronextListingParser::class.java)
    private val mapper = JsonMapper.builder().build()
    private const val LISTING_VIEW = "view-company-press-releases-view"
    private const val TITLE_LINK = "a[data-node-nid]"
    private val PUBLISHED: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH)
    private val ZONE_LABEL = Regex("\\s+CES?T$")
    private val WHITESPACE = Regex("[\\s\\u00A0]+")

    fun parse(json: String): List<EuronextRow> {
        val rows = listingHtml(json)
            ?.let { Jsoup.parseBodyFragment(it).select("tbody tr").filter { row -> row.selectFirst(TITLE_LINK) != null } }
            ?: throw IllegalArgumentException("Euronext response has no company press releases view")
        val parsed = rows.mapNotNull { it.toRowOrNull() }
        if (rows.isNotEmpty() && parsed.isEmpty()) throw IllegalArgumentException("None of ${rows.size} Euronext releases could be read")
        return parsed
    }

    private fun listingHtml(json: String): String? =
        mapper.readTree(json)
            .filter { it.path("command").asString("") == "insert" }
            .map { it.path("data").asString("") }
            .firstOrNull { LISTING_VIEW in it }

    private fun Element.toRowOrNull(): EuronextRow? =
        try {
            toRow()
        } catch (e: RuntimeException) {
            log.warn("Skipping malformed Euronext release row {}: {}", selectFirst(TITLE_LINK)?.attr("data-node-nid"), e.message)
            null
        }

    private fun Element.toRow(): EuronextRow {
        val link = checkNotNull(selectFirst(TITLE_LINK))
        val published = requiredText(".views-field-field-company-pr-pub-datetime").replace(ZONE_LABEL, "")
        return EuronextRow(
            nid = link.attr("data-node-nid").clean().takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("release without nid"),
            company = requiredText(".views-field-field-company-name"),
            title = link.text().clean().takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("release without title"),
            publishedAt = LocalDateTime.parse(published, PUBLISHED).atZone(AMSTERDAM).toInstant(),
        )
    }

    private fun Element.requiredText(selector: String): String =
        selectFirst(selector)?.text()?.clean()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("release without $selector")

    private fun String.clean(): String = replace(WHITESPACE, " ").trim()
}
