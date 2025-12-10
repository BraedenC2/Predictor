package com.example.predictor

import android.annotation.SuppressLint
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

class PredictorWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
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
                // REMOVED: wifi fetching
                val headphones = checkHeadphones(context)

                val location = getBestLocation(context)
                val lat = location?.latitude ?: 0.0
                val lon = location?.longitude ?: 0.0

                // 2. ASK THE BRAIN
                val predictor = BayesianPredictor(context)
                // REMOVED: wifi passed to predictTopApp
                val predictedPkg = predictor.predictTopApp(activity, hour, headphones, lat, lon)

                // 3. PREPARE THE VIEW
                val views = RemoteViews(context.packageName, R.layout.widget_predictor_layout)

                // 4. CHANGE THE ICON & CLICK ACTION
                if (predictedPkg != "No Prediction") {
                    try {
                        val packageManager = context.packageManager
                        val launchIntent = packageManager.getLaunchIntentForPackage(predictedPkg)

                        if (launchIntent != null) {
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
                            views.setImageViewResource(R.id.widget_icon, android.R.drawable.sym_def_app_icon)
                            views.setTextViewText(R.id.widget_text, "Learning...")
                            views.setOnClickPendingIntent(R.id.widget_icon, null)
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                        views.setTextViewText(R.id.widget_text, "Err: ${e.javaClass.simpleName}")
                    }
                } else {
                    views.setImageViewResource(R.id.widget_icon, android.R.drawable.sym_def_app_icon)
                    views.setTextViewText(R.id.widget_text, "Learning...")
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        @SuppressLint("MissingPermission")
        private fun getBestLocation(context: Context): android.location.Location? {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = locationManager.getProviders(true)
            var bestLocation: android.location.Location? = null
            for (provider in providers) {
                val l = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                    bestLocation = l
                }
            }
            return bestLocation
        }

        private fun drawableToBitmap(drawable: Drawable): Bitmap {
            if (drawable is BitmapDrawable) return drawable.bitmap
            val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }

        // REMOVED: getWifiSsid helper

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