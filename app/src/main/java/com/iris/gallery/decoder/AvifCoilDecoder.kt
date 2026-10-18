package com.iris.gallery.decoder

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import org.aomedia.avif.android.AvifDecoder
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

class AvifCoilDecoder(
    private val source: ImageSource,
    private val options: Options
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val bytes = source.source().readByteArray()
        if (bytes.isEmpty()) return null

        val byteBuffer = ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        }

        if (!AvifDecoder.isAvifImage(byteBuffer)) {
            return null
        }

        val info = AvifDecoder.Info()
        if (!AvifDecoder.getInfo(byteBuffer, bytes.size, info)) {
            return null
        }
        if (info.width <= 0 || info.height <= 0) {
            return null
        }

        // AvifDecoder scales the decoded image into whatever bitmap it is handed, so
        // allocate only what the request needs instead of a full-size buffer.
        val requestedWidth = options.size.width.pxOrElse { info.width }
        val requestedHeight = options.size.height.pxOrElse { info.height }
        val needsScale = requestedWidth < info.width || requestedHeight < info.height
        val ratio = if (needsScale) {
            min(requestedWidth.toFloat() / info.width, requestedHeight.toFloat() / info.height)
        } else {
            1f
        }
        val targetWidth = (info.width * ratio).roundToInt().coerceIn(1, info.width)
        val targetHeight = (info.height * ratio).roundToInt().coerceIn(1, info.height)

        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        if (!AvifDecoder.decode(byteBuffer, bytes.size, bitmap)) {
            bitmap.recycle()
            return null
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = targetWidth != info.width || targetHeight != info.height,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader
        ): Decoder? {
            val mimeType = result.mimeType
            if (mimeType != null && mimeType.equals("image/avif", ignoreCase = true)) {
                return AvifCoilDecoder(result.source, options)
            }
            val source = result.source.source()
            if (!source.request(16)) return null
            val head = source.buffer.peek()
            val boxSize = head.readInt()
            if (head.readByteString(4).utf8() != "ftyp") return null
            // The major brand alone is not enough: some AVIF writers leave 'mif1' in the
            // major slot and advertise 'avif' only as a compatible brand, while real HEIF
            // files carry no AVIF brand at all and should go to the platform decoder.
            val ftypSize = boxSize.coerceIn(16, 128)
            if (!source.request(ftypSize.toLong())) return null
            val ftyp = source.buffer.peek().readByteString(ftypSize.toLong())
            var offset = 8
            while (offset + 4 <= ftyp.size) {
                val brand = ftyp.substring(offset, offset + 4).utf8()
                if (brand == "avif" || brand == "avis") {
                    return AvifCoilDecoder(result.source, options)
                }
                offset += 4
            }
            return null
        }
    }
}
