package pl.investor.source.consensus

import java.math.BigDecimal
import java.time.Instant

data class ConsensusRow(val label: String, val actual: BigDecimal, val consensus: BigDecimal, val differencePct: Double)

data class ConsensusNote(val url: String, val publishedAt: Instant, val rows: List<ConsensusRow>)

data class ConsensusLink(val url: String, val listedAt: Instant?)
