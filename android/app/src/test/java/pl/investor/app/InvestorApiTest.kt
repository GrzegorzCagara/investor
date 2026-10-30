package pl.investor.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InvestorApiTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        scope.cancel()
    }

    @Test
    fun `requests go to the configured server with the token header`() = runBlocking {
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "connection.preferences_pb") }
        val settings = ConnectionSettings(store)
        settings.save(server.url("/").toString(), " secret ")
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":[]}"""))
        server.enqueue(MockResponse().setResponseCode(204))

        val api = InvestorApi.create(settings)
        api.alerts()
        api.registerDevice(DeviceRequest("fcm-token"))

        val alertsRequest = server.takeRequest()
        assertEquals("/api/alerts?limit=100", alertsRequest.path)
        assertEquals("secret", alertsRequest.getHeader(InvestorApi.TOKEN_HEADER))
        val deviceRequest = server.takeRequest()
        assertEquals("POST", deviceRequest.method)
        assertEquals("""{"token":"fcm-token"}""", deviceRequest.body.readUtf8())
    }
}
