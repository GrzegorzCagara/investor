package pl.investor.extraction

import pl.investor.history.PeriodKind
import java.math.BigDecimal
import java.time.LocalDate

enum class ReportCategory { RESULTS, CONTRACT, OTHER }

enum class OutlookTone { POSITIVE, NONE, NEGATIVE }

data class Amount(val valueInThousands: BigDecimal, val sourceText: String)

data class ExtractedPeriod(
    val kind: PeriodKind,
    val periodEnd: LocalDate,
    val currency: String,
    val revenue: Amount?,
    val revenuePrevYear: Amount?,
    val operatingProfit: Amount?,
    val operatingProfitPrevYear: Amount?,
    val netProfit: Amount?,
    val netProfitPrevYear: Amount?,
    val orderIntake: Amount? = null,
    val orderIntakePrevYear: Amount? = null,
    val revenueLines: List<RevenueLine> = emptyList(),
)

data class RevenueLine(val name: String, val revenue: Amount, val revenuePrevYear: Amount?)

data class Outlook(val tone: OutlookTone, val quote: String?)

data class CashFlow(val operatingCashFlow: Amount, val netProfit: Amount)

fun CashFlow.isProfitWithoutCash(): Boolean = netProfit.valueInThousands.signum() > 0 && operatingCashFlow.valueInThousands.signum() < 0

data class Extraction(
    val category: ReportCategory,
    val periods: List<ExtractedPeriod>,
    val outlook: Outlook,
    val oneOffQuarter: Boolean,
    val oneOffQuote: String?,
    val cyclicalIndustry: Boolean,
    val cashFlow: CashFlow? = null,
    val acquisitionInPeriod: Boolean = false,
    val acquisitionQuote: String? = null,
    val forecast: Forecast? = null,
)

data class ForecastAmount(val low: Amount, val high: Amount?) {
    fun midpoint(): BigDecimal = high?.let { (low.valueInThousands + it.valueInThousands).divide(BigDecimal.TWO) } ?: low.valueInThousands
}

data class Forecast(
    val periodEnd: LocalDate,
    val currency: String,
    val revenue: ForecastAmount?,
    val operatingProfit: ForecastAmount?,
    val netProfit: ForecastAmount?,
)

data class VerifiedExtraction(val extraction: Extraction, val uncertainFields: Set<String>)

class AiBudgetExhaustedException(val pool: AiPool) : RuntimeException("AI budget exhausted for pool $pool")

class ExtractionFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class AiUnavailableException(cause: Throwable) : RuntimeException("Anthropic API temporarily unavailable", cause)

class AiAuthenticationException(cause: Throwable) : RuntimeException("Anthropic API rejected the API key", cause)
