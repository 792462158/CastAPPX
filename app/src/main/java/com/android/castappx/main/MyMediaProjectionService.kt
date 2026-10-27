package com.android.castappx.main

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.castappx.MainActivity
import com.android.castappx.R

class MyMediaProjectionService : Service() {
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val _active = MutableLiveData(false)
    val active: LiveData<Boolean> = _active
    private val _captureSize = MutableLiveData<Size>()
    val captureSize: LiveData<Size> = _captureSize
    private val _error = MutableLiveData(false)
    val error: LiveData<Boolean> = _error
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var outputSurface: Surface? = null

    inner class LocalBinder : Binder() {
        val service: MyMediaProjectionService get() = this@MyMediaProjectionService
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = stopCapture()

        override fun onCapturedContentResize(width: Int, height: Int) {
            resizeCapture(Size(width, height))
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || projection != null) return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val consent = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
        } else {
            intent.getParcelableExtra<Intent>(EXTRA_CONSENT)
        }
        if (consent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            _error.value = false
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(Activity.RESULT_OK, consent)
            projection!!.registerCallback(projectionCallback, handler)
            _captureSize.value = screenSize()
            // Create once per consent token; view recreation only replaces the surface.
            val size = _captureSize.value!!
            display = projection!!.createVirtualDisplay(
                "CastAPPX screen preview", size.width, size.height,
                resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                outputSurface, null, handler
            )
            _active.value = true
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to start screen capture", error)
            _error.value = true
            stopCapture()
        }
        return START_NOT_STICKY
    }

    fun setSurface(surface: Surface?) {
        outputSurface = surface
        display?.surface = surface
    }

    fun stopCapture() {
        display?.release()
        display = null
        projection?.unregisterCallback(projectionCallback)
        val previousProjection = projection
        projection = null
        previousProjection?.stop()
        _active.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < 34 && projection != null) resizeCapture(screenSize())
    }

    private fun resizeCapture(size: Size) {
        if (size.width <= 0 || size.height <= 0 || _captureSize.value == size) return
        _captureSize.value = size
        display?.resize(size.width, size.height, resources.configuration.densityDpi)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Size {
        val manager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            manager.maximumWindowMetrics.bounds.let { Size(it.width(), it.height()) }
        } else {
            val metrics = android.util.DisplayMetrics()
            manager.defaultDisplay.getRealMetrics(metrics)
            Size(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.projection_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, javaClass).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_record)
            .setContentTitle(getString(R.string.projection_notification_title))
            .setContentText(getString(R.string.projection_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop_projection), stop)
            .build()
    }

    override fun onDestroy() {
        stopCapture()
        outputSurface = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.android.castappx.START_PROJECTION"
        const val ACTION_STOP = "com.android.castappx.STOP_PROJECTION"
        const val EXTRA_CONSENT = "projection_consent"
        private const val CHANNEL_ID = "screen_projection"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "MediaProjectionService"
    }
}
