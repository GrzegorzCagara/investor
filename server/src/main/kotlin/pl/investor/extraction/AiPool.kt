package pl.investor.extraction

import java.math.BigDecimal

enum class AiPool(val limitUsd: BigDecimal) {
    GPW(BigDecimal("1.90")),
    US(BigDecimal("2.85")),
}
