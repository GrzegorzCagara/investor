package pl.investor.settings

import io.mockk.every
import io.mockk.mockk
import pl.investor.report.EventType
import pl.investor.report.Market
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlertFiltersTest {

    private fun inMemorySettings(vararg initial: Pair<String, String>): SettingRepository {
        val values = mutableMapOf(*initial)
        return mockk {
            every { find(any()) } answers { values[firstArg()] }
            every { put(any(), any()) } answers { values[firstArg()] = secondArg() }
        }
    }

    @Test
    fun `defaults allow everything`() {
        val filters = AlertFilters(inMemorySettings())

        assertEquals(emptyList(), filters.disabledMarkets())
        assertEquals(emptyList(), filters.disabledEventTypes())
        Market.entries.forEach { assertTrue(filters.allows(it, EventType.RESULTS)) }
    }

    @Test
    fun `update and read round trip`() {
        val filters = AlertFilters(inMemorySettings())

        filters.update(listOf(Market.US, Market.NC), listOf(EventType.RESULTS))

        assertEquals(listOf(Market.NC, Market.US), filters.disabledMarkets())
        assertEquals(listOf(EventType.RESULTS), filters.disabledEventTypes())
        assertFalse(filters.allows(Market.GPW, EventType.RESULTS))

        filters.update(listOf(Market.NC), emptyList())

        assertTrue(filters.allows(Market.GPW, EventType.RESULTS))
        assertFalse(filters.allows(Market.NC, EventType.RESULTS))
        assertEquals(emptyList(), filters.disabledEventTypes())
    }

    @Test
    fun `unknown stored names are ignored`() {
        val filters = AlertFilters(inMemorySettings("DISABLED_MARKETS" to "GPW,XETRA, ,", "DISABLED_EVENT_TYPES" to "CONTRACTS"))

        assertEquals(listOf(Market.GPW), filters.disabledMarkets())
        assertEquals(emptyList(), filters.disabledEventTypes())
        assertTrue(filters.allows(Market.NC, EventType.RESULTS))
    }
}
