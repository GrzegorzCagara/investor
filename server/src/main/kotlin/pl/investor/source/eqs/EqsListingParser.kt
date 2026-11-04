package pl.investor.source.eqs

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import pl.investor.support.BERLIN
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object EqsListingParser {

    private val log = LoggerFactory.getLogger(EqsListingParser::class.java)
    private const val DATE_HEADER = "data-realtime-news-date-grouped"
    private const val ITEM = "a[data-wio=news-feed-list-item]"
    private val GROUP_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    private val WHITESPACE = Regex("[\\s\\u00A0]+")

    fun parse(html: String): List<EqsNewsItem> {
        var date: LocalDate? = null
        var listed = 0
        val items = Jsoup.parse(html).select("[$DATE_HEADER], $ITEM").mapNotNull { element ->
            if (element.hasAttr(DATE_HEADER)) {
                date = runCatching { LocalDate.parse(element.text().clean(), GROUP_DATE) }.getOrNull()
                null
            } else {
                listed++
                element.toItemOrNull(date)
            }
        }
        if (listed > 0 && items.isEmpty()) throw IllegalArgumentException("None of $listed EQS news items could be read")
        return items
    }

    private fun Element.toItemOrNull(date: LocalDate?): EqsNewsItem? {
        val isin = attr("data-news-isin").clean()
        val category = attr("data-news-category").clean()
        return try {
            toItem(date ?: throw IllegalArgumentException("EQS news item without a date header"), isin, category)
        } catch (e: RuntimeException) {
            if (EqsCategories.covers(isin, category)) log.warn("Skipping malformed EQS item {} ({}): {}", attr("data-news-item"), isin, e.message)
            null
        }
    }

    private fun Element.toItem(date: LocalDate, isin: String, category: String): EqsNewsItem {
        val time = LocalTime.parse(requiredText(".news__time"))
        return EqsNewsItem(
            newsId = requiredAttr("data-news-item"),
            company = requiredText(".news__company"),
            isin = isin.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("EQS news item without data-news-isin"),
            category = category,
            title = selectFirst(".news__heading")?.text()?.clean()?.takeIf { it.isNotEmpty() },
            publishedAt = date.atTime(time).atZone(BERLIN).toInstant(),
            newsUrl = requiredAttr("data-news-url"),
        )
    }

    private fun Element.requiredAttr(name: String): String =
        attr(name).clean().takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("EQS news item without $name")

    private fun Element.requiredText(selector: String): String =
        selectFirst(selector)?.text()?.clean()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("EQS news item without $selector")

    private fun String.clean(): String = replace(WHITESPACE, " ").trim()
}
