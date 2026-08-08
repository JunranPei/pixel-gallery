package com.pixel.gallery.ui.transfer

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Alignment
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
import com.pixel.gallery.model.matchesTransferDestinationQuery
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.theme.ExpressiveShapes
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.utils.StorageUtils
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun TransferDestinationScreen(
    entries: List<MediaEntry>,
    onBack: () -> Unit,
    onFinished: (TransferSummary) -> Unit,
    viewModel: PhotosViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val destinations by viewModel.transferDestinations.collectAsState()
    val transferState by viewModel.transferUiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val createdDestinations = remember { mutableStateListOf<TransferDestination>() }
    var query by remember { mutableStateOf("") }
    var selectedDestination by remember { mutableStateOf<TransferDestination?>(null) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var folderNameError by remember { mutableStateOf<String?>(null) }
    var showConflictDialog by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<TransferMode?>(null) }
    var conflictCount by remember { mutableStateOf(0) }
    var conflictPolicy by remember { mutableStateOf(ConflictPolicy.KEEP_BOTH) }
    val permissionRequests = remember {
        MutableSharedFlow<androidx.activity.result.IntentSenderRequest>(extraBufferCapacity = 1)
    }

    val allDestinations = remember(destinations, createdDestinations.toList()) {
        (createdDestinations + destinations)
            .distinctBy { it.stableKey.lowercase() }
    }
    val duplicateNames = remember(allDestinations) {
        allDestinations.groupBy { it.displayName.lowercase() }
            .filterValues { it.size > 1 }
            .keys
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
            scope.launch {
                snackbarHostState.showSnackbar("This folder is not available as a media destination")
            }
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

    LaunchedEffect(transferState.summary) {
        val summary = transferState.summary ?: return@LaunchedEffect
        if (summary.completedAny) {
            onFinished(summary)
        } else {
            val reason = summary.failures.firstOrNull()?.reason ?: "No items were transferred"
            snackbarHostState.showSnackbar(reason)
            viewModel.clearTransferState()
        }
    }

    LaunchedEffect(transferState.error) {
        val error = transferState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.clearTransferState()
    }

    fun executeTransfer(mode: TransferMode, policy: ConflictPolicy) {
        val destination = selectedDestination ?: return
        viewModel.requestTransfer(
            entries = entries,
            destination = destination,
            mode = mode,
            conflictPolicy = policy,
            onPermissionRequired = { permissionRequests.tryEmit(it) }
        )
    }

    fun startTransfer(mode: TransferMode) {
        val destination = selectedDestination ?: return
        if (
            mode == TransferMode.MOVE &&
            entries.isNotEmpty() &&
            entries.all { File(it.path).parentFile?.absolutePath == destination.path }
        ) {
            scope.launch { snackbarHostState.showSnackbar("Source and destination are the same") }
            return
        }
        val conflicts = entries.count { entry ->
            val source = File(entry.path)
            val target = File(destination.path, source.name)
            target.exists() && target.absolutePath != source.absolutePath
        }
        if (conflicts > 0) {
            pendingMode = mode
            conflictCount = conflicts
            conflictPolicy = ConflictPolicy.KEEP_BOTH
            showConflictDialog = true
        } else {
            executeTransfer(mode, ConflictPolicy.KEEP_BOTH)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Select destination", style = EmphasizedTypography.TitleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !transferState.isRunning) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showCreateFolderDialog = true },
                        enabled = !transferState.isRunning
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Create folder")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            TransferBottomBar(
                destination = selectedDestination,
                isRunning = transferState.isRunning,
                progress = transferState.progress?.let {
                    if (it.total == 0) 0f else it.completed.toFloat() / it.total.toFloat()
                },
                progressLabel = transferState.progress?.let {
                    val action = if (it.mode == TransferMode.MOVE) "Moving" else "Copying"
                    "$action ${it.completed} / ${it.total}"
                },
                onCopy = { startTransfer(TransferMode.COPY) },
                onMove = { startTransfer(TransferMode.MOVE) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search albums or folders") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = { directoryPickerLauncher.launch(null) },
                    modifier = Modifier.weight(1f),
                    enabled = !transferState.isRunning
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Other folders", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = { showCreateFolderDialog = true },
                    modifier = Modifier.weight(1f),
                    enabled = !transferState.isRunning
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("New folder", maxLines = 1)
                }
            }

            Text(
                text = if (query.isBlank()) "Albums" else "Search results",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 12.dp)
            )

            if (filteredDestinations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No matching folders",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 4.dp,
                        bottom = 24.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredDestinations, key = { it.stableKey }) { destination ->
                        TransferDestinationCard(
                            destination = destination,
                            selected = destination.stableKey == selectedDestination?.stableKey,
                            showParentPath = destination.displayName.lowercase() in duplicateNames,
                            onClick = { selectedDestination = destination }
                        )
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreateFolderDialog = false
                folderName = ""
                folderNameError = null
            },
            title = { Text("Create new folder") },
            text = {
                Column {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = {
                            folderName = it
                            folderNameError = null
                        },
                        label = { Text("Folder name") },
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
                        viewModel.createTransferFolder(parent, folderName) { result ->
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
                    enabled = folderName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showConflictDialog) {
        val canReplace = selectedDestination?.documentUri == null &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()))
        AlertDialog(
            onDismissRequest = { showConflictDialog = false },
            title = { Text("Items with the same name already exist") },
            text = {
                Column {
                    Text(
                        "$conflictCount conflict${if (conflictCount == 1) "" else "s"}. " +
                            "Choose how to handle all of them.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        supportingText = "Leave existing items unchanged",
                        selected = conflictPolicy == ConflictPolicy.SKIP,
                        enabled = true,
                        onClick = { conflictPolicy = ConflictPolicy.SKIP }
                    )
                    ConflictChoice(
                        label = "Replace",
                        supportingText = if (canReplace) {
                            "Replace existing items safely"
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
                    showConflictDialog = false
                    if (mode != null) executeTransfer(mode, conflictPolicy)
                }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConflictDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ConflictChoice(
    label: String,
    supportingText: String,
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
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
            )
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun TransferDestinationCard(
    destination: TransferDestination,
    selected: Boolean,
    showParentPath: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(ExpressiveShapes.ExtraLargeIncreased)
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(ExpressiveShapes.ExtraLargeIncreased)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (selected) {
                        Modifier.border(
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                            ExpressiveShapes.ExtraLargeIncreased
                        )
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (destination.coverUri != null) {
                GlideImage(
                    model = destination.coverUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            if (selected) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(2.dp)
                    )
                }
            }
        }
        Text(
            text = destination.displayName,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp)
        )
        Text(
            text = if (destination.itemCount == 1) "1 item" else "${destination.itemCount} items",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        if (showParentPath) {
            Text(
                text = File(destination.path).parent.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun TransferBottomBar(
    destination: TransferDestination?,
    isRunning: Boolean,
    progress: Float?,
    progressLabel: String?,
    onCopy: () -> Unit,
    onMove: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = if (isRunning) progressLabel.orEmpty() else destination?.displayName ?: "Select a destination",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = destination?.path.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            if (isRunning) {
                LinearProgressIndicator(
                    progress = { (progress ?: 0f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        onClick = onCopy,
                        enabled = destination != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Copy here")
                    }
                    FilledTonalButton(
                        onClick = onMove,
                        enabled = destination != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Move here")
                    }
                }
            }
        }
    }
}
