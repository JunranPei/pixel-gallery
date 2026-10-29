package com.pixel.gallery.integration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import com.bumptech.glide.Glide
import com.bumptech.glide.load.Option
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import com.pixel.gallery.glide.NativeJpegFit
import com.pixel.gallery.ui.viewer.ViewerGlideTaskCompression
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.Priority
import com.bumptech.glide.signature.ObjectKey
import java.util.concurrent.atomic.AtomicBoolean
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ExecutionException

/** Actual Glide resource files; controlled cache-decode delay measures scheduling only. */
internal class ViewerCompressionDeviceChecks(private val context: Context) {
    private data class BurstSource(val id: String)
    private val delay = Option.memory("pixel.tests.cacheDecodeDelay", 0L)
    private val active = AtomicInteger()
    private val peak = AtomicInteger()
    private val calls = AtomicInteger()
    private val names = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var hold: CountDownLatch? = null
    @Volatile private var entered: CountDownLatch? = null

    fun exercise(): String {
        val glide = Glide.with(context)
        val singleton = Glide.get(context)
        singleton.registry.prepend(ByteBuffer::class.java, Bitmap::class.java,
            object : ResourceDecoder<ByteBuffer, Bitmap> {
                override fun handles(source: ByteBuffer, options: Options) = options.get(delay)!! > 0
                override fun decode(source: ByteBuffer, width: Int, height: Int, options: Options): Resource<Bitmap>? {
                    val count = active.incrementAndGet()
                    peak.accumulateAndGet(count, ::maxOf)
                    calls.incrementAndGet(); names += Thread.currentThread().name
                    entered?.countDown()
                    try {
                        hold?.let { check(it.await(5, TimeUnit.SECONDS)) }
                        SystemClock.sleep(options.get(delay)!!)
                        val data = source.duplicate().let { copy -> ByteArray(copy.remaining()).also(copy::get) }
                        return BitmapFactory.decodeByteArray(data, 0, data.size)?.let {
                            BitmapResource.obtain(it, singleton.bitmapPool)
                        }
                    } finally { active.decrementAndGet() }
                }
            })
        val version = System.currentTimeMillis()
        val base = Uri.parse("content://${context.packageName}.test.thumbnailquality")
        val models = listOf("tiny", "odd", "adequate").map {
            NativeJpegFit(base.buildUpon().appendPath(it).appendQueryParameter("burst", "$version").build(), 0, version)
        }
        fun request(index: Int, viewer: Boolean, controlled: Boolean = true) =
            glide.asBitmap().load(models[index]).override(128).fitCenter()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE).skipMemoryCache(true)
                .useUnlimitedSourceGeneratorsPool(false)
                .onlyRetrieveFromCache(controlled)
                .set(delay, if (controlled) 150L else 0L)

