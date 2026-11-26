package com.aceinandroid.inkflow.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aceinandroid.inkflow.R

@Composable
fun ImageViewerDialog(bitmap: Bitmap, onDismiss: () -> Unit) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(bitmap) {
            detectTransformGestures { _, drag, scale, _ ->
                zoom = (zoom * scale).coerceIn(1f, 6f)
                pan = if (zoom == 1f) Offset.Zero else Offset(
                    (pan.x + drag.x).coerceIn(-size.width * (zoom - 1) / 2, size.width * (zoom - 1) / 2),
                    (pan.y + drag.y).coerceIn(-size.height * (zoom - 1) / 2, size.height * (zoom - 1) / 2),
                )
            }
        }) {
            Image(bitmap.asImageBitmap(), stringResource(R.string.migration_image), Modifier.fillMaxSize()
                .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y },
                contentScale = ContentScale.Fit)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Text(stringResource(R.string.close), color = Color.White)
            }
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                Text(stringResource(R.string.migration_reset_zoom), color = Color.White)
            }
        }
    }
}

/** Zoom is page-local. At 1×, edge taps still navigate; while zoomed, taps only reveal chrome. */
@Composable
internal fun NativePdfPage(
    bitmap: Bitmap,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleChrome: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Box(modifier.background(Color(0xFFE7E7E7)).clipToBounds().pointerInput(bitmap) {
        detectTransformGestures { _, drag, scale, _ ->
            zoom = (zoom * scale).coerceIn(1f, 6f)
            pan = if (zoom == 1f) Offset.Zero else Offset(
                (pan.x + drag.x).coerceIn(-size.width * (zoom - 1) / 2, size.width * (zoom - 1) / 2),
                (pan.y + drag.y).coerceIn(-size.height * (zoom - 1) / 2, size.height * (zoom - 1) / 2),
            )
        }
    }.pointerInput(bitmap) {
        detectTapGestures(
            onTap = { point ->
                when {
                    zoom > 1f -> onToggleChrome()
                    point.x < size.width * 0.28f -> onPrevious()
                    point.x > size.width * 0.72f -> onNext()
                    else -> onToggleChrome()
                }
            },
            onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f; pan = Offset.Zero },
            onLongPress = { onExpand() },
        )
    }) {
        Image(bitmap.asImageBitmap(), stringResource(R.string.migration_pdf_zoom_hint), Modifier.fillMaxSize()
            .padding(8.dp).graphicsLayer {
                scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
            }, contentScale = ContentScale.Fit)
        if (zoom > 1f) TextButton(onClick = { zoom = 1f; pan = Offset.Zero },
            modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.7f))) {
            Text(stringResource(R.string.migration_reset_zoom), color = Color.White)
        }
    }
}
