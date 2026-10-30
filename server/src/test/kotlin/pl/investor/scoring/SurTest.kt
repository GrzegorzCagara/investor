package pl.investor.scoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SurTest {
    @Test fun `sur needs at least four previous changes`() { assertNull(Sur.compute(10.0, 5.0, listOf(1.0, 2.0, 3.0))) }
    @Test fun `sur value`() { assertEquals(3.6742, Sur.compute(110.0, 100.0, listOf(2.0, 4.0, 6.0, 4.0))!!, 1e-4) }
    @Test fun `sur is null when deviation is zero`() { assertNull(Sur.compute(10.0, 5.0, listOf(2.0, 2.0, 2.0, 2.0))) }
    @Test fun `sur is null when a previous change is NaN`() { assertNull(Sur.compute(10.0, 5.0, listOf(1.0, Double.NaN, 3.0, 4.0))) }
}
