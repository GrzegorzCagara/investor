package pl.investor.report

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.support.PostgresTestBase
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReportRepositoryTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var reports: ReportRepository

    private var companyId = 0L

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company RESTART IDENTITY CASCADE").update()
        companyId = companies.save(Company(null, "XTB", "PLXTRDM00011", Market.GPW, "XTB", "XTB", false)).id!!
    }

    @Test
    fun `report with the same market and source ref is stored once`() {
        val first = reports.saveIfNew(report("1/2026"))
        val second = reports.saveIfNew(report("1/2026"))

        assertNotNull(first?.id)
        assertNull(second)
    }

    @Test
    fun `reports with a different source ref are stored separately`() {
        assertNotNull(reports.saveIfNew(report("1/2026")))
        assertNotNull(reports.saveIfNew(report("2/2026")))
        assertNotNull(reports.saveIfNew(report("1/2026", ReportKind.QUARTERLY, "other-ref")))
    }

    @Test
    fun `two quarterly reports of one company with the same number but different source ref are both stored`() {
        assertNotNull(reports.saveIfNew(report("/2026", ReportKind.QUARTERLY, "494951")))
        assertNotNull(reports.saveIfNew(report("/2026", ReportKind.QUARTERLY, "497364")))
    }

    @Test
    fun `saved report keeps all fields`() {
        val saved = reports.saveIfNew(report("1/2026"))!!

        assertEquals(report("1/2026").copy(id = saved.id), saved)
    }

    @Test
    fun `companies are found by isin and name`() {
        assertEquals(companyId, companies.findByIsin("PLXTRDM00011")?.id)
        assertEquals(companyId, companies.findByName("XTB")?.id)
        assertNull(companies.findByIsin("NONE"))
        assertEquals("XTB", companies.findById(companyId).name)
    }

    @Test
    fun `existsBySourceRef matches market and source ref`() {
        reports.saveIfNew(report("1/2026"))

        assertTrue(reports.existsBySourceRef(Market.GPW, "ref-1/2026"))
        assertFalse(reports.existsBySourceRef(Market.GPW, "ref-2/2026"))
        assertFalse(reports.existsBySourceRef(Market.NC, "ref-1/2026"))
    }

    private fun report(number: String, kind: ReportKind = ReportKind.CURRENT, sourceRef: String = "ref-$number") = Report(
        null, companyId, Market.GPW, kind, number, sourceRef, "Raport", Instant.parse("2026-05-12T15:00:00Z"),
        Instant.parse("2026-05-12T15:01:00Z"), "https://example.test/r/$number", ReportStatus.NEW
    )
}
