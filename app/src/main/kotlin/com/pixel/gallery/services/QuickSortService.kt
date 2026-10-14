package com.pixel.gallery.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.repository.QuickSortQueueRepository
import com.pixel.gallery.model.QuickSortJobStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

@AndroidEntryPoint
class QuickSortService : Service() {
    @Inject lateinit var queue: QuickSortQueueRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    private var startGeneration = 0
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Quick sort", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress while moving selected files"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Preparing move", 0, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        scope.launch {
            var lastUpdate = 0L
            queue.state.collectLatest { state ->
                val job = state.jobs.firstOrNull { it.status == QuickSortJobStatus.Running }
                if (job != null) {
                    val remaining = 750L - (System.currentTimeMillis() - lastUpdate)
                    if (remaining > 0) delay(remaining)
                    manager.notify(NOTIFICATION_ID, notification("Moving to ${job.destination.displayName}",
                        job.items.count { it.status != com.pixel.gallery.model.QuickSortItemStatus.Queued && it.status != com.pixel.gallery.model.QuickSortItemStatus.Running }, job.total))
                    lastUpdate = System.currentTimeMillis()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        queue.resumeFromService()
        startGeneration++
        latestStartId = startId
        if (worker?.isActive != true) {
            worker = scope.launch {
                try {
                    do {
                        val observedGeneration = startGeneration
                        withContext(Dispatchers.IO) { queue.runUntilIdle() }
                    } while (observedGeneration != startGeneration)
                }
                catch (error: Exception) { android.util.Log.e("QuickSortService", "Saved queue paused", error) }
                finally {
                    worker = null
                    if (stopSelfResult(latestStartId)) {
                        ServiceCompat.stopForeground(this@QuickSortService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    }
                }
            }
        }
        // Saved work resumes only after the user returns to the foreground, never by an unsafe background launch.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        queue.requestPause()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        queue.requestPause()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String, completed: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Quick sort")
            .setContentText(if (total > 0) "$text · $completed / $total" else text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setProgress(total, completed, total == 0)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    companion object {
        private const val CHANNEL = "quick_sort_moves"
        private const val NOTIFICATION_ID = 4207
    }
}
