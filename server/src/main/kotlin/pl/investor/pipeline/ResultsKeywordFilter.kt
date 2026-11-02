package pl.investor.pipeline

object ResultsKeywordFilter {

    private val KEYWORDS = listOf("przychod", "sprzedaz", "wynik", "szacunk", "prognoz", "zysk", "ebitda", "dane finansowe")
    private val ENGLISH_KEYWORDS = Regex(
        "\\b(results|revenue|sales|profit|earnings|interim|quarterly report|annual report|half-year|half year|financial statements|financial report)",
    )
    private val SWEDISH_KEYWORDS = Regex(
        "\\b(resultat|rorelseresultat|omsattning|nettoomsattning|forsaljning|delarsrapport|kvartalsrapport|bokslutskommunike|vinst)",
    )
    private val CORRECTION = Regex("^(korekta|correction|rattelse)")

    fun matches(title: String): Boolean = title.foldedTitle().let { text ->
        KEYWORDS.any { it in text } || ENGLISH_KEYWORDS.containsMatchIn(text) || SWEDISH_KEYWORDS.containsMatchIn(text)
    }

    fun isCorrection(title: String): Boolean = CORRECTION.containsMatchIn(title.foldedTitle())
}
