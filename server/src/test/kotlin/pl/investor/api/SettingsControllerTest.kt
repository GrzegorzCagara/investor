package pl.investor.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import pl.investor.support.PostgresTestBase
import kotlin.test.Test

class SettingsControllerTest : PostgresTestBase() {

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var tokenFilter: ApiTokenFilter
    @Autowired lateinit var jdbc: JdbcClient

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters<DefaultMockMvcBuilder>(tokenFilter).build()
        resetSettings()
    }

    @AfterEach
    fun tearDown() = resetSettings()

    private fun resetSettings() {
        jdbc.sql("DELETE FROM setting WHERE key IN ('DISABLED_MARKETS', 'DISABLED_EVENT_TYPES') OR key LIKE 'QUIET_HOURS_%'").update()
        jdbc.sql("UPDATE setting SET value = '70' WHERE key = 'PUSH_THRESHOLD'").update()
    }

    private fun getSettings(): ResultActions = mvc.perform(get("/api/settings").header(TOKEN_HEADER, TOKEN))

    private fun putSettings(body: String): ResultActions =
        mvc.perform(put("/api/settings").header(TOKEN_HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun body(threshold: Any = 55, markets: String = """["NC"]""", eventTypes: String = """["RESULTS"]""", from: String = "23:00", to: String = "06:30") =
        """{"pushThreshold":$threshold,"disabledMarkets":$markets,"disabledEventTypes":$eventTypes,"quietHours":{"enabled":true,"from":"$from","to":"$to"}}"""

    private fun ResultActions.expectDefaults(): ResultActions =
        andExpect(status().isOk)
            .andExpect(jsonPath("\$.pushThreshold").value(70))
            .andExpect(jsonPath("\$.disabledMarkets.length()").value(0))
            .andExpect(jsonPath("\$.disabledEventTypes.length()").value(0))
            .andExpect(jsonPath("\$.quietHours.enabled").value(false))
            .andExpect(jsonPath("\$.quietHours.from").value("22:00"))
            .andExpect(jsonPath("\$.quietHours.to").value("07:00"))

    private fun ResultActions.expectSaved(): ResultActions =
        andExpect(status().isOk)
            .andExpect(jsonPath("\$.pushThreshold").value(55))
            .andExpect(jsonPath("\$.disabledMarkets.length()").value(1))
            .andExpect(jsonPath("\$.disabledMarkets[0]").value("NC"))
            .andExpect(jsonPath("\$.disabledEventTypes[0]").value("RESULTS"))
            .andExpect(jsonPath("\$.quietHours.enabled").value(true))
            .andExpect(jsonPath("\$.quietHours.from").value("23:00"))
            .andExpect(jsonPath("\$.quietHours.to").value("06:30"))

    @Test
    fun `get gives the defaults`() {
        getSettings().expectDefaults()
    }

    @Test
    fun `put returns the saved settings and get reads them back`() {
        putSettings(body()).expectSaved()

        getSettings().expectSaved()
    }

    @Test
    fun `invalid settings give 400 and change nothing`() {
        listOf(
            body(threshold = 101),
            body(threshold = -1),
            body(from = "25:00"),
            body(to = "7:00"),
            body(to = "07:00:00"),
            body(markets = """["XETRA"]"""),
            body(eventTypes = """["CONTRACTS"]"""),
            """{"pushThreshold":55}""",
            body(threshold = 70.5),
            body(threshold = 70.9),
            body(threshold = "null"),
            body(markets = "[null]"),
            body(eventTypes = """["RESULTS",null]"""),
            """{"disabledMarkets":[],"disabledEventTypes":[],"quietHours":{"enabled":true,"from":"23:00","to":"06:30"}}""",
            """{"pushThreshold":55,"disabledMarkets":[],"disabledEventTypes":[],"quietHours":{"from":"23:00","to":"06:30"}}""",
        ).forEach { invalid ->
            putSettings(invalid).andExpect(status().isBadRequest)
            getSettings().expectDefaults()
        }
    }

    @Test
    fun `null list element gives 400 and keeps the threshold`() {
        putSettings("""{"pushThreshold":50,"disabledMarkets":[null],"disabledEventTypes":[],"quietHours":{"enabled":true,"from":"23:00","to":"06:30"}}""")
            .andExpect(status().isBadRequest)

        getSettings().expectDefaults()
    }

    @Test
    fun `whole number written as a decimal is accepted`() {
        putSettings(body(threshold = "55.0")).expectSaved()
    }

    @Test
    fun `missing token gives 401`() {
        mvc.perform(get("/api/settings")).andExpect(status().isUnauthorized)
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isUnauthorized)
        getSettings().expectDefaults()
    }

    private companion object {
        const val TOKEN_HEADER = "X-Investor-Token"
        const val TOKEN = "test-token"
    }
}
