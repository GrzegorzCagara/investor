package pl.investor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var pendingAlertId by mutableStateOf<Long?>(null)

    private val container get() = (application as InvestorApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAlertId = intent.alertId()
        setContent {
            MaterialTheme {
                NotificationPermissionRequest()
                InvestorApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingAlertId = intent.alertId()
    }

    @Composable
    private fun NotificationPermissionRequest() {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @Composable
    private fun InvestorApp() {
        val configured by produceState<Boolean?>(initialValue = null) {
            container.settings.connection.collect { value = it != null }
        }
        val scope = rememberCoroutineScope()

        when (configured) {
            null -> Unit
            false -> SetupScreen(onSave = { url, token ->
                scope.launch { container.settings.save(url, token) }
            })
            true -> {
                LaunchedEffect(Unit) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        container.deviceRegistration.syncCurrentToken()
                    }
                }
                InvestorNavigation()
            }
        }
    }

    @Composable
    private fun InvestorNavigation() {
        val navController = rememberNavController()

        LaunchedEffect(pendingAlertId) {
            pendingAlertId?.let { id ->
                navController.navigate("$DETAIL_ROUTE/$id")
                pendingAlertId = null
                intent.removeExtra(EXTRA_ALERT_ID)
            }
        }

        NavHost(navController = navController, startDestination = ALERTS_ROUTE) {
            composable(ALERTS_ROUTE) {
                val alertsViewModel = viewModel<AlertsViewModel>(
                    factory = viewModelFactory { initializer { AlertsViewModel(container.api, container.clock) } },
                )
                AlertsScreen(
                    viewModel = alertsViewModel,
                    now = container.clock::instant,
                    onOpen = { navController.navigate("$DETAIL_ROUTE/$it") },
                )
            }
            composable(
                route = "$DETAIL_ROUTE/{$ID_ARGUMENT}",
                arguments = listOf(navArgument(ID_ARGUMENT) { type = NavType.LongType }),
            ) { entry ->
                val alertId = entry.arguments?.getLong(ID_ARGUMENT) ?: error("Brak identyfikatora alertu")
                val detailViewModel = viewModel<DetailViewModel>(
                    factory = viewModelFactory { initializer { DetailViewModel(container.api, alertId) } },
                )
                DetailScreen(
                    viewModel = detailViewModel,
                    now = container.clock::instant,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }

    private fun Intent.alertId(): Long? = getStringExtra(EXTRA_ALERT_ID)?.toLongOrNull()

    companion object {
        const val EXTRA_ALERT_ID = "alertId"
        private const val ALERTS_ROUTE = "alerts"
        private const val DETAIL_ROUTE = "detail"
        private const val ID_ARGUMENT = "id"
    }
}
