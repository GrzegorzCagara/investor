package pl.investor.app

import android.util.Log
import retrofit2.HttpException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class DeviceRegistration(
    private val api: InvestorApi,
    private val settings: ConnectionSettings,
    private val currentToken: suspend () -> String,
) {

    suspend fun register(token: String): Boolean =
        try {
            api.registerDevice(DeviceRequest(token))
            settings.saveRegisteredToken(token)
            true
        } catch (e: IOException) {
            Log.w(TAG, "Device registration failed", e)
            false
        } catch (e: HttpException) {
            Log.w(TAG, "Device registration rejected: ${e.code()}", e)
            false
        }

    suspend fun syncCurrentToken() {
        val token = try {
            currentToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not obtain FCM token", e)
            return
        }
        if (token != settings.registeredToken()) register(token)
    }

    private companion object {
        const val TAG = "DeviceRegistration"
    }
}
