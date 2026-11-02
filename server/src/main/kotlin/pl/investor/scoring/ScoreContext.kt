package pl.investor.scoring

data class ScoreContext(
    val ytdChangePct: Double? = null,
    val ordersYoyPct: Double? = null,
    val pastReactions: List<PastReaction> = emptyList(),
    val consensus: List<ConsensusComparison> = emptyList(),
) {
    companion object {
        val EMPTY = ScoreContext()
    }
}
