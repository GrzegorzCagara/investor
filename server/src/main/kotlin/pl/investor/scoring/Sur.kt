package pl.investor.scoring

import kotlin.math.sqrt

object Sur {
    private const val MIN_HISTORY = 4
    private const val MIN_DEVIATION = 1e-9

    fun compute(current: Double, yearAgo: Double, previousChanges: List<Double>): Double? {
        if (previousChanges.size < MIN_HISTORY) return null
        if (!current.isFinite() || !yearAgo.isFinite() || previousChanges.any { !it.isFinite() }) return null
        val mean = previousChanges.average()
        val deviation = sqrt(previousChanges.sumOf { (it - mean) * (it - mean) } / (previousChanges.size - 1))
        if (deviation < MIN_DEVIATION) return null
        return ((current - yearAgo - mean) / deviation).takeIf { it.isFinite() }
    }
}
