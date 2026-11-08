package online.myesstu.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.exifinterface.media.ExifInterface
import coil.ImageLoader
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream

/** Images come from the server and need the session header, so they use the app's own HTTP client. */
val LocalLoader = staticCompositionLocalOf<ImageLoader> { error("ImageLoader not provided") }

@Composable
fun RemoteImage(model: Any?, modifier: Modifier = Modifier) {
    AsyncImage(
        model = model,
        contentDescription = null,
        imageLoader = LocalLoader.current,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/** Shrinks a photo to a sane size and fixes its rotation before uploading. */
fun compressPhoto(context: Context, uri: Uri): ByteArray {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2200) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: error("decode failed")
    val orientation = resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees != 0f) {
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
    return out.toByteArray()
}

/** A person's picture, or the first letter of their name while it loads or when there is none. */
@Composable
fun Avatar(name: String, url: String, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val initial: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        imageLoader = LocalLoader.current,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = { initial() },
        error = { initial() },
    )
}
