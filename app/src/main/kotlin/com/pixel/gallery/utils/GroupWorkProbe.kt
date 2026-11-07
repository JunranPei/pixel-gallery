package com.pixel.gallery.utils

import com.pixel.gallery.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Passive counters, enabled only by debug instrumentation. Never keeps media or Views. */
internal object GroupWorkProbe {
    @Volatile var enabled = false
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    fun hit(name: String) {
        if (BuildConfig.DEBUG && enabled) counts.getOrPut(name) { AtomicLong() }.incrementAndGet()
    }
    fun reset() = counts.clear()
    fun snapshot(): Map<String, Long> = counts.mapValues { it.value.get() }.toSortedMap()
}
