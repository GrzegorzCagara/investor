package pl.investor.extraction

import pl.investor.report.Market
import java.math.BigDecimal

enum class AiPool(val limitUsd: BigDecimal) {
    GPW(BigDecimal("1.90")),
    US(BigDecimal("0.00")),
    NEWS(BigDecimal("1.00")),
    SE(BigDecimal("1.00")),
    SE_NEWS(BigDecimal("0.25")),
    DE(BigDecimal("0.40")),
    DE_NEWS(BigDecimal("0.20")),
}

fun Market.resultsPool(): AiPool = when (this) {
    Market.GPW, Market.NC -> AiPool.GPW
    Market.SE -> AiPool.SE
    Market.DE -> AiPool.DE
    Market.US -> AiPool.US
}

fun Market.newsPool(): AiPool = when (this) {
    Market.GPW, Market.NC -> AiPool.NEWS
    Market.SE -> AiPool.SE_NEWS
    Market.DE -> AiPool.DE_NEWS
    Market.US -> AiPool.US
}
