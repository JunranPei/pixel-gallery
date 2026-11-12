package com.pixel.gallery.ui.viewer

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** No recorder, queue, preference read or worker exists until recording is explicitly started. */
internal object ViewerMemoryDiagnostics {
    @Volatile private var recorder: Recorder? = null
    val isEnabled: Boolean get() = recorder != null
    @Volatile var generation = 0L
        private set

    @Synchronized fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled && recorder == null) {
            generation++
            recorder = Recorder(File(context.applicationContext.filesDir, "viewer-memory-diagnostics"))
            event("DIAGNOSTIC_START")
        } else if (!enabled) {
            val previous = recorder ?: return
            previous.event("DIAGNOSTIC_STOP", "", null)
            recorder = null
            previous.close()
        }
    }

    fun event(name: String, detail: String = "", imageKey: String? = null) {
        recorder?.event(name, detail, imageKey)
    }
    fun flush(): Boolean = recorder?.flush() ?: true
    internal fun hasRecorderForTest(): Boolean = recorder != null
    fun shouldRecordRead(store: Any, key: String, source: String, revision: Int): Boolean =
        recorder?.allowRead(System.identityHashCode(store),"$key:$source:$revision") ?: false

    private class Recorder(private val directory: File) {
        private val reads = LinkedHashMap<Int,String>()
        @Synchronized fun allowRead(store: Int, token: String): Boolean {
            if (reads[store] == token) return false
            reads[store] = token
            while (reads.size > 128) reads.entries.iterator().run { next(); remove() }
            return true
        }
        private val dropped = AtomicInteger()
        private val writer = ThreadPoolExecutor(0, 1, 2L, TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable>(1024),
            { task -> Thread(task, "viewer-memory-log").apply { priority = Thread.MIN_PRIORITY } },
            { _, _ -> dropped.incrementAndGet() })

        fun event(name: String, detail: String, key: String?) {
            val line = "EVENT name=$name clockNanos=${SystemClock.elapsedRealtimeNanos()} " +
                "pid=${Process.myPid()} wallMillis=${System.currentTimeMillis()} " +
                "key=${key ?: "none"} thread=${Thread.currentThread().name} $detail"
            Log.i("ViewerMemoryDiagnostics", line)
            writer.execute {
                try {
                    if (!directory.isDirectory && !directory.mkdirs()) return@execute
                    val file = File(directory, "current.log")
                    if (file.length() >= 4L * 1024L * 1024L) {
                        val previous = File(directory, "previous.log")
                        if ((previous.exists() && !previous.delete()) || !file.renameTo(previous)) return@execute
                    }
                    val lost = dropped.getAndSet(0)
                    file.appendText((if (lost > 0) "DIAGNOSTIC_DROPPED count=$lost\n" else "")+line+"\n")
                } catch (error: Exception) {
                    Log.w("ViewerMemoryDiagnostics", "Diagnostic write failed", error)
                }
            }
        }
        fun flush(): Boolean {
            val done = CountDownLatch(1)
            writer.execute { done.countDown() }
            return done.await(2000, TimeUnit.MILLISECONDS)
        }
        fun close() { writer.shutdown() }
    }
}
