package com.pixel.gallery.services

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import com.pixel.gallery.MainActivity

/** Notification taps restore the exact document task instead of creating a gallery document. */
class VideoPlaybackReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent.getIntExtra("taskId", -1)
        val task = getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
            @Suppress("DEPRECATION")
            val candidate = if (Build.VERSION.SDK_INT >= 29) it.taskInfo.taskId else it.taskInfo.id
            candidate == taskId
        }
        try {
            val restored = task != null && runCatching { task.moveToFront(); true }.getOrDefault(false)
            if (!restored) intent.getStringExtra("uri")?.let { uri ->
                startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(uri), "video/mp4")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        } finally {
            finish()
        }
    }
}
