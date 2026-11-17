package pl.investor.history

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class CompanyForecast(
    val companyId: Long,
    val periodEnd: LocalDate,
    val currency: String,
    val revenue: BigDecimal?,
    val operatingProfit: BigDecimal?,
    val netProfit: BigDecimal?,
    val publishedAt: Instant,
    val reportId: Long?,
)
