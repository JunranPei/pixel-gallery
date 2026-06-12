package com.pixel.gallery.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.utils.CustomShortcut
import com.pixel.gallery.utils.ShortcutHelper
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutManagerScreen(
    onBack: () -> Unit,
    viewModel: PhotosViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val customShortcuts by viewModel.customShortcuts.collectAsState()
    val albums by viewModel.albums.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Desktop Shortcuts",
                        style = EmphasizedTypography.TitleLarge
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create Shortcut")
            }
        }
    ) { innerPadding ->
        if (customShortcuts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No custom shortcuts created",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Tap the + button to create a new desktop shortcut.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        "Active Shortcuts",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(customShortcuts, key = { it.id }) { shortcut ->
                    ShortcutItemRow(
                        shortcut = shortcut,
                        onDelete = {
                            viewModel.removeCustomShortcut(shortcut.id)
                            ShortcutHelper.disableShortcut(context, shortcut.id)
                            Toast.makeText(context, "Shortcut removed", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }

        if (showCreateDialog) {
            CreateShortcutDialog(
                albums = albums.map { it.name },
                onDismiss = { showCreateDialog = false },
                onCreate = { label, targetScreen, targetParam, iconBitmap ->
                    val id = UUID.randomUUID().toString()
                    val success = ShortcutHelper.createPinnedShortcut(
                        context = context,
                        id = id,
                        label = label,
                        iconBitmap = iconBitmap,
                        targetScreen = targetScreen,
                        targetParam = targetParam
                    )
                    if (success) {
                        viewModel.addCustomShortcut(
                            CustomShortcut(
                                id = id,
                                label = label,
                                targetScreen = targetScreen,
                                targetParam = targetParam
                            )
                        )
                        Toast.makeText(context, "Shortcut added to home screen", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to create shortcut", Toast.LENGTH_SHORT).show()
                    }
                    showCreateDialog = false
                }
            )
        }
    }
}

@Composable
fun ShortcutItemRow(
    shortcut: CustomShortcut,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val iconBitmap = remember(shortcut.id) {
        ShortcutHelper.getSavedIconBitmap(context, shortcut.id)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shortcut icon
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Photo,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shortcut.label,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = when (shortcut.targetScreen) {
                        "Photos" -> "Target: Photos"
                        "Albums" -> "Target: Albums"
                        "Favourites" -> "Target: Favorites"
                        "Trash" -> "Target: Trash"
                        "PhotoAlbum" -> "Target: Album (${shortcut.targetParam})"
                        else -> "Target: Home"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateShortcutDialog(
    albums: List<String>,
    onDismiss: () -> Unit,
    onCreate: (label: String, targetScreen: String, targetParam: String?, iconBitmap: Bitmap) -> Unit
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf("") }
    var targetType by remember { mutableStateOf("Photos") } // Photos, Albums, Favourites, Trash, Album
    var selectedAlbum by remember { mutableStateOf(albums.firstOrNull() ?: "") }
    var iconSource by remember { mutableStateOf("default") } // default, custom, colored
    var selectedColor by remember { mutableStateOf(0xFF2196F3.toInt()) } // Default Blue
    var selectedIconBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val presetColors = listOf(
        0xFFF44336.toInt(), // Red
        0xFF4CAF50.toInt(), // Green
        0xFF2196F3.toInt(), // Blue
        0xFF9C27B0.toInt(), // Purple
        0xFFFF9800.toInt(), // Orange
        0xFF009688.toInt()  // Teal
    )

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val original = BitmapFactory.decodeStream(inputStream)
                if (original != null) {
                    val size = minOf(original.width, original.height)
                    val x = (original.width - size) / 2
                    val y = (original.height - size) / 2
                    val cropped = Bitmap.createBitmap(original, x, y, size, size)
                    val scaled = Bitmap.createScaledBitmap(cropped, 192, 192, true)
                    selectedIconBitmap = scaled
                    iconSource = "custom"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Shortcut") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Shortcut Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    Text("Target View", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TargetButton("Photos", targetType == "Photos") { targetType = "Photos" }
                        TargetButton("Albums", targetType == "Albums") { targetType = "Albums" }
                        TargetButton("Favorites", targetType == "Favourites") { targetType = "Favourites" }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TargetButton("Trash", targetType == "Trash") { targetType = "Trash" }
                        TargetButton("Specific Album", targetType == "PhotoAlbum") { targetType = "PhotoAlbum" }
                    }
                }

                if (targetType == "PhotoAlbum" && albums.isNotEmpty()) {
                    item {
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                        ) {
                            OutlinedTextField(
                                value = selectedAlbum,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Select Album") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                albums.forEach { album ->
                                    DropdownMenuItem(
                                        text = { Text(album) },
                                        onClick = {
                                            selectedAlbum = album
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text("Shortcut Icon", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TargetButton("Default", iconSource == "default") { iconSource = "default" }
                        TargetButton("Photo...", iconSource == "custom") {
                            pickImageLauncher.launch("image/*")
                        }
                        TargetButton("Text Tag", iconSource == "colored") { iconSource = "colored" }
                    }
                }

                if (iconSource == "colored") {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            presetColors.forEach { color ->
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(color))
                                        .border(
                                            width = if (selectedColor == color) 3.dp else 0.dp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            shape = CircleShape
                                        )
                                        .clickable { selectedColor = color }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (label.isBlank()) {
                        Toast.makeText(context, "Please enter a name", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val targetParam = if (targetType == "PhotoAlbum") selectedAlbum else null

                    // Generate final icon Bitmap
                    val finalBitmap = when (iconSource) {
                        "custom" -> selectedIconBitmap ?: getDefaultAppIcon(context)
                        "colored" -> {
                            val textTag = if (label.isNotEmpty()) label.take(1).uppercase() else "G"
                            generateColoredIcon(selectedColor, textTag)
                        }
                        else -> getDefaultAppIcon(context)
                    }

                    onCreate(label, targetType, targetParam, finalBitmap)
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun TargetButton(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) }
    )
}

private fun getDefaultAppIcon(context: Context): Bitmap {
    val drawable = context.packageManager.getApplicationIcon(context.packageName)
    val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    drawable.setBounds(0, 0, 192, 192)
    drawable.draw(canvas)
    return bitmap
}

private fun generateColoredIcon(colorVal: Int, text: String): Bitmap {
    val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    
    // Draw background circle
    paint.color = colorVal
    canvas.drawCircle(96f, 96f, 96f, paint)
    
    // Draw white text in center
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 80f
    paint.textAlign = android.graphics.Paint.Align.CENTER
    val fontMetrics = paint.fontMetrics
    val y = 96f - (fontMetrics.ascent + fontMetrics.descent) / 2f
    canvas.drawText(text, 96f, y, paint)
    
    return bitmap
}
