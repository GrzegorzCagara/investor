package pl.investor.pipeline

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import pl.investor.SchedulingConfig
import kotlin.test.assertEquals

class SchedulingConfigTest {

    private val runner = ApplicationContextRunner().withUserConfiguration(SchedulingConfig::class.java)

    private fun schedulers(context: org.springframework.context.ApplicationContext) =
        context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor::class.java).size

    @Test
    fun `scheduling is on by default`() {
        runner.run { assertEquals(1, schedulers(it)) }
    }

    @Test
    fun `flag false registers no scheduled tasks`() {
        runner.withPropertyValues("investor.scheduling.enabled=false").run { assertEquals(0, schedulers(it)) }
    }
}
