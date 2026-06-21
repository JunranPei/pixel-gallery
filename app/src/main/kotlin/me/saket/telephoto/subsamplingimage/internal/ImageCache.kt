package me.saket.telephoto.subsamplingimage.internal

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.util.fastForEach
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import me.saket.telephoto.subsamplingimage.internal.ImageCache.LoadingState.InFlight
import me.saket.telephoto.subsamplingimage.internal.ImageCache.LoadingState.Loaded
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class ImageCache(
  private val decoder: ImageRegionDecoder,
  private val throttleEvery: Duration = 100.milliseconds,
) {
  private val visibleRegions = Channel<List<ImageRegionTile>>(capacity = 10)
  private val cachedImages = MutableStateFlow(emptyMap<ImageRegionTile, LoadingState>())
  private var listenJob: Job? = null

  // Thread-safe LRU cache for off-screen tiles (keeps up to 12 loaded tiles in memory)
  private val lruCache = object : LinkedHashMap<ImageRegionTile, Loaded>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ImageRegionTile, Loaded>?): Boolean {
      return size > 12
    }
  }

  private sealed interface LoadingState {
    data class Loaded(val painter: Painter) : LoadingState
    data class InFlight(val job: Job) : LoadingState
  }

  fun listen(scope: CoroutineScope) {
    android.util.Log.e("TileDiag", "ImageCache.listen(): cancelling old job=${listenJob?.hashCode()}, starting new")
    listenJob?.cancel()
    listenJob = scope.launch {
      android.util.Log.e("TileDiag", "ImageCache.listen(): coroutine started, consuming channel")
      visibleRegions.consumeAsFlow()
        .distinctUntilChanged()
        .throttleLatest(throttleEvery)  // In case the image is animating its zoom.
        .collect { tiles ->
          android.util.Log.e("TileDiag", "ImageCache.collect: received ${tiles.size} tiles, cachedImages has ${cachedImages.value.size} entries")
          val tilesToLoad = tiles.fastFilter { it !in cachedImages.value }
          tilesToLoad.fastForEach { tile ->
            val cachedLoaded = synchronized(lruCache) { lruCache.remove(tile) }
            if (cachedLoaded != null) {
              cachedImages.update {
                it + (tile to cachedLoaded)
              }
            } else {
              // CoroutineStart.UNDISPATCHED is used to ensure that the coroutines are executed
              // in the same order they were launched. Otherwise, the tiles may load in a different
              // order than what was requested. SubSamplingImageTest#draw_tile_under_centroid_first()
              // test will also become flaky.
              launch(start = CoroutineStart.UNDISPATCHED) {
                cachedImages.update {
                  if (tile in it) return@update it
                  it + (tile to InFlight(currentCoroutineContext().job))
                }
                val painter = try {
                  val result = decoder.decodeRegion(tile)
                  android.util.Log.e("TileDiag", "ImageCache: tile decoded OK - ${tile.bounds}")
                  result
                } catch (e: Exception) {
                  cachedImages.update { it - tile }
                  android.util.Log.e("TileDiag", "ImageCache: tile decode FAILED - ${tile.bounds}: ${e.message}")
                  return@launch
                }
                cachedImages.update {
                  if (tile in it && it[tile] is InFlight) {
                    it + (tile to Loaded(painter))
                  } else {
                    it
                  }
                }
              }
            }
          }

          val tilesToUnload = cachedImages.value.keys.filter { it !in tiles }
          tilesToUnload.fastForEach { region ->
            val state = cachedImages.value[region]
            if (state is InFlight) {
              state.job.cancel()
            } else if (state is Loaded) {
              synchronized(lruCache) {
                lruCache[region] = state
              }
            }
          }
          cachedImages.update { it - tilesToUnload.toSet() }
        }
      android.util.Log.e("TileDiag", "ImageCache.listen(): consumeAsFlow().collect ENDED (channel closed?)")
    }
  }

  fun observeCachedImages(): Flow<ImmutableMap<ImageRegionTile, Painter>> {
    return cachedImages.map { map ->
      buildMap(capacity = map.size) {
        map.forEach { (region, state) ->
          if (state is Loaded) {
            put(region, state.painter)
          }
        }
      }.toImmutableMap()
    }.distinctUntilChanged()
  }

  fun loadOrUnloadForTiles(regions: List<ImageRegionTile>) {
    val result = visibleRegions.trySend(regions)
    android.util.Log.e("TileDiag", "loadOrUnloadForTiles: trySend ${regions.size} regions, success=${result.isSuccess}, failure=${result.isFailure}, closed=${result.isClosed}")
  }

  // Copied from https://github.com/Kotlin/kotlinx.coroutines/issues/1446#issuecomment-1198103541.
  private fun <T> Flow<T>.throttleLatest(delay: Duration): Flow<T> {
    return conflate().transform {
      emit(it)
      delay(delay)
    }
  }
}
