package pl.investor.news

import pl.investor.report.Market
import pl.investor.source.content.ReportContent
import java.math.BigDecimal

enum class NewsCategory(val label: String) {
    CONTRACT("Umowa"),
    ACQUISITION("Przejęcie"),
    TENDER_OFFER("Wezwanie"),
    BUYBACK("Skup akcji"),
    DIVIDEND("Dywidenda"),
    LISTING("Notowanie na innej giełdzie"),
    GUIDANCE("Prognoza / strategia"),
    SHARE_ISSUE("Emisja akcji"),
    FINANCING("Finansowanie"),
    LEGAL_REGULATORY("Sąd / regulacje"),
    MANAGEMENT("Zarząd"),
    OTHER("Inne"),
}

enum class NewsDirection(val label: String) {
    POSITIVE("pozytywny"),
    NEGATIVE("negatywny"),
    NEUTRAL("neutralny"),
}

enum class ContractCurrency { PLN, EUR, USD, GBP, CHF, CZK, BGN, OTHER }

data class ContractValue(val valueInThousands: BigDecimal, val currency: ContractCurrency, val sourceText: String)

data class ModelNewsAssessment(
    val category: NewsCategory,
    val direction: NewsDirection,
    val impact: Double,
    val contractValue: ContractValue?,
    val reason: String,
)

data class NewsAssessment(
    val category: NewsCategory,
    val direction: NewsDirection,
    val impact: Double,
    val reason: String,
    val contractValue: BigDecimal?,
    val contractCurrency: ContractCurrency?,
    val revenueSharePct: Double?,
) {
    val score: Double get() = if (direction == NewsDirection.POSITIVE) impact else 0.0
}

data class NewsInput(
    val companyName: String,
    val market: Market,
    val title: String,
    val content: ReportContent,
    val trailingRevenuePln: BigDecimal?,
) {
    val announcement: String get() = "Tytuł: $title\n\n${content.text.take(NewsPrompt.MAX_BODY_CHARS)}"
}

class NewsAssessmentFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

const val NEWS_LISTED_MIN_SCORE = 50
