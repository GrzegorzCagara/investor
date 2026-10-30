package pl.investor.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["investor.scheduling.enabled=false", "investor.api-token=test-token", "investor.contact-email=test@example.com", "investor.anthropic.api-key=test-key"])
@Testcontainers
abstract class PostgresTestBase {

    companion object {
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17").apply { start() }
    }
}
