package pl.investor.scoring

import java.math.BigDecimal

enum class ConsensusMetric { REVENUE, EBIT }

data class ConsensusComparison(
    val metric: ConsensusMetric,
    val actual: BigDecimal,
    val consensus: BigDecimal,
    val currency: String,
    val source: String,
    val analysts: Int?,
) {
    fun differencePct(): Double = (actual - consensus).toDouble() / consensus.abs().toDouble() * 100
}
