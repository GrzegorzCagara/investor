package pl.investor.news

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val THOUSAND = BigDecimal(1000)
private val MILLION = BigDecimal(1_000_000)

fun NewsAssessment.contractValueText(): String? {
    val value = contractValue ?: return null
    val currency = contractCurrency ?: return null
    val unit = if (currency == ContractCurrency.OTHER) "w innej walucie" else currency.name
    val absolute = value.abs()
    val text = when {
        absolute >= MILLION -> "${polish(value.divide(MILLION), 2)} mld"
        absolute >= THOUSAND -> "${polish(value.divide(THOUSAND), 1)} mln"
        else -> "${polish(value, 0)} tys."
    }
    return "$text $unit"
}

private fun polish(value: BigDecimal, decimals: Int): String =
    DecimalFormat(if (decimals == 0) "#,##0" else "#,##0.${"#".repeat(decimals)}", DecimalFormatSymbols(Locale.forLanguageTag("pl-PL")))
        .apply { roundingMode = RoundingMode.HALF_UP }
        .format(value)
        .replace(NON_BREAKING_SPACES, " ")

private val NON_BREAKING_SPACES = Regex("[\\u00A0\\u202F]")
