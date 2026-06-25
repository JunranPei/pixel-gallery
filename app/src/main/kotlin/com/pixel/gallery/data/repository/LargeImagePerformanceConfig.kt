package com.pixel.gallery.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object LargeImagePerformanceConfig {
    @Volatile var tileSize: Int = 1024
    @Volatile var debounceMs: Int = 150
    @Volatile var useHardwareBitmap: Boolean = false
    @Volatile var maxCores: Int = 4

    private var executor: ExecutorService? = null
    
    @Volatile var decoderDispatcher: CoroutineDispatcher = Dispatchers.IO
        private set

    fun updateMaxCores(cores: Int) {
        synchronized(this) {
            if (executor != null && maxCores == cores) {
                return
            }
            maxCores = cores
            executor?.shutdown()
            val newExecutor = Executors.newFixedThreadPool(cores) { runnable ->
                Thread(runnable, "large-image-decoder").apply {
                    priority = Thread.NORM_PRIORITY
                }
            }
            executor = newExecutor
            decoderDispatcher = newExecutor.asCoroutineDispatcher()
        }
    }
}
