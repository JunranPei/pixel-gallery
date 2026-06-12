package com.pixel.gallery.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.pixel.gallery.MainActivity
import java.io.File
import java.io.FileOutputStream

data class CustomShortcut(
    val id: String,
    val label: String,
    val targetScreen: String,
    val targetParam: String? = null
)

object ShortcutHelper {

    private const val SHORTCUT_SCHEME = "gallery-shortcut"
    private const val ICONS_DIR_NAME = "shortcut_icons"

    /**
     * Create a pinned shortcut on the launcher.
     */
    fun createPinnedShortcut(
        context: Context,
        id: String,
        label: String,
        iconBitmap: Bitmap,
        targetScreen: String,
        targetParam: String? = null
    ): Boolean {
        // 1. Save the custom icon bitmap locally so we can dynamically read it for TaskDescription
        saveIconBitmap(context, id, iconBitmap)

        // 2. Prepare the explicit launch intent targeting MainActivity
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            // Use unique Data URI to separate Tasks in system Recents
            data = Uri.parse("$SHORTCUT_SCHEME://shortcut/$id")
            putExtra("extra_screen", targetScreen)
            if (targetParam != null) {
                putExtra("extra_param", targetParam)
            }
            putExtra("extra_title", label)
            putExtra("extra_shortcut_id", id)
            // FLAG_ACTIVITY_NEW_DOCUMENT creates a new isolated task in Recents
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }

        // 3. Build ShortcutInfoCompat
        val shortcutInfo = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setIcon(IconCompat.createWithBitmap(iconBitmap))
            .setIntent(launchIntent)
            .build()

        // 4. Request the system to pin the shortcut
        return ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
    }

    /**
     * Disable a shortcut (this will either grey it out or remove it depending on Launcher).
     */
    fun disableShortcut(context: Context, id: String) {
        try {
            ShortcutManagerCompat.disableShortcuts(context, listOf(id), null)
        } catch (e: Exception) {
            // Ignore API issues on customized launchers
        }
        deleteIconBitmap(context, id)
    }

    /**
     * Read the saved custom icon bitmap for TaskDescription.
     */
    fun getSavedIconBitmap(context: Context, id: String): Bitmap? {
        val file = File(getIconsDir(context), "$id.png")
        return if (file.exists()) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    private fun getIconsDir(context: Context): File {
        val dir = File(context.filesDir, ICONS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun saveIconBitmap(context: Context, id: String, bitmap: Bitmap) {
        val file = File(getIconsDir(context), "$id.png")
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun deleteIconBitmap(context: Context, id: String) {
        val file = File(getIconsDir(context), "$id.png")
        if (file.exists()) {
            file.delete()
        }
    }
}
