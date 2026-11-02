package pl.investor.source.content

enum class AttachmentFormat(private val urlPattern: Regex, val isArchive: Boolean = false) {
    PDF(Regex("\\.pdf(,|$)", RegexOption.IGNORE_CASE)),
    XHTML(Regex("\\.xhtml(,|$)", RegexOption.IGNORE_CASE)),
    RAR(Regex("\\.rar(,|$)", RegexOption.IGNORE_CASE), isArchive = true),
    ZIP(Regex("\\.zip(,|$)", RegexOption.IGNORE_CASE), isArchive = true),
    ;

    companion object {
        fun of(url: String): AttachmentFormat? = entries.firstOrNull { it.urlPattern.containsMatchIn(url) }
    }
}
