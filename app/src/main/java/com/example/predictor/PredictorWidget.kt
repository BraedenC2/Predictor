package com.example.predictor

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.RemoteViews
import com.example.predictor.logic.BayesianPredictor
import com.example.predictor.sensors.ActivityTransitionReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
// Imports for WiFi/Headphones context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo

class PredictorWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Perform this loop procedure for each App Widget that belongs to this provider
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                // 1. GATHER CONTEXT
                val calendar = Calendar.getInstance()
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val activity = ActivityTransitionReceiver.currentActivity
                val wifi = getWifiSsid(context)
                val headphones = checkHeadphones(context)

                // 2. ASK THE BRAIN
                val predictor = BayesianPredictor(context)
                val predictedPkg = predictor.predictTopApp(activity, hour, headphones, wifi)

                // 3. PREPARE THE VIEW
                val views = RemoteViews(context.packageName, R.layout.widget_predictor_layout)

                // 4. CHANGE THE ICON & CLICK ACTION
                if (predictedPkg != "No Prediction") {
                    try {
                        val packageManager = context.packageManager

                        // SAFETY CHECK 1: Can we actually launch this app?
                        val launchIntent = packageManager.getLaunchIntentForPackage(predictedPkg)

                        if (launchIntent != null) {
                            // Yes, it's a real app (like Spotify). Show it.
                            val appIcon = packageManager.getApplicationIcon(predictedPkg)
                            val appLabel = packageManager.getApplicationLabel(
                                packageManager.getApplicationInfo(predictedPkg, 0)
                            )

                            views.setImageViewBitmap(R.id.widget_icon, drawableToBitmap(appIcon))
                            views.setTextViewText(R.id.widget_text, appLabel)

                            val pendingIntent = PendingIntent.getActivity(
                                context,
                                0,
                                launchIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            views.setOnClickPendingIntent(R.id.widget_icon, pendingIntent)
                        } else {
                            // No, it's a background process (like 'com.android.systemui').
                            // Treat it as "Learning..." instead of crashing.
                            views.setImageViewResource(R.id.widget_icon, android.R.drawable.sym_def_app_icon)
                            views.setTextViewText(R.id.widget_text, "Learning...")
                            // Remove click action
                            views.setOnClickPendingIntent(R.id.widget_icon, null)
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                        // SAFETY CHECK 2: Show the specific error so we know what to fix
                        views.setTextViewText(R.id.widget_text, "Err: ${e.javaClass.simpleName}")
                    }
                } else {
                    views.setImageViewResource(R.id.widget_icon, android.R.drawable.sym_def_app_icon)
                    views.setTextViewText(R.id.widget_text, "Learning...")
                }

                // 5. UPDATE THE WIDGET
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        // --- Helpers ---
        private fun drawableToBitmap(drawable: Drawable): Bitmap {
            if (drawable is BitmapDrawable) return drawable.bitmap
            val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }

        // (We duplicate these small helpers here to keep the Widget standalone)
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
}