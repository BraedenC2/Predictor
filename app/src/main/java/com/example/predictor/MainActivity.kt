package com.example.predictor

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.predictor.logic.BayesianPredictor
import com.example.predictor.sensors.ActivitySensor
import com.example.predictor.sensors.ActivityTransitionReceiver
import com.example.predictor.sensors.UsageCollector
import com.example.predictor.ui.theme.PredictorTheme
import com.example.predictor.workers.DataLoggerWorker
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit
// Import for the WiFi check logic (we'll duplicate the worker logic briefly for the UI)
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        setContent { AppContent() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Schedule Background Worker
        val workRequest = PeriodicWorkRequestBuilder<DataLoggerWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "PredictorDataLogger",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        // 2. Start Sensors
        val activitySensor = ActivitySensor(this)
        activitySensor.startMonitoring()

        setContent {
            AppContent()
        }
    }

    @Composable
    fun AppContent() {
        PredictorTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val usageCollector = UsageCollector(this)
                if (!usageCollector.isPermissionGranted()) {
                    PermissionScreen("Usage Access") { usageCollector.requestPermission() }
                } else {
                    DashboardScreen()
                }
            }
        }
    }

    @Composable
    fun PermissionScreen(permissionName: String, onButtonClick: () -> Unit) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Predictor needs $permissionName")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onButtonClick) {
                Text("Grant Permission")
            }
        }
    }

    @Composable
    fun DashboardScreen() {
        val scope = rememberCoroutineScope()
        var prediction by remember { mutableStateOf("Tap to Predict") }
        var currentDetails by remember { mutableStateOf("Waiting...") }

        // Request Permissions on load
        LaunchedEffect(Unit) {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Predictor Brain", style = MaterialTheme.typography.headlineLarge)

            Spacer(modifier = Modifier.height(32.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Current Context:", style = MaterialTheme.typography.titleMedium)
                    Text(text = currentDetails)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    scope.launch {
                        // 1. Gather Real-time Context
                        val calendar = Calendar.getInstance()
                        val hour = calendar.get(Calendar.HOUR_OF_DAY)
                        val activity = ActivityTransitionReceiver.currentActivity
                        val wifi = getWifiSsid(this@MainActivity)
                        val headphones = checkHeadphones(this@MainActivity)

                        currentDetails = "Activity: $activity\nTime: $hour:00\nWiFi: $wifi\nHeadphones: $headphones"

                        // 2. Ask the Brain
                        val predictor = BayesianPredictor(this@MainActivity)
                        val result = predictor.predictTopApp(activity, hour, headphones, wifi)

                        // 3. Show Result
                        // (Cleanup the package name to make it readable)
                        prediction = result.substringAfterLast(".")
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Analyze & Predict")
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(text = "I think you want:", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = prediction,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    // --- Helper Functions for UI ---

    private fun getWifiSsid(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return "None"
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return "None"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val info = caps.transportInfo as? WifiInfo
            return info?.ssid?.replace("\"", "") ?: "None"
        }
        return "None"
    }

    private fun checkHeadphones(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val devices = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    }
}