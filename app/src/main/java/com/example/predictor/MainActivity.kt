package com.example.predictor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
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

        // 1. Start the Always-On Service (NEW)
        val serviceIntent = android.content.Intent(this, com.example.predictor.services.PredictorService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // 1. Schedule Background Worker
        val workRequest = PeriodicWorkRequestBuilder<DataLoggerWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "PredictorDataLogger",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        // 2. Start Sensors (ActivitySensor is mostly disabled now, but we keep it running to not break flows)
        val activitySensor = ActivitySensor(this)
        activitySensor.startMonitoring()

        setContent {
            AppContent()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Predictor Alerts"
            val descriptionText = "Smart predictions about your day"
            val importance = android.app.NotificationManager.IMPORTANCE_HIGH
            val channel = android.app.NotificationChannel("predictor_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: android.app.NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
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

        // Time Machine State
        var isTimeMachineEnabled by remember { mutableStateOf(false) }
        var timeMachineHour by remember { mutableFloatStateOf(12f) }

        // --- TURBO MODE ---
        val context = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(Unit) {
            while (true) {
                val request = OneTimeWorkRequestBuilder<DataLoggerWorker>().build()
                WorkManager.getInstance(context).enqueue(request)
                delay(15_000)
            }
        }

        // Permissions Check
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
            Text(
                text = if (isTimeMachineEnabled) "Time Machine Mode" else "Predictor: TURBO MODE",
                style = MaterialTheme.typography.headlineMedium,
                color = if (isTimeMachineEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(32.dp))

            // TIME MACHINE CARD
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Enable Time Travel", style = MaterialTheme.typography.titleMedium)
                        Switch(
                            checked = isTimeMachineEnabled,
                            onCheckedChange = { isTimeMachineEnabled = it }
                        )
                    }

                    if (isTimeMachineEnabled) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "Simulated Hour: ${timeMachineHour.roundToInt()}:00")
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

            // NEW: Sleep Reset Button
            Button(
                onClick = {
                    val prefs = getSharedPreferences("PredictorSleep", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putLong("sleep_bank_minutes", 0)
                        .putLong("sleep_start_timestamp", 0)
                        .putBoolean("require_resume_delay", false)
                        .apply()

                    android.widget.Toast.makeText(this@MainActivity, "Sleep Bank Reset to 0m", android.widget.Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text("Manual Sleep Reset")
            }

            Button(
                onClick = {
                    scope.launch {
                        // 1. Gather Context
                        val headphones = checkHeadphones(this@MainActivity)

                        // NEW: Get Location
                        val location = getBestLocation(this@MainActivity)
                        val lat = location?.latitude ?: 0.0
                        val lon = location?.longitude ?: 0.0

                        // Logic: Real Time OR Fake Time?
                        val calendar = Calendar.getInstance()
                        val hour = if (isTimeMachineEnabled) {
                            timeMachineHour.roundToInt()
                        } else {
                            calendar.get(Calendar.HOUR_OF_DAY)
                        }

                        // Updated Display: Shows GPS instead of Activity/Wifi
                        currentDetails = "Time: $hour:00\nHeadphones: $headphones\nGPS: ${"%.3f".format(lat)}, ${"%.3f".format(lon)}"

                        // 2. Ask the Brain (Passing "UNKNOWN"/"None" for the disabled fields)
                        val predictor = BayesianPredictor(this@MainActivity)
                        val result = predictor.predictTopApp(
                            currentActivity = "UNKNOWN",
                            currentHour = hour,
                            isHeadphones = headphones,
                            currentWifi = "None",
                            currentLat = lat,
                            currentLong = lon
                        )

                        prediction = result.substringAfterLast(".")
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(if (isTimeMachineEnabled) "Predict Future" else "Analyze Now")
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(text = "I think you want:", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = prediction,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary
            )

            if (isTimeMachineEnabled) {
                Text(text = "(Based on historical data for ${timeMachineHour.roundToInt()}:00)", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    // --- Helper Functions ---

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