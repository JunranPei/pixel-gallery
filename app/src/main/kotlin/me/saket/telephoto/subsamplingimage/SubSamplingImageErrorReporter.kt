package me.saket.telephoto.subsamplingimage

import java.io.IOException

interface SubSamplingImageErrorReporter {

  /** Called when loading of an [imageSource] fails. */
  fun onImageLoadingFailed(e: IOException, imageSource: SubSamplingImageSource) = Unit

  companion object {
    val NoOpInRelease = object : SubSamplingImageErrorReporter {
      override fun onImageLoadingFailed(e: IOException, imageSource: SubSamplingImageSource) {
        android.util.Log.e("SubSamplingImage", "Failed to load image from $imageSource", e)
      }
    }
  }
}
