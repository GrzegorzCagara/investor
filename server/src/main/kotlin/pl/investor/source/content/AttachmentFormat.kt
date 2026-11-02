package pl.investor.source.content

enum class AttachmentFormat(private val urlPattern: Regex) {
    PDF(Regex("\\.pdf(,|$)", RegexOption.IGNORE_CASE)),
    XHTML(Regex("\\.xhtml(,|$)", RegexOption.IGNORE_CASE)),
    ;

    companion object {
        fun of(url: String): AttachmentFormat? = entries.firstOrNull { it.urlPattern.containsMatchIn(url) }
    }
}
