package pl.investor.history

import java.math.BigDecimal
import java.time.LocalDate

data class ValuationPoint(
    val companyId: Long,
    val quarterEnd: LocalDate,
    val price: BigDecimal?,
    val shares: Long?,
    val pe: BigDecimal?,
    val ps: BigDecimal?,
)
