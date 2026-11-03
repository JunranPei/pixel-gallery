package com.pixel.gallery.ui.home

import android.content.SharedPreferences
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Label
import com.pixel.gallery.R

internal val groupArt = listOf(
    R.drawable.group_art_01,
    R.drawable.group_art_02,
    R.drawable.group_art_03,
    R.drawable.group_art_04,
    R.drawable.group_art_05,
    R.drawable.group_art_06,
    R.drawable.group_art_07,
    R.drawable.group_art_08,
    R.drawable.group_art_09,
    R.drawable.group_art_10,
    R.drawable.group_art_11,
    R.drawable.group_art_12,
    R.drawable.group_art_13,
    R.drawable.group_art_14,
    R.drawable.group_art_15,
    R.drawable.group_art_16,
    R.drawable.group_art_17,
    R.drawable.group_art_18,
    R.drawable.group_art_19,
    R.drawable.group_art_20,
    R.drawable.group_art_21,
    R.drawable.group_art_22,
    R.drawable.group_art_23,
    R.drawable.group_art_24,
    R.drawable.group_art_25,
    R.drawable.group_art_26,
    R.drawable.group_art_27,
    R.drawable.group_art_28,
    R.drawable.group_art_29,
    R.drawable.group_art_30,
    R.drawable.group_art_31,
    R.drawable.group_art_32,
    R.drawable.group_art_33,
    R.drawable.group_art_34,
    R.drawable.group_art_35,
    R.drawable.group_art_36,
    R.drawable.group_art_37,
    R.drawable.group_art_38,
    R.drawable.group_art_39,
    R.drawable.group_art_40,
    R.drawable.group_art_41,
    R.drawable.group_art_42,
    R.drawable.group_art_43,
    R.drawable.group_art_44,
    R.drawable.group_art_45,
    R.drawable.group_art_46,
    R.drawable.group_art_47,
    R.drawable.group_art_48,
    R.drawable.group_art_49,
    R.drawable.group_art_50
)
internal val labelGroupArt = listOf(
    R.drawable.label_group_art_01,
    R.drawable.label_group_art_02,
    R.drawable.label_group_art_03,
    R.drawable.label_group_art_04,
    R.drawable.label_group_art_05,
    R.drawable.label_group_art_06,
    R.drawable.label_group_art_07,
    R.drawable.label_group_art_08,
    R.drawable.label_group_art_09,
    R.drawable.label_group_art_10,
    R.drawable.label_group_art_11,
    R.drawable.label_group_art_12,
    R.drawable.label_group_art_13,
    R.drawable.label_group_art_14,
    R.drawable.label_group_art_15,
    R.drawable.label_group_art_16,
    R.drawable.label_group_art_17,
    R.drawable.label_group_art_18,
    R.drawable.label_group_art_19,
    R.drawable.label_group_art_20,
    R.drawable.label_group_art_21,
    R.drawable.label_group_art_22,
    R.drawable.label_group_art_23,
    R.drawable.label_group_art_24,
    R.drawable.label_group_art_25,
    R.drawable.label_group_art_26,
    R.drawable.label_group_art_27,
    R.drawable.label_group_art_28,
    R.drawable.label_group_art_29,
    R.drawable.label_group_art_30,
    R.drawable.label_group_art_31,
    R.drawable.label_group_art_32,
    R.drawable.label_group_art_33,
    R.drawable.label_group_art_34,
    R.drawable.label_group_art_35,
    R.drawable.label_group_art_36,
    R.drawable.label_group_art_37,
    R.drawable.label_group_art_38,
    R.drawable.label_group_art_39,
    R.drawable.label_group_art_40,
    R.drawable.label_group_art_41,
    R.drawable.label_group_art_42,
    R.drawable.label_group_art_43,
    R.drawable.label_group_art_44,
    R.drawable.label_group_art_45,
    R.drawable.label_group_art_46,
    R.drawable.label_group_art_47,
    R.drawable.label_group_art_48,
    R.drawable.label_group_art_49,
    R.drawable.label_group_art_50
)
internal fun groupIllustrationResource(choice: Int, labelGroup: Boolean): Int =
    (if (labelGroup) labelGroupArt else groupArt)[choice]

