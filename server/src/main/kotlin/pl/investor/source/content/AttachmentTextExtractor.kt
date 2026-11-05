package pl.investor.source.content

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.text.Normalizer

data class AttachmentReading(val text: String, val nothingReadable: Boolean)

enum class PageOrder { LEADING_PAGES_FIRST, KEYWORD_PAGES_FIRST }

@Component
class AttachmentTextExtractor(
    private val http: PoliteHttpClient,
    private val unpacker: ArchiveUnpacker = ArchiveUnpacker(),
) {

    private sealed interface Outcome {
        data class Readable(val texts: List<String>) : Outcome
        data object Unreadable : Outcome
        data object NotYetReadable : Outcome
    }

    private val log = LoggerFactory.getLogger(AttachmentTextExtractor::class.java)

    fun read(articleUrl: String): AttachmentReading {
        val links = attachmentLinks(articleUrl)
        val supported = links.filter { AttachmentFormat.of(it) != null }
        if (links.isNotEmpty() && supported.isEmpty()) return AttachmentReading("", nothingReadable = true)
        val outcomes = supported.chosenForReading().map { url -> attachmentOutcome(url, articleUrl) }
        val texts = outcomes.filterIsInstance<Outcome.Readable>().flatMap { it.texts }
        return AttachmentReading(joined(texts), nothingReadable = outcomes.isNotEmpty() && outcomes.all { it == Outcome.Unreadable })
    }

    fun readNasdaqPdfs(urls: List<String>): AttachmentReading = readPdfs(urls.take(MAX_ATTACHMENTS), Source.NASDAQ_NORDIC, NORDIC_KEYWORDS)

    fun readEuronextPdfs(urls: List<String>): AttachmentReading = readPdfs(urls.take(MAX_EURONEXT_PDFS), Source.EURONEXT, EURONEXT_KEYWORDS)

    private fun readPdfs(urls: List<String>, source: Source, keywords: List<String>): AttachmentReading {
        val outcomes = urls.map { pdfOutcome(it, source, keywords) }
        val texts = outcomes.filterIsInstance<Outcome.Readable>().flatMap { it.texts }
        return AttachmentReading(joined(texts), nothingReadable = outcomes.isNotEmpty() && outcomes.all { it == Outcome.Unreadable })
    }

    internal fun joined(texts: List<String>): String = texts.fairlyTruncated().joinToString(" ").take(MAX_CHARS)

    private fun List<String>.fairlyTruncated(): List<String> {
        var remainingChars = MAX_CHARS - (size - 1).coerceAtLeast(0)
        var remainingTexts = size
        val limits = withIndex().sortedBy { it.value.length }.associate { (index, text) ->
            val limit = minOf(text.length, remainingChars / remainingTexts)
            remainingChars -= limit
            remainingTexts--
            index to limit
        }
        return mapIndexed { index, text -> text.take(limits.getValue(index)).trim() }
    }

    private fun attachmentLinks(articleUrl: String): List<String> =
        Jsoup.parse(http.get(Source.BANKIER, articleUrl), articleUrl)
            .select("a[href]")
            .map { it.absUrl("href") }
            .filter { ATTACHMENT_PATH in it }
            .distinct()

    private fun List<String>.chosenForReading(): List<String> {
        val (archives, documents) = partition { AttachmentFormat.of(it)?.isArchive == true }
        val candidates = documents.ifEmpty { archives }
        val polish = candidates.filterNot { ENGLISH_FILE.containsMatchIn(it.substringAfterLast('/')) }
        return polish.ifEmpty { candidates }.take(MAX_ATTACHMENTS)
    }

    private fun attachmentOutcome(url: String, articleUrl: String): Outcome {
        val format = checkNotNull(AttachmentFormat.of(url))
        return try {
            val bytes = http.getBytes(Source.BANKIER, url, mapOf("Referer" to articleUrl))
            if (format.isArchive) archiveOutcome(bytes, format, url) else documentOutcome(bytes, format)
        } catch (e: Exception) {
            log.warn("Skipping attachment {}: {}", url, e.message)
            Outcome.NotYetReadable
        }
    }

    private fun documentOutcome(bytes: ByteArray, format: AttachmentFormat): Outcome {
        require(bytes.size <= MAX_ATTACHMENT_BYTES) { "attachment larger than $MAX_ATTACHMENT_BYTES bytes" }
        val text = documentText(bytes, format, PageOrder.LEADING_PAGES_FIRST)
        return if (text.isBlank()) Outcome.NotYetReadable else Outcome.Readable(listOf(text))
    }

    private fun archiveOutcome(bytes: ByteArray, format: AttachmentFormat, url: String): Outcome {
        val texts = try {
            unpacker.readDocuments(bytes, format, ::archivedDocumentText)
        } catch (e: Exception) {
            log.warn("Archive {} is not readable: {}", url, e.message)
            emptyList()
        }
        return if (texts.isEmpty()) Outcome.Unreadable else Outcome.Readable(texts)
    }

    private fun archivedDocumentText(document: ArchivedDocument): String? =
        try {
            documentText(document.bytes, document.format, PageOrder.KEYWORD_PAGES_FIRST).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            log.warn("Skipping archived document {}: {}", document.name, e.message)
            null
        }

    private fun pdfOutcome(url: String, source: Source, keywords: List<String>): Outcome {
        val bytes = try {
            http.getBytes(source, url, emptyMap())
        } catch (e: SourceUnavailableException) {
            log.warn("Attachment {} not available yet: {}", url, e.message)
            return Outcome.NotYetReadable
        } catch (e: IOException) {
            log.warn("Attachment {} not available yet: {}", url, e.message)
            return Outcome.NotYetReadable
        }
        val text = bytes.takeIf { it.size <= MAX_ATTACHMENT_BYTES }?.let { pdf ->
            runCatching { relevantPages(pdf, PageOrder.LEADING_PAGES_FIRST, keywords) }
                .onFailure { log.warn("Attachment {} is not a readable PDF: {}", url, it.message) }
                .getOrNull()
        }
        return text?.takeIf { it.isNotBlank() }?.let { Outcome.Readable(listOf(it)) } ?: Outcome.Unreadable
    }

    private fun documentText(bytes: ByteArray, format: AttachmentFormat, order: PageOrder): String = when (format) {
        AttachmentFormat.PDF -> relevantPages(bytes, order)
        AttachmentFormat.XHTML -> relevantXhtmlPages(bytes, order)
        AttachmentFormat.RAR, AttachmentFormat.ZIP -> error("Nested $format archives are not unpacked")
    }

    internal fun relevantPages(
        pdf: ByteArray,
        order: PageOrder = PageOrder.LEADING_PAGES_FIRST,
        keywords: List<String> = POLISH_KEYWORDS,
    ): String =
        Loader.loadPDF(pdf).use { document ->
            val stripper = PDFTextStripper()
            (1..document.numberOfPages)
                .map { page ->
                    stripper.startPage = page
                    stripper.endPage = page
                    stripper.getText(document)
                }
                .relevant(order, keywords)
        }

    internal fun relevantXhtmlPages(
        xhtml: ByteArray,
        order: PageOrder = PageOrder.LEADING_PAGES_FIRST,
        keywords: List<String> = POLISH_KEYWORDS,
    ): String {
        val document = Jsoup.parse(ByteArrayInputStream(xhtml), null, "")
        document.select("style, script").remove()
        return document.select(XHTML_PAGE).ifEmpty { listOf(document.body()) }.map { it.text() }.relevant(order, keywords)
    }

    private fun List<String>.relevant(order: PageOrder, keywords: List<String>): String {
        val (keywordPages, otherPages) = withIndex().partition { it.value.hasKeywordOf(keywords) }
        val leadingPages = otherPages.filter { it.index < ALWAYS_KEPT_PAGES }
        val kept = when (order) {
            PageOrder.LEADING_PAGES_FIRST -> (keywordPages + leadingPages).sortedBy { it.index }
            PageOrder.KEYWORD_PAGES_FIRST -> keywordPages + leadingPages
        }
        return kept.joinToString(" ") { it.value.normalizeSpaces() }
    }

    private fun String.hasKeywordOf(keywords: List<String>): Boolean {
        val folded = foldDiacritics()
        return keywords.any { it in folded }
    }

    private fun String.foldDiacritics() =
        Normalizer.normalize(lowercase().replace('ł', 'l'), Normalizer.Form.NFD).replace(DIACRITICS, "")

    private fun String.normalizeSpaces() = replace(WHITESPACE, " ").trim()

    private companion object {
        const val MAX_CHARS = 30_000
        const val MAX_ATTACHMENTS = 3
        const val MAX_EURONEXT_PDFS = 2
        const val ALWAYS_KEPT_PAGES = 2
        const val ATTACHMENT_PATH = "/static/att/"
        const val XHTML_PAGE = "div.pf"
        val ENGLISH_FILE = Regex("(^|[_\\-. ])(EN|ENG|English)([_\\-. ]|$)", RegexOption.IGNORE_CASE)
        val WHITESPACE = Regex("[\\s\\u00A0]+")
        val DIACRITICS = Regex("\\p{M}+")
        val POLISH_KEYWORDS = listOf(
            "przychod", "sprzedaz", "rachunek zyskow", "wybrane dane", "zysk netto", "ebitda",
            "revenue", "net profit", "prognoz", "perspektyw", "portfel zamowien",
        )
        val NORDIC_KEYWORDS = listOf(
            "nettoomsattning", "net sales", "revenue", "rorelseresultat", "operating profit", "ebit",
            "orderingang", "order intake", "resultat efter skatt", "net profit",
        )
        val EURONEXT_KEYWORDS = listOf(
            "revenue", "net sales", "net income", "net result", "net profit", "ebit", "orders", "order intake", "outlook",
            "omzet", "bedrijfsresultaat", "nettowinst", "nettoresultaat", "vooruitzichten",
        )
    }
}
