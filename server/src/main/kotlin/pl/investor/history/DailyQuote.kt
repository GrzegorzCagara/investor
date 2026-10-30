package pl.investor.history

import java.math.BigDecimal
import java.time.LocalDate

data class DailyQuote(
    val instrument: String,
    val date: LocalDate,
    val close: BigDecimal,
    val volume: Long,
    val turnover: BigDecimal?,
)
