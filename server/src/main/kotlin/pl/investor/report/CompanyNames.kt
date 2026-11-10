package pl.investor.report

import java.text.Normalizer

object CompanyNames {

    private val LEGAL_FORM = Regex(
        "\\(publ\\)|(?<![\\p{L}\\p{N}])(w restrukturyzacji|w likwidacji|w upad[lł]o[sś]ci|alternatywna sp[oó][lł]ka inwestycyjna|sp[oó][lł]ka akcyjna|s\\.a\\.?|n\\.v\\.?|hf\\.?|ltd\\.?|ab|abp|ad|asa|oy|oyj|plc|publ|sa|se|nv)(?![\\p{L}\\p{N}])",
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
        Normalizer.normalize(name.lowercase().replace('ł', 'l'), Normalizer.Form.NFD).replace(DIACRITICS, "").replace(NON_ALPHANUMERIC, "")

    fun keyWithoutLegalForm(name: String): String = key(withoutLegalForm(name))

    fun sameCompany(name: String, other: String): Boolean =
        keyWithoutLegalForm(name).let { it.isNotEmpty() && it == keyWithoutLegalForm(other) }
}
