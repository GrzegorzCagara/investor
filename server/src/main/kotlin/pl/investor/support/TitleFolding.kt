package pl.investor.support

import java.text.Normalizer

private val DIACRITICS = Regex("\\p{M}+")
private val WHITESPACE = Regex("[\\s\\u00A0\\u202F]+")

fun String.foldedTitle(): String =
    Normalizer.normalize(lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .replace(WHITESPACE, " ")
        .trim()
