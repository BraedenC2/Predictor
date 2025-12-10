package com.example.predictor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.predictor.logic.BayesianPredictor
import com.example.predictor.sensors.ActivitySensor
import com.example.predictor.sensors.UsageCollector
import com.example.predictor.ui.theme.PredictorTheme
import com.example.predictor.workers.DataLoggerWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        setContent { AppContent() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()

        val serviceIntent = Intent(this, com.example.predictor.services.PredictorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val workRequest = PeriodicWorkRequestBuilder<DataLoggerWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "PredictorDataLogger",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        val activitySensor = ActivitySensor(this)
        activitySensor.startMonitoring()

        setContent {
            AppContent()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Predictor Alerts"
            val importance = android.app.NotificationManager.IMPORTANCE_HIGH
            val channel = android.app.NotificationChannel("predictor_channel", name, importance)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
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
        var isTimeMachineEnabled by remember { mutableStateOf(false) }
        var timeMachineHour by remember { mutableFloatStateOf(12f) }

        val context = androidx.compose.ui.platform.LocalContext.current

        // --- TURBO MODE ---
        LaunchedEffect(Unit) {
            while (true) {
                val request = OneTimeWorkRequestBuilder<DataLoggerWorker>().build()
                WorkManager.getInstance(context).enqueue(request)
                delay(15_000)
            }
        }

        // --- PERMISSIONS CHECK ---
        LaunchedEffect(Unit) {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
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
            Text(
                text = if (isTimeMachineEnabled) "Time Machine" else "Predictor Active",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 1. OVERLAY PERMISSION FOR "MIND READER"
            if (!Settings.canDrawOverlays(context)) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("⚠️ Enable Mind Reader", style = MaterialTheme.typography.titleMedium)
                        Text("Grant 'Display over other apps' to allow Predictor to auto-open apps.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${packageName}"))
                            context.startActivity(intent)
                        }) {
                            Text("Grant Permission")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 2. TIME MACHINE UI
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Simulate Future", style = MaterialTheme.typography.titleMedium)
                        Switch(
                            checked = isTimeMachineEnabled,
                            onCheckedChange = { isTimeMachineEnabled = it }
                        )
                    }

                    if (isTimeMachineEnabled) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "Hour: ${timeMachineHour.roundToInt()}:00")
                        Slider(
                            value = timeMachineHour,
                            onValueChange = { timeMachineHour = it },
                            valueRange = 0f..23f,
                            steps = 22
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. ANALYZE BUTTON
            Button(
                onClick = {
                    scope.launch {
                        val headphones = checkHeadphones(this@MainActivity)
                        val location = getBestLocation(this@MainActivity)
                        val lat = location?.latitude ?: 0.0
                        val lon = location?.longitude ?: 0.0

                        val calendar = Calendar.getInstance()
                        val hour = if (isTimeMachineEnabled) {
                            timeMachineHour.roundToInt()
                        } else {
                            calendar.get(Calendar.HOUR_OF_DAY)
                        }

                        currentDetails = "Time: $hour:00\nHeadphones: $headphones"

                        val predictor = BayesianPredictor(this@MainActivity)
                        val result = predictor.predictTopApp("UNKNOWN", hour, headphones, lat, lon)
                        prediction = result.substringAfterLast(".")
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(if (isTimeMachineEnabled) "Predict Future" else "Analyze Now")
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(text = prediction, style = MaterialTheme.typography.displayMedium)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getBestLocation(context: Context): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = locationManager.getProviders(true)
        var bestLocation: Location? = null
        for (provider in providers) {
            val l = locationManager.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                bestLocation = l
            }
        }
        return bestLocation
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