package pl.investor.notification

import org.springframework.stereotype.Component
import pl.investor.extraction.OutlookTone
import pl.investor.report.Company
import pl.investor.scoring.Score
import pl.investor.scoring.ScoringInput
import pl.investor.scoring.roundedHalfUp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

@Component
class ReasonTemplate {

    fun render(company: Company, input: ScoringInput, score: Score): String {
        val fragments = listOfNotNull(
            input.revenueYoyPct?.let { "przychody ${it.signedWhole()}% r/r" },
            input.sur?.let { it.surpriseDescription() },
            input.consecutiveStrongBefore.takeIf { it >= MIN_SERIES }?.let { "${it + 1}. mocny raport z rzędu" },
            input.operatingProfitYoyPct?.let { "zysk operacyjny ${it.signedWhole()}% r/r" },
            input.outlook?.outlookDescription(),
            valuationFragment(input),
            runUpFragment(input, score),
        )
        return if (fragments.isEmpty()) NO_DATA else fragments.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
    }

    private fun Double.surpriseDescription() = when {
        this >= 1 -> "wyraźnie ponad dotychczasowy trend"
        this >= 0 -> "zgodnie z trendem"
        else -> "poniżej trendu"
    }

    private fun OutlookTone.outlookDescription() = when (this) {
        OutlookTone.POSITIVE -> "zarząd zapowiada dalsze wzrosty"
        OutlookTone.NEGATIVE -> "zarząd zapowiada słabsze wyniki"
        OutlookTone.NONE -> null
    }

    private fun valuationFragment(input: ScoringInput): String? {
        val pe = input.pe ?: return null
        val growth = input.ttmNetProfitGrowthPct ?: return null
        return "C/Z ${pe.oneDecimal()} przy wzroście zysku ${growth.signedWhole()}%"
    }

    private fun runUpFragment(input: ScoringInput, score: Score): String? =
        input.priceChange10Pct?.takeIf { score.penalty > 0 }?.let { "kurs wzrósł o ${it.whole()}% w 10 sesjach przed raportem" }

    private fun Double.whole() = roundedHalfUp().toPlainString()

    private fun Double.signedWhole() = whole().let { if (it.startsWith("-") || it == "0") it else "+$it" }

    private fun Double.oneDecimal() = DecimalFormat("0.0", DecimalFormatSymbols(Locale.forLanguageTag("pl-PL"))).format(this)

    private companion object {
        const val MIN_SERIES = 2
        const val NO_DATA = "Brak wystarczających danych do uzasadnienia oceny."
    }
}
