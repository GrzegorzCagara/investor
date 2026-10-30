package pl.investor.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeviceRegistrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: ConnectionSettings
    private lateinit var registration: DeviceRegistration
    private var fcmToken = "token-1"
    private var tokenFailure: Exception? = null

    @Before
    fun setUp() {
        server.start()
        settings = ConnectionSettings(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "c.preferences_pb") })
        registration = DeviceRegistration(InvestorApi.create(server.url("/"), OkHttpClient()), settings) { tokenFailure?.let { throw it } ?: fcmToken }
    }

    @After
    fun tearDown() {
        server.shutdown()
        scope.cancel()
    }

    @Test
    fun `failure leaves registered token unchanged`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        assertFalse(registration.register("token-1"))

        assertNull(settings.registeredToken())
    }

    @Test
    fun `success stores the registered token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        assertTrue(registration.register("token-1"))

        assertEquals("token-1", settings.registeredToken())
    }

    @Test
    fun `identical token is not sent again and a new token is`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        registration.syncCurrentToken()
        registration.syncCurrentToken()
        assertEquals(1, server.requestCount)

        fcmToken = "token-2"
        registration.syncCurrentToken()
        assertEquals(2, server.requestCount)
        assertEquals("token-2", settings.registeredToken())
    }

    @Test
    fun `misconfigured Firebase keeps the stored token and sends nothing`() = runBlocking {
        settings.saveRegisteredToken("token-0")
        tokenFailure = IllegalStateException("Default FirebaseApp is not initialized")

        registration.syncCurrentToken()

        assertEquals(0, server.requestCount)
        assertEquals("token-0", settings.registeredToken())
    }
}
