package pl.investor.source.nasdaq

import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import pl.investor.source.content.ReportContent
import pl.investor.source.content.ReportContentNotFoundException

@Component
class NasdaqViewParser {

    fun parse(html: String, pageUrl: String): ReportContent {
        val document = Jsoup.parse(html, pageUrl)
        val body = document.selectFirst(BODY_SELECTOR) ?: throw ReportContentNotFoundException(pageUrl)
        body.select("style, script, img").remove()
        return ReportContent(
            text = body.text().replace(WHITESPACE, " ").trim(),
            selectedData = emptyList(),
            attachmentUrls = document.select(ATTACHMENT_SELECTOR)
                .filter { PDF_NAME.containsMatchIn(it.text()) }
                .map { it.absUrl("href") }
                .filter { it.isNotEmpty() }
                .distinct(),
            attachmentsArePdf = true,
        )
    }

    private companion object {
        const val BODY_SELECTOR = "#view-body"
        const val ATTACHMENT_SELECTOR = ".attachments [href]"
        val PDF_NAME = Regex("\\.pdf\\s*$", RegexOption.IGNORE_CASE)
        val WHITESPACE = Regex("[\\s\\u00A0]+")
    }
}
