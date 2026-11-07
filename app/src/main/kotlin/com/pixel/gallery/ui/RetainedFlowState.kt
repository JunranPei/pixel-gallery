package com.pixel.gallery.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.StateFlow

/** Retain a covered route's presentation; resubscribe with the latest value on return. */
@Composable
internal fun <T> StateFlow<T>.collectAsRetainedState(active: Boolean, initialized: Boolean = true): State<T> {
    val retained = remember(this) { mutableStateOf(value) }
    val hasSnapshot = remember(this) { mutableStateOf(false) }
    if (!active && hasSnapshot.value) return retained

    // A new subscription starts from StateFlow.value, so returning to the route
    // never publishes an empty placeholder or replays intermediate changes.
    val observed = collectAsState()
    val latest = observed.value
    SideEffect {
        if (initialized) {
            retained.value = latest
            hasSnapshot.value = true
        }
    }
    return observed
}
