package pl.investor.source.biznesradar

import java.math.BigDecimal

object PolishNumbers {

    private val WHITESPACE = Regex("[\\s\\u00A0\\u202F]+")
    private val PLAIN_NUMBER = Regex("-?\\d+(\\.\\d+)?")
    private val NUMBER = """-?(?:\d{1,3}(?:,\d{3}){2,}(?:\.\d+)?|\d{1,3}(?:,\d{3})+\.\d+|\d[\d\s  ]*(?:,\d+)?)"""
    private val AMOUNT = Regex(
        "($NUMBER)\\s*(tys|mln|mld|thousand|million|billion|mdsek|mdkr|msek|mnkr|mkr|ksek|tsek|tkr|miljoner|miljarder|tusen)?",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): BigDecimal? =
        text.replace(WHITESPACE, "")
            .let { if (it.isEnglishFormatted()) it.replace(",", "") else it.replace(',', '.') }
            .takeIf { PLAIN_NUMBER.matches(it) }
            ?.let(::BigDecimal)

    fun parseAmountInThousands(text: String): BigDecimal? {
        val match = AMOUNT.find(text) ?: return null
        val value = parse(match.groupValues[1]) ?: return null
        return when (match.groupValues[2].lowercase()) {
            "tys", "thousand", "ksek", "tsek", "tkr", "tusen" -> value
            "mln", "million", "msek", "mnkr", "mkr", "miljoner" -> value.movePointRight(3)
            "mld", "billion", "mdsek", "mdkr", "miljarder" -> value.movePointRight(6)
            else -> value.movePointLeft(3)
        }
    }

    private fun String.isEnglishFormatted(): Boolean {
        val commas = count { it == ',' }
        val firstComma = indexOf(',')
        return commas >= 2 || (firstComma >= 0 && indexOf('.', firstComma) > firstComma)
    }
}
