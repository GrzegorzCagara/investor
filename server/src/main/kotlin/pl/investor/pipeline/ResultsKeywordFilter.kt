package pl.investor.pipeline

import java.text.Normalizer

object ResultsKeywordFilter {

    private val KEYWORDS = listOf("przychod", "sprzedaz", "wynik", "szacunk", "prognoz", "zysk", "ebitda", "dane finansowe")
    private val DIACRITICS = Regex("\\p{M}+")

    fun matches(title: String): Boolean = normalized(title).let { text -> KEYWORDS.any { it in text } }

    fun isCorrection(title: String): Boolean = normalized(title).trimStart().startsWith("korekta")

    private fun normalized(text: String): String =
        Normalizer.normalize(text.lowercase().replace('ł', 'l'), Normalizer.Form.NFD).replace(DIACRITICS, "")
}
