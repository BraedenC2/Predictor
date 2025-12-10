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
import com.example.predictor.sensors.ActivitySensor
import com.example.predictor.sensors.UsageCollector
import com.example.predictor.ui.theme.PredictorTheme
import com.example.predictor.workers.DataLoggerWorker
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // When permissions are granted, refresh the UI
        setContent { AppContent() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Schedule Background Worker (Runs every 15 mins)
        val workRequest = PeriodicWorkRequestBuilder<DataLoggerWorker>(15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "PredictorDataLogger",
            ExistingPeriodicWorkPolicy.KEEP, // Don't replace if already running
            workRequest
        )

        // 2. Start Activity Sensor
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
                // Check if we have Usage Access (The special permission)
                val usageCollector = UsageCollector(this)
                val hasUsageAccess = usageCollector.isPermissionGranted()

                if (!hasUsageAccess) {
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
        // Request standard Android permissions (Location, Activity Recognition)
        LaunchedEffect(Unit) {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.POST_NOTIFICATIONS
            )
            // Activity Recognition needs a separate permission on API 29+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Predictor is Active", style = MaterialTheme.typography.headlineMedium)
            Text(text = "Collecting data in background...")
        }
    }
}