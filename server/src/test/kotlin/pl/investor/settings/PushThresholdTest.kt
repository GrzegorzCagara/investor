package pl.investor.settings

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PushThresholdTest {

    private val settings = mockk<SettingRepository> { every { put(any(), any()) } returns Unit }

    @Test
    fun `update rejects values outside 0 to 100`() {
        val threshold = PushThreshold(settings)

        assertFailsWith<IllegalArgumentException> { threshold.update(101) }
        assertFailsWith<IllegalArgumentException> { threshold.update(-1) }

        verify(exactly = 0) { settings.put(any(), any()) }
    }

    @Test
    fun `update stores the boundaries`() {
        val threshold = PushThreshold(settings)

        threshold.update(0)
        threshold.update(100)

        verify { settings.put("PUSH_THRESHOLD", "0") }
        verify { settings.put("PUSH_THRESHOLD", "100") }
    }
}
