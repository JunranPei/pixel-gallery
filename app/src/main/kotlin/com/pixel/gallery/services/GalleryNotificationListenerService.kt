package com.pixel.gallery.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class GalleryNotificationListenerService : NotificationListenerService() {
    
    override fun onCreate() {
        super.onCreate()
        Log.d("KeepAliveService", "GalleryNotificationListenerService created. OOM_ADJ is now heavily protected.")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("KeepAliveService", "GalleryNotificationListenerService connected. Keepalive active.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d("KeepAliveService", "GalleryNotificationListenerService disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Keep silent to save energy
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Keep silent to save energy
    }
}
