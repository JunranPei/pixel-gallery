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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

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
        super.onCreate(savedInstanceState)
        _intentSenderLauncher = intentSenderLauncher
        
        enableEdgeToEdge()
        
        val initialScreen = getScreenFromIntent(intent)
        val initialHomeTab = getHomeTabFromIntent(intent)
        updateTaskDescriptionFromIntent(intent)
        
        setContent {
            PixelGalleryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    MainScaffold(initialScreen = initialScreen, initialHomeTab = initialHomeTab)
                }
            }
        }
        
        checkPermissions()
        handleIntent(intent)
    }

    private fun getScreenFromIntent(intent: Intent): com.pixel.gallery.ui.Screen {
        val screen = intent.getStringExtra("extra_screen") ?: return com.pixel.gallery.ui.Screen.Home
        return when (screen) {
            "Photos", "Albums" -> com.pixel.gallery.ui.Screen.Home
            "Favourites" -> com.pixel.gallery.ui.Screen.Favourites
            "Trash" -> com.pixel.gallery.ui.Screen.Trash
            "PhotoAlbum" -> {
                val albumName = intent.getStringExtra("extra_param") ?: ""
                com.pixel.gallery.ui.Screen.Photo(albumName)
            }
            else -> com.pixel.gallery.ui.Screen.Home
        }
    }

    private fun getHomeTabFromIntent(intent: Intent): Int {
        val screen = intent.getStringExtra("extra_screen") ?: return -1
        return when (screen) {
            "Photos" -> 0
            "Albums" -> 1
            else -> -1
        }
    }

    private fun updateTaskDescriptionFromIntent(intent: Intent) {
        val title = intent.getStringExtra("extra_title")
        val shortcutId = intent.getStringExtra("extra_shortcut_id")
        if (title != null && shortcutId != null) {
            try {
                val bitmap = com.pixel.gallery.utils.ShortcutHelper.getSavedIconBitmap(this, shortcutId)
                if (bitmap != null) {
                    @Suppress("DEPRECATION")
                    setTaskDescription(android.app.ActivityManager.TaskDescription(title, bitmap, 0))
                }
            } catch (e: Exception) {
                e.printStackTrace()
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        updateTaskDescriptionFromIntent(intent)
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