        // Populate three actual resource files, waiting for each encoder commit.
        for (i in models.indices) {
            val first = request(i, true, false).submit()
            try { check(first.get(20, TimeUnit.SECONDS).width == 128) }
            finally { glide.clear(first) }
            val deadline = SystemClock.elapsedRealtime() + 10000
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                val cached = request(i, true).set(delay, 0L).submit()
                try { cached.get(5, TimeUnit.SECONDS); ready = true }
                catch (_: ExecutionException) { SystemClock.sleep(10) }
                finally { glide.clear(cached) }
            }
            check(ready) { "Fixture resource cache not ready" }
        }
        fun sourceReads() = context.contentResolver.call(base, "counts", null, null)!!.getInt("originalReads")
        val originalReads = sourceReads()
        val report = mutableListOf<String>()
        fun reset() {
            val deadline = SystemClock.elapsedRealtime() + 1000
            while (active.get() > 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(1)
            check(active.get() == 0); peak.set(0); calls.set(0); names.clear()
        }
        fun batch(viewers: List<Boolean>): Long {
            reset()
            val window = if (viewers.any { it }) ViewerGlideTaskCompression.enter() else null
            val start = SystemClock.elapsedRealtime()
            val targets = viewers.mapIndexed { i, viewer -> request(i % 3, viewer).submit() }
            try { targets.forEach { check(it.get(15, TimeUnit.SECONDS).width == 128) } }
            finally { targets.forEach(glide::clear); window?.close() }
            check(sourceReads() == originalReads) { "Cache test reopened original" }
            return SystemClock.elapsedRealtime() - start
        }
        val serial = batch(listOf(false, false, false))
        check(peak.get() == 1 && calls.get() == 3) { "Grid concurrency changed" }
        report += "shared cache: peak=1 batchMs=$serial threads=${names.toSet()}"
        val parallel = batch(listOf(true, true, true))
        check(peak.get() == 3 && calls.get() == 3) { "Viewer did not overlap 3 cache decodes" }
        report += "viewer cache: peak=3 batchMs=$parallel threads=${names.toSet()} (150ms controlled work per task)"

        val firstWindow = ViewerGlideTaskCompression.enter()
        val secondWindow = ViewerGlideTaskCompression.enter()
        try {
            firstWindow.close(); firstWindow.close()
            batch(listOf(true, true, true)); check(peak.get() == 3)
        } finally { firstWindow.close(); secondWindow.close() }
        batch(listOf(false, false, false)); check(peak.get() == 1)
        report += "two viewer windows: burst retained until last close, then cache concurrency restored to 1"

        reset()
        val joined = listOf(request(0, true).submit(), request(0, true).submit())
        try {
            check(joined[0].get(10, TimeUnit.SECONDS) === joined[1].get(10, TimeUnit.SECONDS))
            check(calls.get() == 1)
        } finally { joined.forEach(glide::clear) }
        report += "identical requests: one decode and one shared bitmap"

        reset(); hold = CountDownLatch(1); entered = CountDownLatch(1)
        val running = request(0, false).submit()
        try {
            check(entered!!.await(5, TimeUnit.SECONDS))
            val cancelled = request(1, false).submit()
            glide.clear(cancelled)
            hold!!.countDown()
            running.get(10, TimeUnit.SECONDS)
            val after = request(2, false).submit()
            try { after.get(10, TimeUnit.SECONDS) } finally { glide.clear(after) }
            check(calls.get() == 2) { "Cancelled queued request decoded" }
        } finally { hold?.countDown(); hold = null; entered = null; glide.clear(running) }
        report += "queued cancellation: skipped; following cache decode completed"

        val absent = glide.asBitmap().load(models[0].copy(modified = version + 100))
            .override(128).onlyRetrieveFromCache(true).useUnlimitedSourceGeneratorsPool(false).submit()
        try { absent.get(5, TimeUnit.SECONDS); error("Expected cache-only miss") }
        catch (_: ExecutionException) { }
        finally { glide.clear(absent) }
        val recovered = request(0, true).submit()
        try { check(recovered.get(10, TimeUnit.SECONDS).width == 128) }
        finally { glide.clear(recovered) }
        check(sourceReads() == originalReads)
        report += "cache-only failure recovered; scheduling tests made zero original reads"

        val png = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).let {
                it.eraseColor(android.graphics.Color.BLUE)
                check(it.compress(Bitmap.CompressFormat.PNG, 100, out)); it.recycle()
            }
        }.toByteArray()
        singleton.registry.prepend(BurstSource::class.java, ByteBuffer::class.java,
            object : ModelLoaderFactory<BurstSource, ByteBuffer> {
                override fun teardown() = Unit
                override fun build(multiFactory: MultiModelLoaderFactory) = object : ModelLoader<BurstSource, ByteBuffer> {
                    override fun handles(model: BurstSource) = true
                    override fun buildLoadData(model: BurstSource, width: Int, height: Int, options: Options) =
                        ModelLoader.LoadData(ObjectKey(model.id), object : DataFetcher<ByteBuffer> {
                            private val cancelled = AtomicBoolean()
                            override fun getDataClass() = ByteBuffer::class.java
                            override fun getDataSource() = DataSource.LOCAL
                            override fun cancel() { cancelled.set(true) }
                            override fun cleanup() = Unit
                            override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in ByteBuffer>) {
                                if (cancelled.get()) return
                                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                                calls.incrementAndGet(); names += Thread.currentThread().name
                                try {
                                    SystemClock.sleep(150)
                                    if (!cancelled.get()) callback.onDataReady(ByteBuffer.wrap(png))
                                } finally { active.decrementAndGet() }
                            }
                        })
                }
            })
        val configured = kotlinx.coroutines.runBlocking {
            com.pixel.gallery.data.repository.SettingsRepository(context).glideThreadCount
                .first()
        }.coerceIn(1, 8)
        fun combined(viewer: Boolean, oldUnbounded: Boolean = false): Long {
            reset()
            val window = if (viewer) ViewerGlideTaskCompression.enter() else null
            val start = SystemClock.elapsedRealtime()
            val cacheTasks = (0..2).map { request(it, viewer).submit() }
            val sourceTasks = (0..4).map { index -> glide.asBitmap()
                .load(BurstSource("$version-$viewer-$oldUnbounded-$index"))
                .override(64, 48).diskCacheStrategy(DiskCacheStrategy.NONE)
                .useUnlimitedSourceGeneratorsPool(oldUnbounded).skipMemoryCache(true).submit() }
            try {
                cacheTasks.forEach { check(it.get(15, TimeUnit.SECONDS).width == 128) }
                sourceTasks.forEach { check(it.get(15, TimeUnit.SECONDS).getPixel(0, 0) == android.graphics.Color.BLUE) }
            } finally { (cacheTasks + sourceTasks).forEach(glide::clear); window?.close() }
            check(calls.get() == 8)
            val expected = if (viewer) minOf(5, maxOf(4, configured)) + 3
                else if (oldUnbounded) 6 else minOf(5, configured) + 1
            check(peak.get() == expected) { "Combined concurrency=${peak.get()} expected=$expected threads=$names" }
            return SystemClock.elapsedRealtime() - start
        }
        val combinedNormal = combined(false)
        val combinedOld = combined(false, true)
        val combinedBurst = combined(true)
        report += "whole batch (5 source + 3 cache, 150ms controlled work each): normal=${combinedNormal}ms oldViewer=${combinedOld}ms " +
            "burst=${combinedBurst}ms sourceLimit=${maxOf(4, configured)} cacheLimit=3"
        return report.joinToString("\n")
    }
}
