package com.pixel.gallery.data.repository

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

/** Emit immediately, then merge rapid invalidations before the next full database read. */
internal fun <T> Flow<T>.coalesceMediaInvalidations(intervalMillis: Long = 150L): Flow<T> =
    conflate().transform { value ->
        emit(value)
        delay(intervalMillis)
    }
