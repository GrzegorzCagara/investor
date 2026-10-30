package pl.investor

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import pl.investor.support.PostgresTestBase
import kotlin.test.assertEquals

@AutoConfigureTestRestTemplate
class ApplicationStartsTest : PostgresTestBase() {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Test
    fun `health is UP`() {
        val health = requireNotNull(rest.getForObject("/actuator/health", Map::class.java))
        assertEquals("UP", health["status"])
    }
}
