package pl.investor.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.tasks.await
import java.time.Clock

class AppContainer(application: Application) {
    val settings = ConnectionSettings(application)
    val api: InvestorApi = InvestorApi.create(settings)
    val clock: Clock = Clock.systemUTC()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val deviceRegistration = DeviceRegistration(api, settings) { FirebaseMessaging.getInstance().token.await() }
}

class InvestorApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(NotificationChannels.ALERTS, getString(R.string.alerts_channel_name), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(NotificationChannels.QUIET, getString(R.string.quiet_alerts_channel_name), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }
}
