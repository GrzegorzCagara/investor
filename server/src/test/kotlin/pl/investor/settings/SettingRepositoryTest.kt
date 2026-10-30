package pl.investor.settings

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import pl.investor.support.PostgresTestBase
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingRepositoryTest : PostgresTestBase() {

    @Autowired lateinit var settings: SettingRepository

    @Test
    fun `missing key is null and put overwrites`() {
        assertNull(settings.find("LAST_POLLED_DATE_TEST"))

        settings.put("LAST_POLLED_DATE_TEST", "2026-09-20")
        settings.put("LAST_POLLED_DATE_TEST", "2026-09-21")

        assertEquals("2026-09-21", settings.find("LAST_POLLED_DATE_TEST"))
    }
}
