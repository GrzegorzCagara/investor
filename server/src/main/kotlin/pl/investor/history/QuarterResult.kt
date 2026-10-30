package pl.investor.history

import java.math.BigDecimal
import java.time.LocalDate

data class QuarterResult(
    val companyId: Long,
    val quarterEnd: LocalDate,
    val revenue: BigDecimal?,
    val operatingProfit: BigDecimal?,
    val netProfit: BigDecimal?,
    val publishedOn: LocalDate?,
)
