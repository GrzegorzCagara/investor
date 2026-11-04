package pl.investor.pipeline

import pl.investor.report.Market

object ResultsKeywordFilter {

    private val KEYWORDS = listOf("przychod", "sprzedaz", "wynik", "szacunk", "prognoz", "zysk", "ebitda", "dane finansowe")
    private val ENGLISH_KEYWORDS = Regex(
        "\\b(results|revenue|sales|profit|earnings|interim|quarterly report|annual report|half-year|half year|financial statements|financial report)",
    )
    private val SWEDISH_KEYWORDS = Regex(
        "\\b(resultat|rorelseresultat|omsattning|nettoomsattning|forsaljning|delarsrapport|kvartalsrapport|bokslutskommunike|vinst)",
    )
    private val GERMAN_KEYWORDS = Regex(
        "quartal|zwischenmitteilung|neunmonat|9-monat|halbjahr|geschaftsjahr|jahresergebnis|umsatz|ergebnis|ebit|gewinn|prognose|ausblick|zahlen|" +
            "\\b(9m|h1|q[1-4]|result|outlook|guidance|forecast|(first|second|third|fourth) quarter|nine[- ]months?|9-months?|first half)\\b",
    )
    private val RESULTS_ANNOUNCEMENT = Regex(
        "\\bto publish|einladung|invitation|conference call|earnings call|webcast|save the date|veroffentlichung .*\\bam\\b|\\btermin",
    )
    private val CORRECTION = Regex("^(korekta|correction|rattelse)")

    fun matches(title: String, market: Market): Boolean = title.foldedTitle().let { text ->
        KEYWORDS.any { it in text } || ENGLISH_KEYWORDS.containsMatchIn(text) || SWEDISH_KEYWORDS.containsMatchIn(text) ||
            (market == Market.DE && GERMAN_KEYWORDS.containsMatchIn(text))
    }

    fun isResultsRelease(title: String): Boolean =
        matches(title, Market.DE) && !RESULTS_ANNOUNCEMENT.containsMatchIn(title.foldedTitle())

    fun isCorrection(title: String): Boolean = CORRECTION.containsMatchIn(title.foldedTitle())
}
