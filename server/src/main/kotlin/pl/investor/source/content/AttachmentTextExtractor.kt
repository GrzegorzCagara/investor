package pl.investor.source.content

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import java.io.ByteArrayInputStream
import java.text.Normalizer

@Component
class AttachmentTextExtractor(private val http: PoliteHttpClient) {

    private val log = LoggerFactory.getLogger(AttachmentTextExtractor::class.java)

    fun relevantText(articleUrl: String): String {
        val texts = attachmentUrls(articleUrl).mapNotNull { url -> attachmentText(url, articleUrl) }
        return joined(texts)
    }

    internal fun joined(texts: List<String>): String = texts.fairlyTruncated().joinToString(" ").take(MAX_CHARS)

    private fun List<String>.fairlyTruncated(): List<String> {
        var remainingChars = MAX_CHARS
        var remainingTexts = size
        val limits = withIndex().sortedBy { it.value.length }.associate { (index, text) ->
            val limit = minOf(text.length, remainingChars / remainingTexts)
            remainingChars -= limit
            remainingTexts--
            index to limit
        }
        return mapIndexed { index, text -> text.take(limits.getValue(index)).trim() }
    }

    private fun attachmentUrls(articleUrl: String): List<String> {
        val all = Jsoup.parse(http.get(Source.BANKIER, articleUrl), articleUrl)
            .select("a[href]")
            .map { it.absUrl("href") }
            .filter { AttachmentFormat.of(it) != null && ATTACHMENT_PATH in it }
            .distinct()
        val polish = all.filterNot { ENGLISH_FILE.containsMatchIn(it.substringAfterLast('/')) }
        return polish.ifEmpty { all }.take(MAX_ATTACHMENTS)
    }

    private fun attachmentText(url: String, articleUrl: String): String? =
        runCatching {
            val bytes = http.getBytes(Source.BANKIER, url, mapOf("Referer" to articleUrl))
            require(bytes.size <= MAX_BYTES) { "attachment larger than $MAX_BYTES bytes" }
            when (checkNotNull(AttachmentFormat.of(url))) {
                AttachmentFormat.PDF -> relevantPages(bytes)
                AttachmentFormat.XHTML -> relevantXhtmlPages(bytes)
            }
        }.onFailure { log.warn("Skipping attachment {}: {}", url, it.message) }.getOrNull()

    internal fun relevantPages(pdf: ByteArray): String =
        Loader.loadPDF(pdf).use { document ->
            val stripper = PDFTextStripper()
            (1..document.numberOfPages)
                .map { page ->
                    stripper.startPage = page
                    stripper.endPage = page
                    stripper.getText(document)
                }
                .relevant()
        }

    internal fun relevantXhtmlPages(xhtml: ByteArray): String {
        val document = Jsoup.parse(ByteArrayInputStream(xhtml), null, "")
        document.select("style, script").remove()
        return document.select(XHTML_PAGE).ifEmpty { listOf(document.body()) }.map { it.text() }.relevant()
    }

    private fun List<String>.relevant(): String =
        filterIndexed { index, text -> index < ALWAYS_KEPT_PAGES || text.hasKeyword() }
            .joinToString(" ") { it.normalizeSpaces() }

    private fun String.hasKeyword(): Boolean {
        val folded = foldDiacritics()
        return KEYWORDS.any { it in folded }
    }

    private fun String.foldDiacritics() =
        Normalizer.normalize(lowercase().replace('ł', 'l'), Normalizer.Form.NFD).replace(DIACRITICS, "")

    private fun String.normalizeSpaces() = replace(WHITESPACE, " ").trim()

    private companion object {
        const val MAX_CHARS = 30_000
        const val MAX_ATTACHMENTS = 3
        const val MAX_BYTES = 10 * 1024 * 1024
        const val ALWAYS_KEPT_PAGES = 2
        const val ATTACHMENT_PATH = "/static/att/"
        const val XHTML_PAGE = "div.pf"
        val ENGLISH_FILE = Regex("(^|[_\\-. ])(EN|ENG|English)([_\\-. ]|$)", RegexOption.IGNORE_CASE)
        val WHITESPACE = Regex("[\\s\\u00A0]+")
        val DIACRITICS = Regex("\\p{M}+")
        val KEYWORDS = listOf(
            "przychod", "sprzedaz", "rachunek zyskow", "wybrane dane", "zysk netto", "ebitda",
            "revenue", "net profit", "prognoz", "perspektyw", "portfel zamowien",
        )
    }
}
