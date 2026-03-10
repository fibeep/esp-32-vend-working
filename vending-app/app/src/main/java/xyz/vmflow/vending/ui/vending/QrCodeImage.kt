package xyz.vmflow.vending.ui.vending

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Composable that renders a string as a QR code image using ZXing.
 *
 * Generates a QR code bitmap from the provided [content] string and
 * displays it as an [Image] composable. Used to display the Yappy
 * payment QR code hash that customers scan with their Yappy app.
 *
 * @param content The string to encode as a QR code (e.g., Yappy hash).
 * @param size The display size of the QR code image.
 * @param modifier Optional Compose modifier.
 */
@Composable
fun QrCodeImage(
    content: String,
    size: Dp = 250.dp,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(content) {
        generateQrBitmap(content, 512)
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR Code",
            modifier = modifier.size(size)
        )
    }
}

/**
 * Generates a QR code [Bitmap] from a string using ZXing.
 *
 * @param content The string to encode.
 * @param sizePx The pixel size of the generated bitmap (square).
 * @return The QR code bitmap, or null on encoding failure.
 */
private fun generateQrBitmap(content: String, sizePx: Int): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        val bitMatrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints
        )

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(
                    x, y,
                    if (bitMatrix.get(x, y)) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE
                )
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
