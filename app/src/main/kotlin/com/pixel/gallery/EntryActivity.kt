package com.pixel.gallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.pixel.gallery.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A short-lived entry point for launcher and external-file intents.
 *
 * Keeping this separate from MainActivity is important: the entry point may
 * finish after forwarding an intent, while an already-running gallery task
 * must remain in Recents.
 */
@AndroidEntryPoint
class EntryActivity : ComponentActivity() {

    @javax.inject.Inject
    lateinit var settingsRepository: SettingsRepository

    private val entryCreatedAt = SystemClock.uptimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        // This activity only routes the launch. Keep its starting window in
        // place so the system can hand it directly to the real gallery UI.
        splashScreen.setKeepOnScreenCondition { true }
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val automatic = settingsRepository.autoCloneEnabled.first()
            val elapsed = SystemClock.uptimeMillis() - entryCreatedAt
            val remaining = (MINIMUM_HANDOFF_DELAY_MS - elapsed).coerceAtLeast(0L)
            if (remaining > 0L) delay(remaining)
            forwardToGallery(automatic)
        }
    }

    private fun forwardToGallery(automatic: Boolean) {
        val source = intent
        val externalView = source.action == Intent.ACTION_VIEW && source.data != null
        val launcher = source.action == Intent.ACTION_MAIN &&
            source.hasCategory(Intent.CATEGORY_LAUNCHER)
        val createIndependentTask = automatic && (externalView || launcher)

        val forwarded = Intent(this, MainActivity::class.java).apply {
            action = source.action
            type = source.type
            clipData = source.clipData
            source.data?.let { data = it }
            source.extras?.let { putExtras(it) }
            source.categories?.forEach { addCategory(it) }
            addFlags(source.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            if (createIndependentTask) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                if (launcher && data == null) {
                    // Give each launcher invocation a distinct document identity.
                    data = Uri.parse("gallery-auto://launch/${UUID.randomUUID()}")
                }
            } else {
                // Reuse the ordinary gallery task when automatic clones are off.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }

        startActivity(forwarded)
        // EntryActivity has its own task affinity, so removing its temporary
        // task cannot affect any existing gallery task.
        finishAndRemoveTask()
    }

    private companion object {
        const val MINIMUM_HANDOFF_DELAY_MS = 16L
    }
}
