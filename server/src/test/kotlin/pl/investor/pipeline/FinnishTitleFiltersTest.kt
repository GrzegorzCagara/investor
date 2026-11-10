package pl.investor.pipeline

import org.junit.jupiter.api.Test
import pl.investor.report.Market
import pl.investor.source.nasdaq.NasdaqNewsParser
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinnishTitleFiltersTest {

    private val headlines = NasdaqNewsParser.parse(checkNotNull(javaClass.getResource("/fixtures/fi/nasdaq-helsinki.json")).readText())
        .associate { it.disclosureId to it.headline }

    private fun title(disclosureId: String) = checkNotNull(headlines[disclosureId]) { "no headline $disclosureId" }

    private val resultsTitles = listOf(
        title("1475515"),
        title("1475518"),
        title("1475273"),
        title("1475271"),
        title("1474694"),
        "Inside information, profit warning: Solwers issues new outlook for 2026",
        "Sisäpiiritieto, positiivinen tulosvaroitus: Bittium Oyj nostaa tulosohjaustaan",
        "Sisäpiiritieto: Tieto päivittää vuoden 2026 orgaanisen liikevaihdon kasvun sekä kannattavuuden ohjeistustaan ja julkistaa ennakkotietoja kolmannen vuosineljänneksen tuloksestaan",
        "Inside information: Wetteri revises its outlook for 2026",
        "Sisäpiiritieto: Yhtiö julkistaa alustavia tietoja liiketoimintakatsauksesta",
    )

    private val newsTitles = listOf(
        title("1476055"),
        title("1476054"),
        title("1475206"),
        title("1475205"),
        title("1474745"),
        title("1475343"),
        "Inside information: Modulight receives USD 1.1 million order",
        "Sisäpiiritieto: Kempower aloittaa muutosneuvottelut Suomessa",
        "Sisäpiiritieto: Yhtiö allekirjoitti alustava sopimus yrityskaupasta",
        "Sisäpiiritieto: Yhtiö on solminut alustavan sopimuksen tehtaan myynnistä",
        "Sisäpiiritieto: Lopullinen tulos vapaaehtoisesta julkisesta ostotarjouksesta",
    )

    @Test
    fun `Finnish profit warnings and preliminary results go to the results extraction`() {
        resultsTitles.forEach { assertTrue(ResultsKeywordFilter.matches(it, Market.FI), it) }
    }

    @Test
    fun `arbitration, rights issue, change negotiations, strategy and orders are not results and not routine`() {
        newsTitles.forEach {
            assertFalse(ResultsKeywordFilter.matches(it, Market.FI), it)
            assertFalse(RoutineNewsFilter.isRoutine(it, Market.FI), it)
        }
    }

    @Test
    fun `Finnish buyback progress, flagging notices and managers' transactions are routine`() {
        listOf(
            "Raisio Oyj: Omien osakkeiden hankinta 6.11.2026",
            "Nokia Oyj: Omien osakkeiden takaisinosto 7.11.2026",
            "Liputusilmoitus: Varman omistusosuus ylitti 5 prosenttia",
            "Kempower Oyj: Johtohenkilöiden liiketoimet",
            "Kempower Oyj: Managers' Transactions",
        ).forEach { assertTrue(RoutineNewsFilter.isRoutine(it, Market.FI), it) }
        listOf(
            "Sisäpiiritieto: Hallitus päätti käynnistää omien osakkeiden hankinnan",
            "Sisäpiiritieto: Kesko Oyj:n hallitus on päättänyt merkintäoikeusannista",
        ).forEach { assertFalse(RoutineNewsFilter.isRoutine(it, Market.FI), it) }
    }

    @Test
    fun `Finnish keywords and routine phrases do not leak into other markets`() {
        listOf(title("1475518"), "Sisäpiiritieto: Yhtiö julkistaa alustavia tietoja", "Wärtsilä moves to a new guidance practice in 2027").forEach {
            assertFalse(ResultsKeywordFilter.matches(it, Market.SE), it)
            assertFalse(ResultsKeywordFilter.matches(it, Market.GPW), it)
        }
        assertFalse(RoutineNewsFilter.isRoutine("Liputusilmoitus: Varman omistusosuus ylitti 5 prosenttia", Market.SE))
    }
}
