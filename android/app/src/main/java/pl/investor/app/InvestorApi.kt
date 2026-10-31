package pl.investor.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit

interface InvestorApi {

    @GET("api/alerts")
    suspend fun alerts(@Query("limit") limit: Int = ALERTS_LIMIT): AlertsResponse

    @GET("api/alerts/{id}")
    suspend fun alert(@Path("id") id: Long): AlertDetail

    @POST("api/devices")
    suspend fun registerDevice(@Body request: DeviceRequest)

    @GET("api/ranking")
    suspend fun ranking(@Query("market") market: Market? = null): RankingResponse

    @GET("api/effectiveness")
    suspend fun effectiveness(): EffectivenessResponse

    @GET("api/settings")
    suspend fun settings(): SettingsView

    @PUT("api/settings")
    suspend fun updateSettings(@Body settings: SettingsView): SettingsView

    @GET("api/status")
    suspend fun status(): StatusResponse

    companion object {
        const val ALERTS_LIMIT = 100
        const val TOKEN_HEADER = "X-Investor-Token"
        private val PLACEHOLDER_URL = "http://localhost/".toHttpUrl()

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun create(baseUrl: HttpUrl, client: OkHttpClient): InvestorApi =
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(InvestorApi::class.java)

        fun create(settings: ConnectionSettings): InvestorApi {
            val client = OkHttpClient.Builder()
                .addInterceptor(ConnectionInterceptor(settings))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            return create(PLACEHOLDER_URL, client)
        }
    }
}

class ConnectionInterceptor(private val settings: ConnectionSettings) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val connection = runBlocking { settings.current() } ?: throw IOException("Brak konfiguracji połączenia")
        val server = try {
            connection.serverUrl.toHttpUrl()
        } catch (e: IllegalArgumentException) {
            throw IOException("Nieprawidłowy adres serwera", e)
        }
        val url = chain.request().url.newBuilder()
            .scheme(server.scheme)
            .host(server.host)
            .port(server.port)
            .build()
        val request = chain.request().newBuilder()
            .url(url)
            .header(InvestorApi.TOKEN_HEADER, connection.apiToken)
            .build()
        return chain.proceed(request)
    }
}
