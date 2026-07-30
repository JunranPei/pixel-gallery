package com.pixel.gallery.ui.viewer

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.pixel.gallery.ui.viewer.decoders.RawEmbeddedPreview
import com.pixel.gallery.ui.viewer.decoders.RawEmbeddedPreviewStore
import com.pixel.gallery.ui.viewer.formats.ViewerRegionDecoderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun RawEmbeddedPreviewViewer(
    uri: String,
    filePath: String,
    width: Int,
    height: Int,
    orientationDegrees: Int,
    dateModifiedMillis: Long,
    isActivePage: Boolean,
    isPreviewVisible: Boolean,
    transformStateStore: ViewerTransformStateStore,
    modifier: Modifier = Modifier,
    onContentReadyChanged: (Boolean) -> Unit = {},
    onClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val sourceUri = remember(uri, filePath) {
        filePath.takeIf { it.isNotEmpty() && File(it).isFile }
            ?.let { Uri.fromFile(File(it)) }
            ?: Uri.parse(uri)
    }
    val sourceKey = remember(sourceUri, dateModifiedMillis) { "$sourceUri:$dateModifiedMillis" }
    var preview by remember(sourceKey) { mutableStateOf<RawEmbeddedPreview?>(null) }
    var resolved by remember(sourceKey) { mutableStateOf(false) }

    LaunchedEffect(sourceKey, isActivePage) {
        if (!isActivePage || resolved) return@LaunchedEffect
        preview = withContext(Dispatchers.IO) {
            runCatching {
                RawEmbeddedPreviewStore.load(context.applicationContext, sourceUri, sourceKey)
            }.getOrNull()
        }
        resolved = true
    }

    when {
        preview != null -> SimpleSubsamplingImageView(
            uri = uri,
            filePath = filePath,
            orientationDegrees = orientationDegrees,
            modifier = modifier,
            isActivePage = isActivePage,
            isPreviewVisible = isPreviewVisible,
            enableSubsampling = true,
            dateModifiedMillis = dateModifiedMillis,
            previewModel = preview!!.bytes,
            regionDecoderKind = ViewerRegionDecoderKind.RAW_EMBEDDED,
            decoderSourceKey = sourceKey,
            transformStateStore = transformStateStore,
            onContentReadyChanged = onContentReadyChanged,
            onClick = onClick,
        )

        !isActivePage || resolved -> GlideViewerFallback(
            imagePath = filePath.ifEmpty { uri },
            width = width,
            height = height,
            orientationDegrees = orientationDegrees,
            dateModifiedMillis = dateModifiedMillis,
            isVisiblePage = isPreviewVisible,
            modifier = modifier,
            onContentReadyChanged = onContentReadyChanged,
            onClick = onClick,
        )

        else -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
