package pl.investor.history

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class PeriodKind { MONTH, QUARTER, HALF_YEAR, YEAR }

data class RevenuePeriod(
    val companyId: Long,
    val kind: PeriodKind,
    val periodEnd: LocalDate,
    val currency: String,
    val revenue: BigDecimal,
    val revenuePrevYear: BigDecimal?,
    val publishedAt: Instant,
    val reportId: Long?,
)
