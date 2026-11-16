package app.pitstop.video.download

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.pitstop.video.R
import app.pitstop.video.core.I18n
import app.pitstop.video.graph
import app.pitstop.video.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Keeps the app running while downloads are pending, with a progress notification. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, I18n.s.downloads, NotificationManager.IMPORTANCE_LOW)
            )
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION, build(I18n.s.downloadPreparing, -1f, 0),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (!started) {
            started = true
            val downloads = graph.downloads
            scope.launch {
                @OptIn(kotlinx.coroutines.FlowPreview::class)
                downloads.tasks.sample(700).collect { tasks ->
                    val pending = tasks.filter { !it.finished }
                    if (pending.isEmpty()) {
                        val done = tasks.count { it.stage == DownloadTask.Stage.DONE }
                        val failed = tasks.count { it.stage == DownloadTask.Stage.FAILED }
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        if (done + failed > 0) manager.notify(NOTIFICATION_DONE, build(I18n.s.downloadsFinished(done, failed), null, 0, ongoing = false))
                        stopSelfResult(lastStartId)
                        return@collect
                    }
                    val now = pending.first()
                    manager.notify(NOTIFICATION, build(now.item.title, now.progress.takeIf { now.stage != DownloadTask.Stage.QUEUED }, pending.size - 1, now))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(title: String, progress: Float?, more: Int, task: DownloadTask? = null, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = listOfNotNull(task?.let { I18n.s.downloadStage(it.stage) }, if (more > 0) I18n.s.moreQueued(more) else null).joinToString(" · ")
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply {
                if (ongoing && progress != null) {
                    if (progress < 0) setProgress(0, 0, true) else setProgress(1000, (progress * 1000).toInt(), false)
                }
            }
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION = 7101
        private const val NOTIFICATION_DONE = 7102

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }
    }
}
