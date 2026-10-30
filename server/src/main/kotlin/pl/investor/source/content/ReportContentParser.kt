package pl.investor.source.content

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
class ReportContentParser {

    fun parse(html: String, baseUrl: String = DEFAULT_BASE_URL, pageUrl: String = baseUrl): ReportContent {
        val body = Jsoup.parse(html, baseUrl).select(BODY_SELECTOR).firstOrNull()
            ?: throw ReportContentNotFoundException(pageUrl)
        return ReportContent(
            text = body.readableText(),
            selectedData = body.selectedData(),
            attachmentUrls = body.attachmentUrls(),
        )
    }

    private fun Element.readableText(): String {
        val copy = clone()
        copy.select("style, script, img, table.nDokument, a[href*=/files/], a[href*=attachment/]").remove()
        return copy.text().normalizeSpaces()
    }

    private fun Element.attachmentUrls(): List<String> =
        select("a[href]")
            .map { it.absUrl("href") }
            .filter { ATTACHMENT_URL.containsMatchIn(it) }
            .distinct()

    private fun Element.selectedData(): List<SelectedDataRow> =
        select("h4")
            .filter { it.text().normalizeSpaces().contains(SELECTED_DATA_SHEET, ignoreCase = true) }
            .mapNotNull { it.nextElementSibling() }
            .flatMap { it.select("tr.nTekst") }
            .mapNotNull { it.toDataRow() }

    private fun Element.toDataRow(): SelectedDataRow? {
        val cells = children().filter { it.tagName() == "td" }
            .map { it.text().normalizeSpaces() }
            .filter { it.isNotEmpty() }
        val label = cells.firstOrNull() ?: return null
        val values = cells.drop(1)
        return values.takeIf { it.any(::isDataValue) }?.let { SelectedDataRow(label, it) }
    }

    private fun isDataValue(cell: String) = NUMBER.matches(cell) && !YEAR.matches(cell)

    private fun String.normalizeSpaces() = replace(WHITESPACE, " ").trim()

    companion object {
        const val DEFAULT_BASE_URL = "https://www.gpw.pl/"
        private const val BODY_SELECTOR = ".report-data, .report_data_wrap"
        private const val SELECTED_DATA_SHEET = "WYBRANE DANE FINANSOWE"
        private val WHITESPACE = Regex("[\\s\\u00A0]+")
        private val ATTACHMENT_URL = Regex("/attachment/|/files/")
        private val NUMBER = Regex("-?[\\d ]+([.,]\\d+)?%?")
        private val YEAR = Regex("(19|20)\\d{2}")
    }
}
