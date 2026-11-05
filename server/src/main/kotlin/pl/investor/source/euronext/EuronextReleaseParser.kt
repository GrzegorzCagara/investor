package pl.investor.source.euronext

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import pl.investor.source.content.ReportContent
import pl.investor.source.content.ReportContentNotFoundException

@Component
class EuronextReleaseParser {

    fun issuer(html: String): EuronextIssuer {
        val document = Jsoup.parse(html)
        return EuronextIssuer(
            isin = document.field(ISIN_LABEL)?.firstListed() ?: document.selectFirst("[data-isin]")?.attr("data-isin")?.firstListed(),
            ticker = document.field(SYMBOL_LABEL)?.split(',')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && !it.first().isDigit() },
        )
    }

    fun content(html: String, pageUrl: String): ReportContent {
        val document = Jsoup.parse(html, pageUrl)
        val container = document.selectFirst(CONTAINER) ?: throw ReportContentNotFoundException(pageUrl)
        val pdfs = container.select("a[href]").filter { ATTACHMENT_PATH in it.attr("href") }.map { it.absUrl("href") }.filter { it.isNotEmpty() }.distinct()
        val body = container.bodyRow()
        body?.select("style, script, img, a[download], #gnw_attachments_section-header, #gnw_attachments_section-items")?.remove()
        return ReportContent(
            text = body?.text()?.replace(WHITESPACE, " ")?.trim().orEmpty(),
            selectedData = emptyList(),
            attachmentUrls = pdfs,
            attachmentsArePdf = true,
        )
    }

    private fun Element.bodyRow(): Element? =
        children().lastOrNull { it.hasClass("row") && it.hasClass("mb-5") && !it.isHeaderRow() }

    private fun Element.isHeaderRow(): Boolean =
        selectFirst("h1, button") != null || select("h3").any { it.text().trim().equals(ISSUER_LABEL, ignoreCase = true) }

    private fun Document.field(label: String): String? =
        select("h3").firstOrNull { it.text().trim().equals(label, ignoreCase = true) }
            ?.nextElementSibling()
            ?.text()
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun String.firstListed(): String? = split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() }

    private companion object {
        const val CONTAINER = "#field_company_press_release_isin"
        const val ISIN_LABEL = "ISIN"
        const val SYMBOL_LABEL = "Symbol"
        const val ISSUER_LABEL = "Issuer"
        const val ATTACHMENT_PATH = "/sites/default/files/company_press_releases/attachments/"
        val WHITESPACE = Regex("[\\s\\u00A0]+")
    }
}
