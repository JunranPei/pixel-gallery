package com.pixel.gallery.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pixel.gallery.ui.theme.EmphasizedTypography

data class SortCriterion(
    val id: String,
    val label: String,
    val ascLabel: String,
    val descLabel: String
)

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
                Text(
                    text = title,
                    style = EmphasizedTypography.TitleLarge, // Match Settings title style (EmphasizedTypography)
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(20.dp))

                // 1. Sort criterion section (3 options)
                Text(
                    text = "Sort by",
                    style = EmphasizedTypography.LabelLarge, // Match Settings list item title style (EmphasizedTypography)
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    criteria.forEach { criterion ->
                        val isSelected = selectedCriterion == criterion.id

                        Surface(
                            onClick = { selectedCriterion = criterion.id },
                            shape = CircleShape, // Always use CircleShape (fully rounded / large corners) as requested
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.padding(horizontal = 4.dp) // Layout naturally and shift size dynamically
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Slide checkmark icon in when selected, matching the bottom tab transition style
                                AnimatedVisibility(visible = isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Text(
                                    text = criterion.label,
                                    style = EmphasizedTypography.LabelLarge, // Match premium settings font
                                    modifier = Modifier.padding(start = if (isSelected) 6.dp else 0.dp),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 2. Sort direction section (2 options)
                Text(
                    text = "Order",
                    style = EmphasizedTypography.LabelLarge, // Match Settings list item title style (EmphasizedTypography)
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                val currentCriterionObj = criteria.find { it.id == selectedCriterion } ?: criteria.first()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val directions = listOf(
                        "ASC" to currentCriterionObj.ascLabel,
                        "DESC" to currentCriterionObj.descLabel
                    )
                    directions.forEach { (dirCode, dirLabel) ->
                        val isSelected = selectedDirection == dirCode

                        Surface(
                            onClick = { selectedDirection = dirCode },
                            shape = CircleShape, // Always use CircleShape (fully rounded / large corners) as requested
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.padding(horizontal = 4.dp) // Layout naturally and shift size dynamically
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Slide checkmark icon in when selected, matching the bottom tab transition style
                                AnimatedVisibility(visible = isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Text(
                                    text = dirLabel,
                                    style = EmphasizedTypography.LabelLarge, // Match premium settings font
                                    modifier = Modifier.padding(start = if (isSelected) 6.dp else 0.dp),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

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
