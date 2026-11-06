package pl.investor.pipeline

import pl.investor.support.foldedTitle
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
    private val DUTCH_KEYWORDS = Regex(
        "\\b(quarter(ly)?|[1-4]q|q[1-4]|[1-4]q-?\\d{2,4}|q[1-4]-?\\d{2,4}|nine[- ]months?|9m|h1|half[- ]year|full[- ]year|annual results|" +
            "trading update|activity update|business update|net income|ebit|ebita|ebitda|outlook|guidance|ytd|" +
            "kwartaal|kwartaalcijfers|halfjaar|halfjaarcijfers|jaarcijfers|resultaten|omzet|winst)\\b",
    )
    private val DUTCH_RESULTS_ANNOUNCEMENT = Regex(
        "\\b(to|will) (report|publish|release|announce)\\b.*\\b(results|figures|earnings)\\b.*\\bon\\b|" +
            "\\bschedule for\\b.*\\b(release|results|earnings)\\b|" +
            "\\b(invitation|uitnodiging)\\b.*\\b(webcast|call|presentation|presentatie|capital markets day|investor day)\\b|" +
            "\\b(conference call|earnings call|webcast|results release and|save the date|financial calendar|sell-side|analyst consensus)\\b",
    )
    private const val INTERIM_DIVIDEND = "interim dividend"
    private val CORRECTION = Regex("^(korekta|correction|rattelse)")

    fun matches(title: String, market: Market): Boolean = title.foldedTitle().let { text ->
        when (market) {
            Market.NL -> text.replace(INTERIM_DIVIDEND, "dividend").let { withoutDividend ->
                !DUTCH_RESULTS_ANNOUNCEMENT.containsMatchIn(withoutDividend) &&
                    (matchesAnyMarket(withoutDividend) || DUTCH_KEYWORDS.containsMatchIn(withoutDividend))
            }
            Market.DE -> matchesAnyMarket(text) || GERMAN_KEYWORDS.containsMatchIn(text)
            else -> matchesAnyMarket(text)
        }
    }

    fun isDutchResultsAnnouncement(title: String): Boolean = DUTCH_RESULTS_ANNOUNCEMENT.containsMatchIn(title.foldedTitle())

    private fun matchesAnyMarket(text: String): Boolean =
        KEYWORDS.any { it in text } || ENGLISH_KEYWORDS.containsMatchIn(text) || SWEDISH_KEYWORDS.containsMatchIn(text)

    fun isResultsRelease(title: String): Boolean =
        matches(title, Market.DE) && !RESULTS_ANNOUNCEMENT.containsMatchIn(title.foldedTitle())

    fun isCorrection(title: String): Boolean = CORRECTION.containsMatchIn(title.foldedTitle())
}
