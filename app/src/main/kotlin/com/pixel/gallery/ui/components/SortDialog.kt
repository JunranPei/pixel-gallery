package com.pixel.gallery.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pixel.gallery.model.AlbumSortOrder
import com.pixel.gallery.ui.theme.EmphasizedTypography

data class SortCriterion(
    val id: String,
    val label: String,
    val ascLabel: String,
    val descLabel: String
)

/** Album and label grids share one set of sort choices and direction mapping. */
@Composable
fun AlbumSortDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    initialOrder: AlbumSortOrder,
    onConfirm: (AlbumSortOrder) -> Unit,
) {
    val criteria = remember {
        listOf(
            SortCriterion("NAME", "Name", "A to Z", "Z to A"),
            SortCriterion("COUNT", "Count", "Least first", "Most first"),
            SortCriterion("DATE", "Date", "Oldest first", "Newest first"),
        )
    }
    SortDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = title,
        criteria = criteria,
        initialCriterion = initialOrder.criterion,
        initialDirection = initialOrder.direction,
        onConfirm = { criterion, direction ->
            onConfirm(AlbumSortOrder.fromSelection(criterion, direction))
        },
    )
}

@Composable
fun SortDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    criteria: List<SortCriterion>,
    initialCriterion: String,
    initialDirection: String,
    onConfirm: (criterion: String, direction: String) -> Unit
) {
    if (!visible) return

    var selectedCriterion by remember(initialCriterion, visible) { mutableStateOf(initialCriterion) }
    var selectedDirection by remember(initialDirection, visible) { mutableStateOf(initialDirection) }

    // Intercept hardware/system back button presses when the dialog is visible
    BackHandler(enabled = visible) {
        onDismissRequest()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Dimmed background - using 0.2f for a subtle dim as requested
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.2f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                )
        )

        // Dialog Content Container - using Surface with 6.dp elevations to match standard M3 AlertDialog shadow exactly
        Surface(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth(0.85f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // Intercept clicks inside the dialog
                ),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp, // Standard M3 AlertDialog tonal elevation
            shadowElevation = 6.dp // Standard M3 AlertDialog shadow elevation
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Sort, contentDescription = null, modifier = Modifier.size(22.dp))
                        }
                    }
                    Text(
                        text = title,
                        style = EmphasizedTypography.TitleLarge, // Match Settings title style (EmphasizedTypography)
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Spacer(modifier = Modifier.height(20.dp))

                // 1. Sort criterion section (3 options)
                Text(
                    text = "Sort by",
                    style = EmphasizedTypography.LabelLarge, // Match Settings list item title style (EmphasizedTypography)
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsOptionControl(
                    options = criteria.map { it.id to it.label },
                    selectedMode = selectedCriterion,
                    onSelected = { selectedCriterion = it },
                    unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 2. Sort direction section (2 options)
                Text(
                    text = "Order",
                    style = EmphasizedTypography.LabelLarge, // Match Settings list item title style (EmphasizedTypography)
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                val currentCriterionObj = criteria.find { it.id == selectedCriterion } ?: criteria.first()
                SettingsOptionControl(
                    options = listOf(
                        "ASC" to currentCriterionObj.ascLabel,
                        "DESC" to currentCriterionObj.descLabel,
                    ),
                    selectedMode = selectedDirection,
                    onSelected = { selectedDirection = it },
                    unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 3. Confirm/Cancel action buttons (M3 Expressive compliant pill shape)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismissRequest,
                        shape = CircleShape
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(selectedCriterion, selectedDirection) },
                        shape = CircleShape
                    ) {
                        Text("Confirm")
                    }
                }
            }
        }
    }
}
