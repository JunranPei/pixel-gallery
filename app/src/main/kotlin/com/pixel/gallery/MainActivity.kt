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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.Screen
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.model.CloneMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @javax.inject.Inject
    lateinit var settingsRepository: SettingsRepository

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
        _intentSenderLauncher = intentSenderLauncher
        
        enableEdgeToEdge()
        
        val shortcutRoute = shortcutRoute(intent)
        updateTaskDescriptionFromIntent(intent)
        setContent {
            PixelGalleryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    MainScaffold(
                        initialScreen = shortcutRoute?.screen ?: Screen.Home,
                        initialHomeTab = shortcutRoute?.homeTab ?: -1,
                    )
                }
            }
        }
        
        checkPermissions()
        checkNotificationListenerPermission()

        lifecycleScope.launch {
            val mode = settingsRepository.cloneMode.first()
            routeIntent(intent, mode)
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
        if (!isNotificationListenerEnabled()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("开启后台保活支持")
                .setMessage("为防止多分身在后台被系统强杀，请在接下来的设置中，为本应用开启“通知使用权”。\n\n开启后，系统将为其提供硬件级的后台常驻保护。")
                .setPositiveButton("去开启") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(this, "未找到通知监听设置页面，请手动开启", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val packageNames = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this)
        return packageNames.contains(packageName)
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.e("GalleryLifecycle", "MainActivity.onStart(hashCode=${hashCode()})")
    }

    override fun onResume() {
        super.onResume()
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
    }

    override fun onPause() {
        super.onPause()
        android.util.Log.e("GalleryLifecycle", "MainActivity.onPause(hashCode=${hashCode()})")
        viewModel.setResumed(false)
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.e("GalleryLifecycle", "MainActivity.onStop(hashCode=${hashCode()})")
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.e("GalleryLifecycle", "MainActivity.onDestroy(hashCode=${hashCode()})")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateTaskDescriptionFromIntent(intent)
        lifecycleScope.launch {
            val mode = settingsRepository.cloneMode.first()
            if (shortcutRoute(intent) != null) {
                // Recreate so the shortcut target becomes the initial navigation entry.
                recreate()
            } else {
                routeIntent(intent, mode, forceNewTask = isLauncherIntent(intent))
            }
        }
    }

    private fun routeIntent(intent: Intent, mode: CloneMode, forceNewTask: Boolean = false) {
        if (mode == CloneMode.AUTO && (isExternalViewIntent(intent) || forceNewTask) &&
            !intent.getBooleanExtra(EXTRA_AUTO_ROUTED, false)
        ) {
            val forwarded = Intent(this, MainActivity::class.java).apply {
                action = intent.action
                data = intent.data
                type = intent.type
                clipData = intent.clipData
                putExtra(EXTRA_AUTO_ROUTED, true)
                intent.categories?.forEach { addCategory(it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            startActivity(forwarded)
            finish()
            return
        }
        handleIntent(intent)
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

    private fun isExternalViewIntent(intent: Intent): Boolean =
        intent.action == Intent.ACTION_VIEW && intent.data != null

    private fun isLauncherIntent(intent: Intent): Boolean =
        intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)

    private fun updateTaskDescriptionFromIntent(intent: Intent) {
        val title = intent.getStringExtra("extra_title")
        val shortcutId = intent.getStringExtra("extra_shortcut_id")
        if (title != null && shortcutId != null) {
            runCatching {
                val icon = com.pixel.gallery.utils.ShortcutHelper.getSavedIconBitmap(this, shortcutId)
                if (icon != null) {
                    @Suppress("DEPRECATION")
                    setTaskDescription(android.app.ActivityManager.TaskDescription(title, icon, 0))
                }
            }
        }
    }

    private data class ShortcutRoute(val screen: Screen, val homeTab: Int = -1)

    private fun shortcutRoute(intent: Intent): ShortcutRoute? {
        val target = intent.getStringExtra(EXTRA_SCREEN) ?: return null
        return when (target) {
            "Photos" -> ShortcutRoute(Screen.Home, homeTab = 0)
            "Albums" -> ShortcutRoute(Screen.Home, homeTab = 1)
            "Favourites" -> ShortcutRoute(Screen.Favourites)
            "Trash" -> ShortcutRoute(Screen.Trash)
            "PhotoAlbum" -> intent.getStringExtra(EXTRA_PARAM)
                ?.let { ShortcutRoute(Screen.Photo(it)) }
            else -> null
        }
    }

    companion object {
        private const val EXTRA_SCREEN = "extra_screen"
        private const val EXTRA_PARAM = "extra_param"
        private const val EXTRA_AUTO_ROUTED = "extra_auto_routed"
        const val DOCUMENT_TREE_ACCESS_REQUEST = 1
        const val MEDIA_WRITE_BULK_PERMISSION_REQUEST = 2

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
