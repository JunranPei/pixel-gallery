package com.pixel.gallery.ui.components

import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.pixel.gallery.R
import com.pixel.gallery.ui.theme.EmphasizedTypography

/** Shared fixed-width title/back/overflow bar for full-screen media collections. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCollectionTopBar(
    title: String,
    onBack: () -> Unit,
    sortLabel: String = "Sort Photos",
    onSort: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        title = { Text(title, style = EmphasizedTypography.TitleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.labels_back))
            }
        },
        actions = {
            GalleryOverflowButton(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                GalleryMenuRow(sortLabel, Icons.AutoMirrored.Filled.Sort, accented = true) {
                    expanded = false
                    onSort()
                }
            }
        },
    )
}
