package pl.investor.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationChannelsTest {

    @Test
    fun `known channel ids are kept`() {
        assertEquals("alerty", NotificationChannels.resolve("alerty"))
        assertEquals("alerty_ciche", NotificationChannels.resolve("alerty_ciche"))
    }

    @Test
    fun `missing or unknown channel falls back to alerty`() {
        assertEquals("alerty", NotificationChannels.resolve(null))
        assertEquals("alerty", NotificationChannels.resolve("fcm_fallback_notification_channel"))
    }
}
