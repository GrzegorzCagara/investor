package pl.investor.scoring

import java.math.BigDecimal
import java.math.RoundingMode

data class ScoreComponents(
    val surprise: Double,
    val growth: Double,
    val quality: Double,
    val series: Double,
    val outlook: Double,
    val valuation: Double,
) {
    val sum: Double get() = surprise + growth + quality + series + outlook + valuation
}

data class Score(val total: Double, val components: ScoreComponents, val penalty: Double, val warnings: Set<Warning>)

fun Double.roundedHalfUp(): BigDecimal = BigDecimal.valueOf(this).setScale(0, RoundingMode.HALF_UP)

fun Score.displayScore(): Int = total.roundedHalfUp().toInt()
