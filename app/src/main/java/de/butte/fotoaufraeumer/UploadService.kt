package de.butte.fotoaufraeumer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Läuft unabhängig vom Bildschirm – Hochladen geht weiter, auch wenn die App verlassen wird. */
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** Ist die App gerade sichtbar? (für Android-Rückfragen, die nur im Vordergrund gehen) */
object AppState {
    @Volatile var foreground = false
}

/**
 * Vordergrund-Dienst während des Hochladens. Android friert die App sonst im Hintergrund ein
 * oder beendet sie. Zeigt den Fortschritt als Benachrichtigung.
 */
class UploadService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, intent?.getStringExtra("text") ?: "Lade hoch …", 0, 0, ongoing = true)
        startForeground(ID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fotoaufraeumer:upload")
                .apply { acquire(3 * 60 * 60 * 1000L) }
        }
        if (wifi == null) {
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fotoaufraeumer:upload")
                .apply { setReferenceCounted(false); acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifi?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val CH = "upload"
        private const val ID_RUN = 41
        private const val ID_DONE = 42

        private fun channel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH, "Hochladen", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        private fun openApp(ctx: Context): PendingIntent? {
            val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
            return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun build(ctx: Context, text: String, i: Int, n: Int, ongoing: Boolean): Notification {
            channel(ctx)
            return Notification.Builder(ctx, CH)
                .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_upload_done)
                .setContentTitle(if (ongoing) "Foto-Aufräumer lädt hoch" else "Foto-Aufräumer")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(ctx))
                .apply { if (ongoing && n > 0) setProgress(n, i, false) }
                .build()
        }

        fun start(ctx: Context, text: String) {
            ctx.getSystemService(NotificationManager::class.java).cancel(ID_DONE)
            try {
                ctx.startForegroundService(Intent(ctx, UploadService::class.java).putExtra("text", text))
            } catch (_: Exception) {
                // Start nur aus dem Vordergrund erlaubt – dann eben ohne Dienst weiter
            }
        }

        fun progress(ctx: Context, text: String, i: Int, n: Int) {
            try {
                ctx.getSystemService(NotificationManager::class.java).notify(ID_RUN, build(ctx, text, i, n, ongoing = true))
            } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, UploadService::class.java))
            ctx.getSystemService(NotificationManager::class.java).cancel(ID_RUN)
        }

        /** Abschluss-Meldung, bleibt stehen bis zum Antippen. */
        fun done(ctx: Context, text: String) {
            try {
                ctx.getSystemService(NotificationManager::class.java).notify(ID_DONE, build(ctx, text, 0, 0, ongoing = false))
            } catch (_: Exception) {}
        }
    }
}
