package pl.investor.report

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanyNamesTest {

    @Test
    fun `names match across Swedish, Polish and other legal forms`() {
        assertTrue(CompanyNames.sameCompany("KRUK S.A.", "KRUK SPÓŁKA AKCYJNA"))
        assertTrue(CompanyNames.sameCompany("KRUK S.A.", "KRUK SA"))
        assertTrue(CompanyNames.sameCompany("KRUK S.A.", "KRUK"))
        assertTrue(CompanyNames.sameCompany("Arctic Paper S.A.", "ARCTIC PAPER SPÓŁKA AKCYJNA"))
        assertTrue(CompanyNames.sameCompany("Kvika banki hf.", "KVIKA BANKI"))
        assertTrue(CompanyNames.sameCompany("Novaturas AB", "AB NOVATURAS"))
        assertTrue(CompanyNames.sameCompany("Primrock Power AB (publ)", "PRIMROCK POWER"))
        assertTrue(CompanyNames.sameCompany("Asbisc Enterprises plc", "ASBISC ENTERPRISES PLC"))
        assertTrue(CompanyNames.sameCompany("Creditas Financial Solutions LTD", "Creditas Financial Solutions Ltd."))
        assertTrue(CompanyNames.sameCompany("Sopharma AD", "SOPHARMA"))
        assertTrue(CompanyNames.sameCompany("Equinor ASA", "EQUINOR"))
        assertTrue(CompanyNames.sameCompany("Nokia Oyj", "NOKIA"))
        assertTrue(CompanyNames.sameCompany("Pepco Group N.V.", "PEPCO GROUP"))
        assertTrue(CompanyNames.sameCompany("Lyckegård AB", "LYCKEGARD"))
    }

    @Test
    fun `different companies and empty names never match`() {
        assertFalse(CompanyNames.sameCompany("KRUK S.A.", "KRUKS SPÓŁKA AKCYJNA"))
        assertFalse(CompanyNames.sameCompany("mBank S.A.", "MBANK HIPOTECZNY SPÓŁKA AKCYJNA"))
        assertFalse(CompanyNames.sameCompany("AB", "SA"))
    }
}
