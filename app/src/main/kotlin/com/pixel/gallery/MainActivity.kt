package com.pixel.gallery

import androidx.fragment.app.FragmentActivity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.data.repository.LargeImagePerformanceConfig
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.pixel.gallery.ui.components.GalleryDialog
import com.pixel.gallery.ui.components.ConfirmationReminder
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import com.pixel.gallery.model.ThemeMode
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.ui.viewer.ViewerLoadMetrics
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @javax.inject.Inject
    lateinit var settingsRepository: SettingsRepository
    var viewerRotationGesture: com.pixel.gallery.ui.viewer.ViewerRotationGesture? = null
    internal val viewerGestureExclusions = com.pixel.gallery.ui.viewer.ViewerGestureExclusions()

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean =
        viewerRotationGesture?.dispatch(event) { super.dispatchTouchEvent(it) }
            ?: super.dispatchTouchEvent(event)

    var videoSession: com.pixel.gallery.ui.viewer.VideoPlaybackSession? = null
    var isVideoPip by androidx.compose.runtime.mutableStateOf(false)
        private set
    private var videoPipHost: com.pixel.gallery.ui.viewer.VideoPipHost? = null
    private var videoPipConfiguration by mutableStateOf<android.content.res.Configuration?>(null)
    private var pendingVideoPipConfiguration: android.content.res.Configuration? = null

    internal fun armVideoPip(rect: android.graphics.Rect?) {
        if (videoPipHost?.locked != true) pendingVideoPipConfiguration = android.content.res.Configuration(resources.configuration)
        videoPipHost?.arm(rect)
    }
    internal fun prepareVideoPip(rect: android.graphics.Rect?) { videoPipHost?.enter(rect) }
    internal fun cancelVideoPip() { videoPipHost?.exit(); pendingVideoPipConfiguration = null }
    internal val isVideoPipLayoutLocked: Boolean get() = videoPipHost?.locked == true

    override fun onPictureInPictureUiStateChanged(pipState: android.app.PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= 35 && pipState.isTransitioningToPip && !isVideoPip) {
            videoSession?.preparePipTransition()
        }
        ViewerLoadMetrics.event("VIDEO_PIP_UI", "transitioning=${if (Build.VERSION.SDK_INT >= 35) pipState.isTransitioningToPip else false}")
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) videoSession?.preparePipTransition() else cancelVideoPip()
        isVideoPip = isInPictureInPictureMode
        ViewerLoadMetrics.event("VIDEO_PIP_MODE", "active=$isVideoPip size=${newConfig.screenWidthDp}x${newConfig.screenHeightDp}")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        videoSession?.onUserLeaveHint()
        videoSession?.captureRetainedFrame("user-leave")
    }

    private val showNotificationAccess = mutableStateOf(false)

    private val viewModel: PhotosViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            viewModel.refresh()
        }
    }

    private val intentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.e("GalleryLifecycle", "MainActivity.onCreate(hashCode=${hashCode()})")
        
        super.onCreate(savedInstanceState)
        traceLifecycle(
            "ACTIVITY_CREATE",
            "hash=${hashCode()} saved=${savedInstanceState != null} " +
                "flags=0x${intent?.flags?.toString(16) ?: "0"} action=${intent?.action ?: "none"} " +
                "data=${intent?.data != null} type=${intent?.type ?: "none"}",
        )
        _intentSenderLauncher = intentSenderLauncher
        
        enableEdgeToEdge()
        
        setContent {
            val currentConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalConfiguration provides (videoPipConfiguration ?: currentConfiguration),
            ) {
            val materialYou by viewModel.materialYou.collectAsState(initial = true)
            val showFullAlbumNames by viewModel.showFullAlbumNames.collectAsState()
            val themeMode by viewModel.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> systemDarkTheme
            }
            PixelGalleryTheme(darkTheme = darkTheme, dynamicColor = materialYou) {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.pixel.gallery.ui.home.LocalShowFullAlbumNames provides showFullAlbumNames,
                    ) {
                        val shortcutTarget = intent.getStringExtra("extra_screen")
                        val shortcutScreen = when (shortcutTarget) {
                            "Favourites" -> com.pixel.gallery.ui.Screen.Favourites
                            "Trash" -> com.pixel.gallery.ui.Screen.Trash
                            "PhotoAlbum" -> intent.getStringExtra("extra_param")?.let { com.pixel.gallery.ui.Screen.Photo(it) }
                                ?: com.pixel.gallery.ui.Screen.Home
                            else -> com.pixel.gallery.ui.Screen.Home
                        }
                        MainScaffold(initialScreen = shortcutScreen, initialHomeTab = when (shortcutTarget) {
                            "Photos" -> 0
                            "Albums" -> 1
                            else -> -1
                        })
                    }
                    if (showNotificationAccess.value) {
                        GalleryDialog(
                            onDismissRequest = { showNotificationAccess.value = false },
                            title = { Text(stringResource(R.string.dialog_notifications_title)) },
                            text = { ConfirmationReminder(stringResource(R.string.dialog_notifications_body)) },
                            confirmButton = {
                                Button(onClick = {
                                    showNotificationAccess.value = false
                                    runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                                }) { Text(stringResource(R.string.dialog_open_settings)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showNotificationAccess.value = false }) { Text(stringResource(R.string.dialog_cancel)) }
                            },
                        )
                    }
                }
            }
        }
        
        }
        val contentRoot = findViewById<android.view.ViewGroup>(android.R.id.content)
        val composeContent = contentRoot.getChildAt(0)
        val composeParams = composeContent.layoutParams
        contentRoot.removeView(composeContent)
        videoPipHost = com.pixel.gallery.ui.viewer.VideoPipHost(this, composeContent) { locked ->
            videoPipConfiguration = if (locked) pendingVideoPipConfiguration ?: android.content.res.Configuration(resources.configuration) else null
        }.also { contentRoot.addView(it, composeParams) }

        checkPermissions()
        intent.getStringExtra("extra_title")?.let { title ->
            val icon = intent.getStringExtra("extra_shortcut_id")?.let {
                com.pixel.gallery.utils.ShortcutHelper.getSavedIconBitmap(this, it)
            }
            setTaskDescription(android.app.ActivityManager.TaskDescription(title, icon))
        }
        handleIntent(intent)
        checkNotificationListenerPermission()

        lifecycleScope.launch {
            settingsRepository.largeImageTileSize.collect {
                LargeImagePerformanceConfig.tileSize = it
            }
        }
        lifecycleScope.launch {
            settingsRepository.largeImageDebounceMs.collect {
                LargeImagePerformanceConfig.debounceMs = it
            }
        }
        lifecycleScope.launch {
            settingsRepository.largeImageHardwareBitmap.collect {
                LargeImagePerformanceConfig.useHardwareBitmap = it
            }
        }
        lifecycleScope.launch {
            settingsRepository.largeImageMaxCores.collect {
                LargeImagePerformanceConfig.updateMaxCores(it)
            }
        }
    }



    private fun checkPermissions() {
        // Request MANAGE_EXTERNAL_STORAGE for Android 11+ to avoid per-file prompts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${packageName}")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needsPermission = permissions.any {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (needsPermission) {
            requestPermissionLauncher.launch(permissions)
        } else {
            viewModel.refresh()
        }
    }

    private fun checkNotificationListenerPermission() {
        showNotificationAccess.value = !isNotificationListenerEnabled()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val packageNames = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this)
        return packageNames.contains(packageName)
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.e("GalleryLifecycle", "MainActivity.onStart(hashCode=${hashCode()})")
        traceLifecycle("ACTIVITY_START", "hash=${hashCode()}")
    }

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode) cancelVideoPip()
        videoSession?.onActivityResumed()
        android.util.Log.e("GalleryLifecycle", "MainActivity.onResume(hashCode=${hashCode()})")
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (hasPermission) {
            viewModel.setResumed(true)
        } else {
            viewModel.setResumed(false)
        }
        traceLifecycle("ACTIVITY_RESUME", "hash=${hashCode()} permission=$hasPermission")
    }

    override fun onPause() {
        videoSession?.captureRetainedFrame("activity-pause")
        super.onPause()
        android.util.Log.e("GalleryLifecycle", "MainActivity.onPause(hashCode=${hashCode()})")
        traceLifecycle("ACTIVITY_PAUSE", "hash=${hashCode()}")
        viewModel.setResumed(false)
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.e("GalleryLifecycle", "MainActivity.onStop(hashCode=${hashCode()})")
        traceLifecycle("ACTIVITY_STOP", "hash=${hashCode()}")
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.e("GalleryLifecycle", "MainActivity.onDestroy(hashCode=${hashCode()})")
        traceLifecycle(
            "ACTIVITY_DESTROY",
            "hash=${hashCode()} changing=${isChangingConfigurations} finishing=${isFinishing}",
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        traceLifecycle("ACTIVITY_SAVE_STATE", "hash=${hashCode()}")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        traceLifecycle(
            "ACTIVITY_NEW_INTENT",
            "hash=${hashCode()} flags=0x${intent.flags.toString(16)} " +
                "action=${intent.action ?: "none"} data=${intent.data != null} type=${intent.type ?: "none"}",
        )
        if (com.pixel.gallery.BuildConfig.VIEWER_METRICS_ENABLED &&
            intent.action == DEBUG_RECREATE_ACTION
        ) {
            ViewerLoadMetrics.event(
                "DEBUG_RECREATE_REQUEST",
                "hash=${hashCode()} taskId=${taskId} package=${packageName}",
            )
            recreate()
            return
        }
        handleIntent(intent)
    }

    private fun traceLifecycle(name: String, detail: String) {
        ViewerLoadMetrics.event(name, detail)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                val mimeType = intent.type ?: contentResolver.getType(uri) ?: "image/*"
                viewModel.setExternalMediaUri(uri.toString(), mimeType)
            }
        }
    }

    companion object {
        const val DOCUMENT_TREE_ACCESS_REQUEST = 1
        const val MEDIA_WRITE_BULK_PERMISSION_REQUEST = 2
        const val DEBUG_RECREATE_ACTION = "com.pixel.gallery.DEBUG_RECREATE_ACTIVITY"

        private var _intentSenderLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
        
        fun launchIntentSender(request: IntentSenderRequest) {
            _intentSenderLauncher?.launch(request)
        }

        val pendingStorageAccessResultHandlers = HashMap<Int, PendingStorageAccessResultHandler>()
        var pendingScopedStoragePermissionCompleter: java.util.concurrent.CompletableFuture<Boolean>? = null

        fun notifyError(message: String) {
            android.util.Log.e("MainActivity", message)
        }
    }
}

data class PendingStorageAccessResultHandler(val path: String?, val onGranted: (android.net.Uri) -> Unit, val onDenied: () -> Unit)
