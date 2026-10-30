package pl.investor.notification

import pl.investor.extraction.OutlookTone
import pl.investor.report.Company
import pl.investor.scoring.Score
import pl.investor.scoring.ScoringInput
import pl.investor.scoring.WarningLabels

internal object ReasonPrompt {

    const val SYSTEM = """Piszesz uzasadnienie oceny raportu finansowej spółki giełdowej dla osoby bez wiedzy o giełdzie.
Napisz 1-2 krótkie zdania po polsku. Użyj wyłącznie liczb podanych przez użytkownika, niczego nie dodawaj i niczego nie zgaduj.
Nie podawaj rekomendacji kupna ani sprzedaży. Odpowiedz samym tekstem uzasadnienia."""

    fun userMessage(company: Company, input: ScoringInput, score: Score): String {
        val facts = listOfNotNull(
            "Spółka: ${company.shortName ?: company.name}",
            "Ocena raportu: ${score.total.round1()} na 100",
            input.revenueYoyPct?.let { "Przychody r/r: ${it.round1()}%" },
            input.sur?.let { "Odchylenie wzrostu przychodów od dotychczasowego trendu (w odchyleniach standardowych): ${it.round1()}" },
            "Liczba mocnych raportów z rzędu przed tym raportem: ${input.consecutiveStrongBefore}",
            input.operatingProfitYoyPct?.let { "Zysk operacyjny r/r: ${it.round1()}%" },
            input.outlook?.outlookPhrase()?.let { "Zapowiedź zarządu: $it" },
            input.pe?.let { "C/Z: ${it.round1()}" },
            input.ttmNetProfitGrowthPct?.let { "Wzrost zysku netto z 12 miesięcy: ${it.round1()}%" },
            input.priceChange10Pct?.let { "Zmiana kursu w 10 sesjach przed raportem: ${it.round1()}%" },
            "Kara za rajd kursu przed raportem: ${score.penalty.round1()} pkt",
            "Ostrzeżenia: ${score.warnings.joinToString(", ") { WarningLabels.label(it) }.ifEmpty { "brak" }}",
        )
        return facts.joinToString("\n")
    }

    private fun OutlookTone.outlookPhrase(): String? = when (this) {
        OutlookTone.POSITIVE -> "zarząd zapowiada dalsze wzrosty"
        OutlookTone.NEGATIVE -> "zarząd zapowiada słabsze wyniki"
        OutlookTone.NONE -> null
    }

    private fun Double.round1() = "%.1f".format(java.util.Locale.ROOT, this)
}
