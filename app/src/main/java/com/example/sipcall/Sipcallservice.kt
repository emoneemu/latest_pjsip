package com.example.sipcall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service of type `phoneCall`.
 *
 * WHY THIS EXISTS:
 * On Samsung devices (One UI 7+, Z Fold 7 / S24+ / etc.), background apps
 * are kernel-sandboxed and outbound UDP from their sockets returns EPERM
 * ("Operation not permitted"). This is Samsung's Knox network policy filter
 * blocking what it thinks are spam/battery-draining background sockets.
 *
 * The fix is to run PJSIP from a service typed
 * `foregroundServiceType="phoneCall"`. Samsung's Knox layer recognizes this
 * as a legitimate VoIP app and lifts the UDP block. Other Android 16
 * devices (Pixel, Motorola) don't enforce this at all — which is why the
 * same code works on those without a service.
 *
 * ARCHITECTURE:
 * This service OWNS the PjsipManager instance. The Activity is UI only and
 * talks to PJSIP through SipCallService.pjsipInstance. When the user
 * backgrounds the Activity, PJSIP keeps running here so calls survive.
 *
 * The service also holds CPU + WiFi WakeLocks for the duration of its life
 * to prevent Doze and Wi-Fi power-save from dropping the SIP socket.
 */
class SipCallService : Service() {

    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiWakeLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Enter foreground FIRST. This must happen before any socket is
        //    created, otherwise Samsung Knox tags the socket as background.
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2. Acquire wake locks so the OS doesn't suspend the network stack.
        acquireWakeLocks()

        // 3. NOW initialize PJSIP. The UDP socket is created under the
        //    phoneCall foreground service umbrella → Samsung allows it.
        if (pjsipInstance == null) {
            val pj = PjsipManager(ServiceListener)
            pj.start()
            pjsipInstance = pj
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            pjsipInstance?.shutdown()
        } catch (_: Exception) {}
        pjsipInstance = null
        releaseWakeLocks()
    }

    private fun acquireWakeLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            cpuWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SipCall::CpuLock"
            ).apply {
                setReferenceCounted(false)
                acquire(8 * 60 * 60 * 1000L) // 8 hour safety ceiling
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiWakeLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "SipCall::WifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            // Non-fatal: app still works, just less reliable in deep sleep.
        }
    }

    private fun releaseWakeLocks() {
        try {
            cpuWakeLock?.takeIf { it.isHeld }?.release()
            wifiWakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {}
        cpuWakeLock = null
        wifiWakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SIP Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the SIP client running for incoming and outgoing calls"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SIP Call")
            .setContentText("Ready to make and receive calls")
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sip_call_channel"
        private const val NOTIFICATION_ID = 1001

        /**
         * The single PJSIP instance, owned by the service.
         * Activity reads this to register/call/hangup.
         */
        @Volatile
        var pjsipInstance: PjsipManager? = null
            private set

        /**
         * UI listener forwarder. The Activity registers itself here so it
         * gets log/state callbacks, but the service is the actual owner.
         */
        @Volatile
        var uiListener: PjsipManager.Listener? = null

        fun start(context: Context) {
            val intent = Intent(context, SipCallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SipCallService::class.java))
        }
    }

    /**
     * Forwards every PJSIP callback to the Activity if one is registered.
     * If the user has backgrounded the app, callbacks just go to logcat
     * and the call still works — that's the whole point of running here.
     */
    private object ServiceListener : PjsipManager.Listener {
        override fun onRegState(code: Int, reason: String, registered: Boolean) {
            uiListener?.onRegState(code, reason, registered)
        }
        override fun onCallState(state: String, lastStatusCode: Int, lastReason: String) {
            uiListener?.onCallState(state, lastStatusCode, lastReason)
        }
        override fun onLog(line: String) {
            uiListener?.onLog(line)
        }
        override fun onIncomingCall(remoteUri: String) {
            uiListener?.onIncomingCall(remoteUri)
        }
    }
}