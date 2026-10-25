package com.pixel.gallery.ui.transfer

import com.pixel.gallery.ui.components.GalleryDialog
import com.pixel.gallery.ui.components.ConfirmationReminder
import com.pixel.gallery.ui.components.ConfirmationFact
import android.app.Activity
import android.content.Intent
import android.os.Environment
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.pixel.gallery.R
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.pixel.gallery.model.QuickSortAlbumUse
import com.pixel.gallery.model.frequentQuickSortAlbums
import com.pixel.gallery.ui.viewmodel.QuickSortViewModel
import com.pixel.gallery.model.Handedness
import com.pixel.gallery.ui.utils.actionBottomAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.TransferDestination
import com.pixel.gallery.model.ConflictPolicy
import com.pixel.gallery.model.TransferMode
import com.pixel.gallery.model.TransferSummary
import com.pixel.gallery.model.isSameTransferDirectory
import com.pixel.gallery.model.matchesTransferDestinationQuery
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.theme.ExpressiveShapes
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.utils.StorageUtils
import com.pixel.gallery.ui.components.GalleryScrollbar
import com.pixel.gallery.ui.home.AlbumCoverCard
import com.pixel.gallery.ui.components.SelectionGrid
import com.pixel.gallery.ui.components.SelectionSearchField
import com.pixel.gallery.ui.components.LocalGalleryMessages
import com.pixel.gallery.ui.components.SelectionBottomSheet
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.pixel.gallery.ui.utils.pinchToZoomColumns
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun TransferDestinationScreen(
    entries: List<MediaEntry>,
    onBack: () -> Unit,
    onFinished: (TransferSummary) -> Unit,
    columns: Int = 3,
    onColumnsChange: (Int) -> Unit = {},
    viewModel: PhotosViewModel = hiltViewModel(),
    quickSortViewModel: QuickSortViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val destinations by viewModel.transferDestinations.collectAsState()
    val handedness by viewModel.handedness.collectAsState()
    val quickQueue by quickSortViewModel.state.collectAsState()
    val transferState by viewModel.transferUiState.collectAsState()
    var lastTransferMode by rememberSaveable { mutableStateOf(TransferMode.MOVE) }
    BackHandler(enabled = transferState.isRunning) {}
    val messages = LocalGalleryMessages.current
    val scope = rememberCoroutineScope()
    val createdDestinations = remember { mutableStateListOf<TransferDestination>() }
    var query by remember { mutableStateOf("") }
    var selectedDestination by remember { mutableStateOf<TransferDestination?>(null) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var folderNameError by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<PendingTransfer?>(null) }
    var confirmedDestination by remember { mutableStateOf<TransferDestination?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var showConflictDialog by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<TransferMode?>(null) }
    var pendingEntries by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var conflictCount by remember { mutableStateOf(0) }
    var conflictPolicy by remember { mutableStateOf(ConflictPolicy.KEEP_BOTH) }
    val permissionRequests = remember {
        MutableSharedFlow<androidx.activity.result.IntentSenderRequest>(extraBufferCapacity = 1)
    }

    val allDestinations = remember(destinations, createdDestinations.toList()) {
        (createdDestinations + destinations)
            .distinctBy { it.stableKey.lowercase() }
    }
    val filteredDestinations = remember(allDestinations, query) {
        if (query.isBlank()) {
            allDestinations
        } else {
            allDestinations.filter { matchesTransferDestinationQuery(it, query) }
        }
    }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onTransferPermissionResult(
            granted = result.resultCode == Activity.RESULT_OK,
            onPermissionRequired = { permissionRequests.tryEmit(it) }
        )
    }

    LaunchedEffect(writePermissionLauncher) {
        permissionRequests.collect { request ->
            writePermissionLauncher.launch(request)
        }
    }

    val directoryPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val path = StorageUtils.convertTreeDocumentUriToDirPath(context, uri)
        if (path == null) {
            messages.show("This folder is not available as a media destination")
        } else {
            val file = File(path.trimEnd(File.separatorChar))
            val destination = TransferDestination(
                stableKey = uri.toString(),
                displayName = file.name.ifEmpty { path },
                path = file.absolutePath,
                documentUri = DocumentsContract.buildDocumentUriUsingTree(
                    uri,
                    DocumentsContract.getTreeDocumentId(uri)
                ).toString()
            )
            createdDestinations.removeAll { it.stableKey == destination.stableKey }
            createdDestinations.add(0, destination)
            selectedDestination = destination
        }
    }

    fun executeTransfer(
        mode: TransferMode,
        policy: ConflictPolicy,
        transferEntries: List<MediaEntry>
    ) {
        val destination = confirmedDestination ?: return
        lastTransferMode = mode
        viewModel.requestTransfer(
            entries = transferEntries,
            destination = destination,
            mode = mode,
            conflictPolicy = policy,
            onPermissionRequired = { permissionRequests.tryEmit(it) }
        )
    }

    fun reviewTransfer(mode: TransferMode) {
        val destination = selectedDestination ?: return
        val transferEntries = entries.filterNot { isSameTransferDirectory(it.path, destination.path) }
        if (transferEntries.isEmpty()) {
            messages.show(context.getString(R.string.transfer_already_in_album))
            return
        }
        confirmation = PendingTransfer(mode, destination, transferEntries)
    }

    fun startTransfer(plan: PendingTransfer) {
        confirmedDestination = plan.destination
        val conflicts = plan.entries.count { File(plan.destination.path, File(it.path).name).exists() }
        if (conflicts > 0) {
            pendingMode = plan.mode
            pendingEntries = plan.entries
            conflictCount = conflicts
            conflictPolicy = ConflictPolicy.KEEP_BOTH
            showConflictDialog = true
        } else {
            executeTransfer(plan.mode, ConflictPolicy.KEEP_BOTH, plan.entries)
        }
    }

    SelectionBottomSheet(
        title = stringResource(R.string.transfer_title),
        onDismiss = { if (!transferState.isRunning) onBack() },
        confirmDismiss = { !transferState.isRunning },
    ) {
        TransferDestinationContent(
            destinations = filteredDestinations,
            albumUses = quickQueue.albumUses,
            query = query,
            selectedDestination = selectedDestination,
            isRunning = transferState.isRunning,
            progress = transferState.progress?.let { if (it.total == 0) 0f else it.completed.toFloat() / it.total },
            progressLabel = transferState.progress?.let {
                val action = if (it.mode == TransferMode.MOVE) "Moving" else "Copying"
                "$action ${it.completed} / ${it.total}"
            },
            onQueryChange = { query = it },
            onSelect = { selectedDestination = it },
            onNewAlbum = { showCreateFolderDialog = true },
            onOtherFolders = { directoryPickerLauncher.launch(null) },
            onCopy = { reviewTransfer(TransferMode.COPY) },
            onMove = { reviewTransfer(TransferMode.MOVE) },
            handedness = handedness,
            columns = columns,
            onColumnsChange = onColumnsChange,
            modifier = Modifier.fillMaxSize(),
        )
    }

    TransferResultFeedback(
        summary = transferState.summary,
        error = transferState.error,
        mode = lastTransferMode,
        onFinished = onFinished,
        onClear = viewModel::clearTransferState,
    )

    confirmation?.let { plan ->
        TransferConfirmationDialog(
            mode = plan.mode,
            destination = plan.destination,
            itemCount = plan.entries.size,
            onDismiss = { confirmation = null },
            onConfirm = { confirmation = null; startTransfer(plan) },
        )
    }

    if (showCreateFolderDialog) {
        GalleryDialog(
            onDismissRequest = {
                if (isCreating) return@GalleryDialog
                showCreateFolderDialog = false
                folderName = ""
                folderNameError = null
            },
            title = { Text(stringResource(R.string.transfer_new_album)) },
            icon = Icons.Default.CreateNewFolder,
            dismissEnabled = !isCreating,
            text = {
                Column {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = {
                            folderName = it
                            folderNameError = null
                        },
                        label = { Text(stringResource(R.string.transfer_album_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCreating,
                        singleLine = true,
                        isError = folderNameError != null,
                        supportingText = folderNameError?.let { { Text(it) } }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parent = selectedDestination ?: TransferDestination(
                            stableKey = "default-pictures",
                            displayName = Environment.DIRECTORY_PICTURES,
                            path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath
                        )
                        isCreating = true
                        viewModel.createTransferFolder(parent, folderName.trim()) { result ->
                            isCreating = false
                            result.onSuccess { destination ->
                                createdDestinations.add(0, destination)
                                selectedDestination = destination
                                showCreateFolderDialog = false
                                folderName = ""
                            }.onFailure {
                                folderNameError = it.message ?: "Could not create folder"
                            }
                        }
                    },
                    enabled = folderName.isNotBlank() && !isCreating
                ) {
                    Text(stringResource(R.string.transfer_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }, enabled = !isCreating) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    if (showConflictDialog) {
        val canReplace = confirmedDestination?.documentUri == null &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()))
        GalleryDialog(
            onDismissRequest = {
                showConflictDialog = false
                pendingMode = null
                pendingEntries = emptyList()
            },
            title = { Text("Items with the same name already exist") },
            icon = Icons.Outlined.ContentCopy,
            text = {
                Column {
                    ConfirmationReminder("$conflictCount conflict${if (conflictCount == 1) "" else "s"}.")
                    Spacer(Modifier.height(12.dp))
                    ConflictChoice(
                        label = "Keep both",
                        supportingText = "Create a numbered copy",
                        selected = conflictPolicy == ConflictPolicy.KEEP_BOTH,
                        enabled = true,
                        onClick = { conflictPolicy = ConflictPolicy.KEEP_BOTH }
                    )
                    ConflictChoice(
                        label = "Skip",
                        supportingText = null,
                        selected = conflictPolicy == ConflictPolicy.SKIP,
                        enabled = true,
                        onClick = { conflictPolicy = ConflictPolicy.SKIP }
                    )
                    ConflictChoice(
                        label = "Replace",
                        supportingText = if (canReplace) {
                            null
                        } else {
                            "Requires all-files access on this Android version"
                        },
                        selected = conflictPolicy == ConflictPolicy.REPLACE,
                        enabled = canReplace,
                        onClick = { conflictPolicy = ConflictPolicy.REPLACE }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val mode = pendingMode
                    val transferEntries = pendingEntries
                    showConflictDialog = false
                    pendingMode = null
                    pendingEntries = emptyList()
                    if (mode != null && transferEntries.isNotEmpty()) {
                        executeTransfer(mode, conflictPolicy, transferEntries)
                    }
                }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showConflictDialog = false
                    pendingMode = null
                    pendingEntries = emptyList()
                }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }
}

@Composable
internal fun ConflictChoice(
    label: String,
    supportingText: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            supportingText?.let { Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
            ) }
        }
    }
}

@Composable
private fun TransferDestinationCard(
    destination: TransferDestination,
    selected: Boolean,
    showParentPath: Boolean,
    columns: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    AlbumCoverCard(
        title = destination.displayName,
        subtitle = "${destination.itemCount} items",
        coverUri = destination.coverUri?.takeIf { it.isNotBlank() },
        coverModifiedMillis = destination.lastModified,
        columns = columns,
        selected = selected,
        enabled = enabled,
        onClick = onClick,
    ) {
        if (showParentPath) {
            Text(File(destination.path).parent.orEmpty(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class PendingTransfer(val mode: TransferMode, val destination: TransferDestination, val entries: List<MediaEntry>)

@Composable
fun TransferConfirmationDialog(mode: TransferMode, destination: TransferDestination, itemCount: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val move = mode == TransferMode.MOVE
    GalleryDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (move) R.string.dialog_move_title else R.string.dialog_copy_title, itemCount)) },
        icon = if (move) Icons.AutoMirrored.Outlined.DriveFileMove else Icons.Outlined.ContentCopy,
        text = {
            ConfirmationFact(stringResource(R.string.dialog_destination), destination.displayName, destination.path)
            ConfirmationReminder(stringResource(if (move) R.string.dialog_move_body else R.string.dialog_copy_body),
                if (move) Icons.AutoMirrored.Outlined.DriveFileMove else Icons.Outlined.ContentCopy)
            if (!move) ConfirmationReminder(stringResource(R.string.dialog_copy_source))
        },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(if (move) R.string.dialog_move else R.string.dialog_copy)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

/** The grid owns all scrolling; its action buttons float directly over it. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun TransferDestinationContent(
    destinations: List<TransferDestination>,
    albumUses: List<QuickSortAlbumUse>,
    query: String,
    selectedDestination: TransferDestination?,
    isRunning: Boolean,
    progress: Float?,
    progressLabel: String?,
    onQueryChange: (String) -> Unit,
    onSelect: (TransferDestination) -> Unit,
    onNewAlbum: () -> Unit,
    onOtherFolders: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    columns: Int = 3,
    onColumnsChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    handedness: Handedness = Handedness.RIGHT,
) {
    val frequent = if (query.isBlank()) frequentQuickSortAlbums(destinations, albumUses, System.currentTimeMillis()) else emptyList()
    val duplicateNames = destinations.groupBy { it.displayName.lowercase() }.filterValues { it.size > 1 }.keys
    val gridState = rememberLazyGridState()
    val gridColumns = columns.coerceIn(2, 6)
    Column(modifier.fillMaxSize()) {
        SelectionSearchField(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.transfer_search),
            enabled = !isRunning,
        ) {
            androidx.compose.material3.FilledTonalIconButton(
                onClick = onNewAlbum, enabled = !isRunning,
                modifier = Modifier.size(48.dp), shape = ExpressiveShapes.LargeIncreased,
            ) { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.transfer_new_album)) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SelectionGrid(
                columns = gridColumns,
                onColumnsChange = onColumnsChange,
                state = gridState,
                bottomPadding = 180.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (frequent.isNotEmpty()) {
                    item(key = "frequent-heading", span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.qs_targets_frequent), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(4.dp))
                    }
                    items(frequent, key = { "frequent-${it.stableKey}" }) { destination ->
                        TransferDestinationCard(destination, destination.stableKey == selectedDestination?.stableKey,
                            destination.displayName.lowercase() in duplicateNames, columns = gridColumns, enabled = !isRunning, onClick = { if (!isRunning) onSelect(destination) })
                    }
                }
                item(key = "albums-heading", span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(if (query.isBlank()) R.string.transfer_albums else R.string.transfer_results),
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(4.dp))
                }
                if (destinations.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.transfer_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp, 16.dp))
                }
                items(destinations, key = { it.stableKey }) { destination ->
                    TransferDestinationCard(destination, destination.stableKey == selectedDestination?.stableKey,
                        destination.displayName.lowercase() in duplicateNames, columns = gridColumns, enabled = !isRunning, onClick = { if (!isRunning) onSelect(destination) })
                }
                item(key = "other-folders", span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = onOtherFolders, enabled = !isRunning) {
                        Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.transfer_other_folders))
                    }
                }
            }
            if (isRunning) {
                Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(progressLabel.orEmpty(), style = MaterialTheme.typography.labelLarge)
                        LinearProgressIndicator(
                            progress = { (progress ?: 0f).coerceIn(0f, 1f) },
                            drawStopIndicator = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                Column(Modifier.align(handedness.actionBottomAlignment).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val enabled = selectedDestination != null
                    Surface(onClick = onCopy, enabled = enabled, shape = CircleShape, shadowElevation = 1.dp,
                        color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(56.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.dialog_copy)) }
                    }
                    Surface(onClick = onMove, enabled = enabled, shape = CircleShape, shadowElevation = 1.dp,
                        color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(56.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, stringResource(R.string.dialog_move)) }
                    }
                }
            }
        }
    }
}
