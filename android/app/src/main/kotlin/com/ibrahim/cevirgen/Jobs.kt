package com.ibrahim.cevirgen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Çalışan dönüştürme işleri: iptal bayrakları/iptal işlemleri ve
 * uygulamadan çıkılsa da işin sürmesi için ön plan servisi + ilerleme bildirimi.
 */
object Jobs {
    const val CANCELLED = "__cevirgen_cancelled__"
    private const val CHANNEL_RUN = "work"
    private const val CHANNEL_DONE = "done"
    const val NOTIF_RUN = 41
    private const val NOTIF_DONE = 42

    class Cancelled : RuntimeException(CANCELLED)

    private class Job(val title: String) {
        val flag = AtomicBoolean(false)
        @Volatile var cancelAction: (() -> Unit)? = null
        @Volatile var progress = 0.0
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    @Volatile private var lastNotify = 0L
    @Volatile var appVisible = true

    fun begin(ctx: Context, id: String, title: String) {
        jobs[id] = Job(title)
        ensureChannels(ctx)
        try {
            val i = Intent(ctx, WorkService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (_: Exception) {
            // Servis başlatılamazsa iş yine de çalışır; sadece arka planda korunmaz.
        }
    }

    /**
     * İş bittiğinde (başarı/hata/iptal). Son iş bittiyse servis durur.
     * Dikkat: servis henüz startForeground() çağırmadan durdurulursa Android uygulamayı kapatır
     * (ForegroundServiceDidNotStartInTimeException). Bu yüzden servisi doğrudan durdurmuyoruz:
     * servis ayaktaysa kendini kapatır; henüz açılmadıysa açılınca önce bildirimi gösterip sonra kapanır.
     */
    fun end(ctx: Context, id: String, okTitle: String?) {
        val j = jobs.remove(id)
        if (jobs.isEmpty()) WorkService.stopIfIdle() else updateNotification(ctx, force = true)
        if (j != null && okTitle != null && !appVisible) notifyDone(ctx, okTitle)
    }

    fun isIdle() = jobs.isEmpty()

    fun setCancelAction(id: String, action: () -> Unit) {
        val j = jobs[id] ?: return
        j.cancelAction = action
        if (j.flag.get()) action() // iptal, iş tam başlarken basılmışsa
    }

    fun cancel(id: String) {
        val j = jobs[id] ?: return
        j.flag.set(true)
        j.cancelAction?.invoke()
    }

    /** Arka plan işleri ilerleme bildirirken iptal edilip edilmediğini de denetler. */
    fun checkCancelled(id: String) {
        if (jobs[id]?.flag?.get() == true) throw Cancelled()
    }

    fun progress(ctx: Context, id: String, value: Double) {
        jobs[id]?.progress = value
        updateNotification(ctx, force = false)
    }

    // ---------------------------------------------------------------- bildirimler

    private fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RUN, "Dönüştürme sürüyor", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Uzun süren işlerde ilerlemeyi gösterir"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Dönüştürme bitti", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Uygulama arka plandayken iş bitince haber verir"
        })
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @Suppress("DEPRECATION")
    private fun builder(ctx: Context, channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else Notification.Builder(ctx)

    fun runningNotification(ctx: Context): Notification {
        ensureChannels(ctx)
        val list = jobs.values.toList()
        val title = when {
            list.isEmpty() -> "Çevirgen hazırlanıyor"
            list.size == 1 -> list[0].title
            else -> "${list.size} iş dönüştürülüyor"
        }
        val pct = if (list.isEmpty()) 0 else (list.map { it.progress }.average() * 100).toInt().coerceIn(0, 100)
        return builder(ctx, CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(if (pct > 0) "%$pct · Dosyan telefonunda işleniyor" else "Dosyan telefonunda işleniyor")
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .build()
    }

    private fun updateNotification(ctx: Context, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        if (jobs.isEmpty()) return
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_RUN, runningNotification(ctx))
        } catch (_: SecurityException) {}
    }

    private fun notifyDone(ctx: Context, title: String) {
        try {
            val n = builder(ctx, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Dönüştürme bitti")
                .setContentText("Sonucu görmek için dokun.")
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_DONE, n)
        } catch (_: SecurityException) {}
    }
}

/** Uygulama arka plana geçince işin sistem tarafından durdurulmaması için ön plan servisi. */
class WorkService : Service() {
    companion object {
        @Volatile private var instance: WorkService? = null

        /** İş kalmadıysa servisi kapatır (yalnızca startForeground() çağrılmış servis). */
        fun stopIfIdle() {
            val s = instance ?: return // henüz başlamadı: onStartCommand'da kendisi kapanacak
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (Jobs.isIdle()) s.shutdown() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android kuralı: startForegroundService ile başlatılan servis, iş bitmiş olsa bile önce bunu çağırmalı
        val n = Jobs.runningNotification(this)
        try {
            when {
                Build.VERSION.SDK_INT >= 35 -> startForeground(Jobs.NOTIF_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                Build.VERSION.SDK_INT >= 29 -> startForeground(Jobs.NOTIF_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else -> startForeground(Jobs.NOTIF_RUN, n)
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        instance = this
        lastStartId = startId
        if (Jobs.isIdle()) shutdown() // iş, servis açılmadan bitmişti
        return START_NOT_STICKY
    }

    @Volatile private var lastStartId = 0

    private fun shutdown() {
        // stopSelfResult(son başlatma no): bu arada yeni bir iş servisi tekrar başlattıysa servis kapanmaz
        if (stopSelfResult(lastStartId)) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+: ön plan süresi dolarsa servisi kapat (iş uygulama açıkken sürmeye devam eder)
        stopSelf()
    }
}
