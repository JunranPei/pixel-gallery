@file:Suppress("NAME_SHADOWING")
@file:OptIn(ExperimentalCoroutinesApi::class)

package me.saket.telephoto.subsamplingimage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import me.saket.telephoto.subsamplingimage.internal.ExifMetadata
import me.saket.telephoto.subsamplingimage.internal.ImageRegionDecoder
import me.saket.telephoto.subsamplingimage.internal.LocalImageRegionDecoderFactory
import me.saket.telephoto.subsamplingimage.internal.PooledImageRegionDecoder
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableContentTransformation
import me.saket.telephoto.zoomable.ZoomableState
import java.io.IOException

/**
 * Create a [SubSamplingImageState] that can be used with [SubSamplingImage] which uses
 * [Modifier.zoomable][me.saket.telephoto.zoomable.zoomable] as its gesture detector.
 *
 * ```kotlin
 * val zoomableState = rememberZoomableState()
 * val imageState = rememberSubSamplingImageState(
 *   zoomableState = zoomableState,
 *   imageSource = SubSamplingImageSource.asset("fox.jpg")
 * )
 *
 * SubSamplingImage(
 *   modifier = Modifier
 *     .fillMaxSize()
 *     .zoomable(zoomableState),
 *   state = imageState,
 *   contentDescription = …,
 * )
 * ```
 */
@Composable
fun rememberSubSamplingImageState(
  imageSource: SubSamplingImageSource,
  zoomableState: ZoomableState,
  imageOptions: ImageBitmapOptions = ImageBitmapOptions.Default,
  errorReporter: SubSamplingImageErrorReporter = SubSamplingImageErrorReporter.NoOpInRelease
): SubSamplingImageState {
  val state = rememberSubSamplingImageState(
    imageSource = imageSource,
    transformation = { zoomableState.contentTransformation },
    imageOptions = imageOptions,
    errorReporter = errorReporter,
  )

  // SubSamplingImage will apply the transformations on its own.
  DisposableEffect(zoomableState) {
    val previousValue = zoomableState.autoApplyTransformations
    zoomableState.autoApplyTransformations = false
    onDispose {
      zoomableState.autoApplyTransformations = previousValue
    }
  }

  zoomableState.setContentLocation(
    ZoomableContentLocation.unscaledAndTopLeftAligned(state.imageSize?.toSize())
  )
  return state
}

@Composable
internal fun rememberSubSamplingImageState(
  imageSource: SubSamplingImageSource,
  transformation: () -> ZoomableContentTransformation,
  imageOptions: ImageBitmapOptions = ImageBitmapOptions.Default,
  errorReporter: SubSamplingImageErrorReporter = SubSamplingImageErrorReporter.NoOpInRelease,
): SubSamplingImageState {
  val transformation by rememberUpdatedState(transformation)
  val state = remember(imageSource) {
    RealSubSamplingImageState(imageSource, transformation)
  }
  state.currentSource = imageSource
  val decoder = createImageRegionDecoder(imageSource, imageOptions, errorReporter)
  state.imageRegionDecoder = decoder

  state.LoadImageTilesEffect()
  DisposableEffect(imageSource) {
    onDispose {
      imageSource.close()
    }
  }
  return state
}

@Composable
private fun createImageRegionDecoder(
  imageSource: SubSamplingImageSource,
  imageOptions: ImageBitmapOptions,
  errorReporter: SubSamplingImageErrorReporter
): ImageRegionDecoder? {
  val context = LocalContext.current
  val errorReporter by rememberUpdatedState(errorReporter)
  var decoder by remember(imageSource) { mutableStateOf<ImageRegionDecoder?>(null) }

  if (!LocalInspectionMode.current) {
    val localFactory = LocalImageRegionDecoderFactory.current
    LaunchedEffect(imageSource) {
      val startTime = System.nanoTime()
      android.util.Log.e("ImageLoadFlow", "[Start] createImageRegionDecoder launched for source = $imageSource")
      try {
        val exifStartTime = System.nanoTime()
        val exif = ExifMetadata.read(context, imageSource)
        val exifDuration = (System.nanoTime() - exifStartTime) / 1_000_000.0
        android.util.Log.e("ImageLoadFlow", "[Exif] Read Exif for source = $imageSource took $exifDuration ms")

        val decoderStartTime = System.nanoTime()
        val createdDecoder = PooledImageRegionDecoder.Factory(localFactory).create(
          ImageRegionDecoder.FactoryParams(
            context = context,
            imageSource = imageSource,
            imageOptions = imageOptions,
            exif = exif,
          )
        )
        val decoderDuration = (System.nanoTime() - decoderStartTime) / 1_000_000.0
        android.util.Log.e("ImageLoadFlow", "[DecoderInit] Created PooledImageRegionDecoder for source = $imageSource took $decoderDuration ms")

        decoder = createdDecoder
        val totalDuration = (System.nanoTime() - startTime) / 1_000_000.0
        android.util.Log.e("ImageLoadFlow", "[Success] createImageRegionDecoder completed for source = $imageSource, total = $totalDuration ms")
      } catch (e: IOException) {
        android.util.Log.e("ImageLoadFlow", "[Error] createImageRegionDecoder failed for source = $imageSource", e)
        errorReporter.onImageLoadingFailed(e, imageSource)
      }
    }
    DisposableEffect(imageSource) {
      android.util.Log.e("ImageLoadFlow", "[Lifecycle] DisposableEffect entered for source = $imageSource")
      onDispose {
        val closeStartTime = System.nanoTime()
        decoder?.close()
        decoder = null
        val closeDuration = (System.nanoTime() - closeStartTime) / 1_000_000.0
        android.util.Log.e("ImageLoadFlow", "[Lifecycle] Closed decoder for source = $imageSource, took $closeDuration ms")
      }
    }
  }

  return decoder
}

/** State for [SubSamplingImage]. */
@Stable
sealed interface SubSamplingImageState {
  /** Raw size of the image, without any scaling applied. */
  val imageSize: IntSize?

  /**
   * Whether the image is loaded and displayed, but not necessarily in its full quality.
   *
   * Also see [isImageDisplayedInFullQuality].
   */
  val isImageDisplayed: Boolean

  /** Whether the image is loaded and displayed in its full quality. */
  val isImageDisplayedInFullQuality: Boolean

  @Deprecated("Use isImageDisplayed instead", ReplaceWith("isImageDisplayed"))
  val isImageLoaded: Boolean get() = isImageDisplayed

  @Deprecated("Renamed to isImageDisplayedInFullQuality", ReplaceWith("isImageDisplayedInFullQuality"))
  val isImageLoadedInFullQuality: Boolean get() = isImageDisplayedInFullQuality
}