@Composable
private fun illustrationChoice(id: String): Pair<Int, (Int) -> Unit> {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("group_illustrations", 0) }
    var choice by remember(id) { mutableIntStateOf(prefs.getInt(id, 0).coerceIn(groupArt.indices)) }
    DisposableEffect(prefs, id) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == id) choice = prefs.getInt(id, 0).coerceIn(groupArt.indices)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return choice to { value -> prefs.edit().putInt(id, value).apply(); choice = value }
}
@Composable
fun GroupIllustration(id: String, modifier: Modifier = Modifier, labelGroup: Boolean = false) {
    val (choice, _) = illustrationChoice(id)
    Image(painterResource(groupIllustrationResource(choice, labelGroup)),
        contentDescription = null, modifier = modifier)
}
// Stable illustration IDs may be appended to any category without changing saved choices.
private val illustrationCategories = listOf(
    listOf(0, 1, 2, 3, 4),
    listOf(5, 6, 7, 8, 9),
    listOf(10, 11, 12, 13, 14),
    listOf(15, 16, 17, 18, 19),
    listOf(20, 21, 22, 23, 24),
    listOf(25, 26, 27, 28, 29),
    listOf(30, 31, 32, 33, 34),
    listOf(35, 36, 37, 38, 39),
    listOf(40, 41, 42, 43, 44),
    listOf(45, 46, 47, 48, 49)
)
@Composable
fun GroupIllustrationPicker(choice: Int, onSelect: (Int) -> Unit, onBack: () -> Unit, labelGroup: Boolean = false) {
    val categories = androidx.compose.ui.res.stringArrayResource(R.array.group_art_categories)
    androidx.compose.ui.window.Dialog(onDismissRequest = onBack,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        com.pixel.gallery.ui.components.SelectionBottomSheet(
            title = androidx.compose.ui.res.stringResource(R.string.group_choose_illustration),
            onDismiss = onBack,
        ) {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(illustrationCategories.size, key = { it }) { category ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(categories[category], style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 4.dp))
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)) {
                            items(illustrationCategories[category].size, key = { illustrationCategories[category][it] }) { position ->
                                val index = illustrationCategories[category][position]
                                Surface(onClick = { onSelect(index) }, modifier = Modifier.size(104.dp),
                                    shape = MaterialTheme.shapes.large,
                                    border = if (index == choice) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                    color = if (index == choice) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Image(painterResource(groupIllustrationResource(index, labelGroup)), contentDescription = "${categories[category]} ${position + 1}",
                                        modifier = Modifier.padding(10.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GroupCreateDialog(groups: List<com.pixel.gallery.model.AlbumGroup>, initialName: String,
    saving: Boolean, error: String?, onBack: () -> Unit, labelGroup: Boolean = false, onCreate: (String, Int) -> Unit) {
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialName) }
    var art by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var choosing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (choosing) {
        GroupIllustrationPicker(art, { art = it; choosing = false }, { choosing = false }, labelGroup)
        return
    }
    val valid = name.trim().isNotEmpty() && name.trim().length <= 60 && groups.none { it.name.equals(name.trim(), true) }
    com.pixel.gallery.ui.components.GalleryDialog(onDismissRequest = { if (!saving) onBack() },
        icon = if (labelGroup) androidx.compose.material.icons.Icons.Outlined.Label else androidx.compose.material.icons.Icons.Outlined.CreateNewFolder,
        title = { Text(androidx.compose.ui.res.stringResource(R.string.album_group_new)) }, dismissEnabled = !saving,
        text = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Surface(onClick = { choosing = true }, enabled = !saving, modifier = Modifier.size(112.dp),
                    shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Image(painterResource(groupIllustrationResource(art, labelGroup)), androidx.compose.ui.res.stringResource(R.string.group_choose_illustration), Modifier.padding(8.dp))
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, enabled = !saving,
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.album_group_name)) }, isError = name.isNotEmpty() && !valid)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = { onCreate(name.trim(), art) }, enabled = valid && !saving) { Text(androidx.compose.ui.res.stringResource(R.string.album_group_save)) } },
        dismissButton = { TextButton(onClick = onBack, enabled = !saving) { Text(androidx.compose.ui.res.stringResource(R.string.album_group_cancel)) } })
}
