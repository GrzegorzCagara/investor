package pl.investor.source.eqs

import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import pl.investor.source.content.ReportContent
import pl.investor.source.content.ReportContentNotFoundException

@Component
class EqsReleaseParser {

    fun parse(html: String, pageUrl: String): ReportContent {
        val body = Jsoup.parse(html, pageUrl).selectFirst(BODY_SELECTOR) ?: throw ReportContentNotFoundException(pageUrl)
        body.select("style, script, img").remove()
        body.select("td").filter { it.ownText().trim() in CONTACT_TABLE_LABELS }.mapNotNull { it.closest("table") }.distinct().forEach { it.remove() }
        val text = body.text().replace(WHITESPACE, " ").trim()
        return ReportContent(
            text = END_OF_ANNOUNCEMENT.find(text)?.let { text.substring(0, it.range.first).trim() } ?: text,
            selectedData = emptyList(),
            attachmentUrls = emptyList(),
        )
    }

    private companion object {
        const val BODY_SELECTOR = ".news-details__content"
        val CONTACT_TABLE_LABELS = setOf("Language:", "Sprache:")
        val END_OF_ANNOUNCEMENT = Regex(
            "(The|Die) EQS Distribution ?s?[Ss]ervices|End of (Announcement|News|Media Release)|Ende der (Mitteilung|News|Pressemitteilung)",
        )
        val WHITESPACE = Regex("[\\s\\u00A0]+")
    }
}
