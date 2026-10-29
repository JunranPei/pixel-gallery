package com.pixel.gallery.ui.viewer

import com.bumptech.glide.request.BaseRequestOptions
import com.bumptech.glide.load.engine.executor.PixelGlideExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A viewing session shares a bounded burst window for source and cache work.
 * Do not bypass that window with Glide's unbounded source executor.
 */
internal fun <T : BaseRequestOptions<T>> T.withViewerTaskCompression(): T =
    useUnlimitedSourceGeneratorsPool(false)

/** One window per visible viewer; supports two windows and initialization after enter. */
internal class ViewerTaskWindow {
    private var viewers = 0
    private var source: ThreadPoolExecutor? = null
    private var disk: ThreadPoolExecutor? = null
    private var configuredSourceThreads = 1

    @Synchronized
    fun attach(source: ThreadPoolExecutor, disk: ThreadPoolExecutor, configuredThreads: Int) {
        this.source = source; this.disk = disk
        configuredSourceThreads = configuredThreads.coerceIn(1, 8)
        update()
    }

    @Synchronized
    fun enter(): AutoCloseable {
        viewers++
        update()
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(this) {
                viewers--
                update()
            }
        }
    }

    private fun resize(pool: ThreadPoolExecutor?, count: Int) {
        if (pool == null || pool.isShutdown) return
        // Growing max first and shrinking core first keeps both setters legal.
        // Shrinking max also retires excess workers after their current task;
        // no decoded resource is interrupted or discarded at the screen boundary.
        if (count > pool.maximumPoolSize) pool.maximumPoolSize = count
        pool.corePoolSize = count
        pool.maximumPoolSize = count
    }

    private fun update() {
        resize(source, if (viewers > 0) maxOf(4, configuredSourceThreads) else configuredSourceThreads)
        resize(disk, if (viewers > 0) 3 else 1)
    }
}

internal object ViewerGlideTaskCompression {
    private val window = ViewerTaskWindow()

    fun executors(configuredThreads: Int): Pair<com.bumptech.glide.load.engine.executor.GlideExecutor,
        com.bumptech.glide.load.engine.executor.GlideExecutor> {
        val source = PixelGlideExecutor.pool(configuredThreads, "source-configured", false)
        val disk = PixelGlideExecutor.pool(1, "disk-cache-configured", true)
        window.attach(source, disk, configuredThreads)
        return PixelGlideExecutor.wrap(source) to PixelGlideExecutor.wrap(disk)
    }

    fun enter(): AutoCloseable = window.enter()
}
