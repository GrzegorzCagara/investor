package pl.investor.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreColorsTest {

    @Test
    fun `colors follow thresholds`() {
        assertEquals(0xFF1F9D55, ScoreColors.forScore(84))
        assertEquals(0xFF1F9D55, ScoreColors.forScore(70))
        assertEquals(0xFFD69E2E, ScoreColors.forScore(69))
        assertEquals(0xFFD69E2E, ScoreColors.forScore(50))
        assertEquals(0xFFA0AEC0, ScoreColors.forScore(49))
        assertEquals(0xFFA0AEC0, ScoreColors.forScore(41))
    }

    @Test
    fun `missing score is grey`() {
        assertEquals(0xFFA0AEC0, ScoreColors.forScore(null))
    }
}
