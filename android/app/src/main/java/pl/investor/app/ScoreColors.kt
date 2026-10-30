package pl.investor.app

object ScoreColors {
    const val GREEN = 0xFF1F9D55
    const val AMBER = 0xFFD69E2E
    const val GREY = 0xFFA0AEC0

    fun forScore(score: Int?): Long = when {
        score == null -> GREY
        score >= 70 -> GREEN
        score >= 50 -> AMBER
        else -> GREY
    }
}
