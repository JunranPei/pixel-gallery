package com.pixel.gallery.integration

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixel.gallery.MainActivity
import com.pixel.gallery.ui.components.GalleryDialog
import com.pixel.gallery.ui.components.SettingsOptionControl
import com.pixel.gallery.ui.settings.SettingsClickItem
import com.pixel.gallery.ui.settings.SettingsBlockPosition
import com.pixel.gallery.ui.settings.SettingsLayout
import com.pixel.gallery.ui.settings.SettingsBlock
import com.pixel.gallery.ui.settings.SettingsBlockSpec
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Label
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import java.io.File

/** Design alternatives only. Uses production components; no files, settings or labels are mutated. */
internal class LabelBackupDesignPreviews(private val instrumentation: android.app.Instrumentation) {
    fun exercise(): String {
        val variant = mutableIntStateOf(1)
        val output = File(instrumentation.targetContext.cacheDir, "label-backup-designs").apply { mkdirs() }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync {
            activity.setContent { PixelGalleryTheme(dynamicColor = false, darkTheme = false) { BackupDesign(variant.intValue) } }
        }
        SystemClock.sleep(450)
        find("取消")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        SystemClock.sleep(500)
        for (index in 1..10) {
            instrumentation.runOnMainSync { variant.intValue = index }
            SystemClock.sleep(450)
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(output, "C%02d.png".format(index)).outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        instrumentation.runOnMainSync { activity.finish() }
        return "Ten label-backup alternatives rendered from production SettingsBlock, SettingsClickItem, SettingsOptionControl, GalleryDialog and Material3 buttons. Sample data only. Screenshots: $output"
    }

    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.contains(text) == true) return node
        for (index in 0 until node.childCount) find(text, node.getChild(index))?.let { return it }
        return null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupDesign(variant: Int) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("Library", style = EmphasizedTypography.TitleLarge) },
            navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = SettingsLayout.ContentPadding,
            verticalArrangement = Arrangement.spacedBy(SettingsLayout.GroupSpacing)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(SettingsLayout.ItemSpacing)) {
                    when (variant) {
                        1 -> {
                            BackupHeader()
                            BackupAction(true, SettingsBlockPosition.Middle)
                            BackupAction(false, SettingsBlockPosition.Last)
                        }
                        2 -> {
                            Text("Label backup", style = EmphasizedTypography.LabelLarge,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 14.dp, bottom = 4.dp))
                            BackupAction(true, SettingsBlockPosition.First)
                            BackupAction(false, SettingsBlockPosition.Last)
                        }
                        3 -> { BackupHeader(); BackupButtons(SettingsBlockPosition.Last) }
                        4 -> {
                            BackupHeader { IconButton(onClick = {}) { Icon(Icons.Outlined.FileUpload, "Export labels", tint = MaterialTheme.colorScheme.primary) } }
                            BackupAction(false, SettingsBlockPosition.Last)
                        }
                        5 -> {
                            BackupHeader { TextButton(onClick = {}) { Text("Export") } }
                            BackupAction(false, SettingsBlockPosition.Last)
                        }
                        6 -> {
                            BackupHeader()
                            SettingsBlock(spec = SettingsBlockSpec(position = SettingsBlockPosition.Middle)) {
                                SettingsOptionControl(options = listOf("export" to "Export", "import" to "Import"),
                                    selectedMode = "import", onSelected = {}, modifier = Modifier.padding(12.dp))
                            }
                            BackupFileRow(false)
                        }
                        7 -> {
                            BackupHeader()
                            BackupAction(true, SettingsBlockPosition.Middle, "Save labels as JSON")
                            BackupAction(false, SettingsBlockPosition.Last, "Choose a backup file")
                        }
                        8 -> {
                            BackupHeader()
                            BackupButtons(SettingsBlockPosition.Middle)
                            BackupFileRow(true)
                        }
                        9 -> {
                            BackupHeader {
                                Row {
                                    IconButton(onClick = {}) { Icon(Icons.Outlined.FileUpload, "Export labels", tint = MaterialTheme.colorScheme.primary) }
                                    IconButton(onClick = {}) { Icon(Icons.Outlined.FileDownload, "Import labels", tint = MaterialTheme.colorScheme.primary) }
                                }
                            }
                            BackupFileRow(false)
                        }
                        10 -> {
                            BackupHeader()
                            BackupButtons(SettingsBlockPosition.Middle)
                            SettingsBlock(spec = SettingsBlockSpec(position = SettingsBlockPosition.Last)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("248 / 256 files matched", style = MaterialTheme.typography.titleMedium)
                                    Text("18 labels", style = MaterialTheme.typography.bodyMedium)
                                    Text("Not found 6 · Ambiguous copies 2", style = MaterialTheme.typography.bodyMedium)
                                    Text("Existing labels are kept.", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = {}) { Text("Cancel") }
                                        Button(onClick = {}) { Text("Restore labels") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                SettingsBlock(spec = SettingsBlockSpec(position = SettingsBlockPosition.Single)) {
                    SettingsClickItem("Excluded Folders", icon = Icons.Outlined.FolderOff, onClick = {})
                }
            }
        }
    }
}

@Composable
private fun BackupHeader(trailing: (@Composable () -> Unit)? = null) {
    SettingsBlock(spec = SettingsBlockSpec(position = SettingsBlockPosition.First)) {
        ListItem(colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = { Text("Label backup", style = EmphasizedTypography.LabelLarge) },
            leadingContent = { Icon(Icons.Outlined.Label, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = trailing)
    }
}

@Composable
private fun BackupAction(export: Boolean, position: SettingsBlockPosition, description: String? = null) {
    SettingsBlock(spec = SettingsBlockSpec(position = position)) {
        SettingsClickItem(if (export) "Export labels" else "Import labels", description = description,
            icon = if (export) Icons.Outlined.FileUpload else Icons.Outlined.FileDownload, onClick = {})
    }
}

@Composable
private fun BackupButtons(position: SettingsBlockPosition) {
    SettingsBlock(spec = SettingsBlockSpec(position = position)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Export")
            }
            FilledTonalButton(onClick = {}, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import")
            }
        }
    }
}

@Composable
private fun BackupFileRow(hasFile: Boolean) {
    SettingsBlock(spec = SettingsBlockSpec(position = SettingsBlockPosition.Last)) {
        SettingsClickItem(if (hasFile) "labels-backup.json" else "Choose backup file",
            icon = Icons.Outlined.Description, onClick = {})
    }
}
