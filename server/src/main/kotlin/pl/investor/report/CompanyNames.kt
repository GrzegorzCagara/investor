package pl.investor.report

import java.text.Normalizer

object CompanyNames {

    private val LEGAL_FORM = Regex(
        "\\(publ\\)|(?<![\\p{L}\\p{N}])(sp[oó][lł]ka akcyjna|s\\.a\\.?|n\\.v\\.?|hf\\.?|ltd\\.?|ab|ad|asa|oyj|plc|publ|sa|se|nv)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val APOSTROPHES = Regex("['’`´]")
    private val PUNCTUATION = Regex("[^\\p{L}\\p{N}&\\s-]+")
    private val WHITESPACE = Regex("\\s+")
    private val DIACRITICS = Regex("\\p{M}+")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

    fun withoutLegalForm(name: String): String =
        name.replace(APOSTROPHES, "").replace(LEGAL_FORM, " ").replace(PUNCTUATION, " ").replace(WHITESPACE, " ").trim()

    fun key(name: String): String =
        Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "").replace(NON_ALPHANUMERIC, "")

    fun sameCompany(name: String, other: String): Boolean =
        key(withoutLegalForm(name)).let { it.isNotEmpty() && it == key(withoutLegalForm(other)) }
}
