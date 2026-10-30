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
)

data class Outlook(val tone: OutlookTone, val quote: String?)

data class Extraction(
    val category: ReportCategory,
    val periods: List<ExtractedPeriod>,
    val outlook: Outlook,
    val oneOffQuarter: Boolean,
    val oneOffQuote: String?,
    val cyclicalIndustry: Boolean,
)

data class VerifiedExtraction(val extraction: Extraction, val uncertainFields: Set<String>)

class AiBudgetExhaustedException(val pool: AiPool) : RuntimeException("AI budget exhausted for pool $pool")

class ExtractionFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class AiUnavailableException(cause: Throwable) : RuntimeException("Anthropic API temporarily unavailable", cause)

class AiAuthenticationException(cause: Throwable) : RuntimeException("Anthropic API rejected the API key", cause)
