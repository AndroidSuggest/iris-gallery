package com.iris.gallery.decoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import com.iris.gallery.data.MediaImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import android.util.Base64

/**
 * Parsed depth-photo data extracted from a JPEG that follows the Google "GDepth" depth-map
 * convention (used by Nokia 7.2, many Android OEM portrait cameras, and Google Photos).
 *
 * The depth photo stores three pieces inside a single JPEG:
 *  1. The primary (sharp) image — the visible pixels when the file is opened normally.
 *  2. A **depth map** — a grayscale JPEG (typically ~¼ resolution) where each pixel encodes
 *     the distance to the nearest surface, quantised 0–255. [format] tells whether 255 means
 *     "nearest" ([RangeInverse]) or "farthest" ([RangeLinear]).
 *  3. A **blurred background image** (GImage) — a full-resolution JPEG pre-blurred by the
 *     camera ISP. The viewer blends sharp↔blurred through the depth map to synthesise the
 *     portrait/bokeh look.
 *
 * The blending formula is:
 * ```
 *   blurAlpha = clamp((depthValue - focusDepth) / range, 0, 1) * bokehStrength
 *   output = lerp(sharp, blurred, blurAlpha)
 * ```
 * Where `focusDepth` is the depth value the user wants in focus and `range` controls the
 * transition width.
 *
 * @property depthMap     Decoded grayscale depth-map bitmap, or null if absent.
 * @property blurredImage Decoded full-res blurred background bitmap, or null if absent.
 * @property format       "RangeInverse" (255=near) or "RangeLinear" (255=far).
 * @property nearMm       Near depth in millimetres (only informative; the 0–255 pixel range
 *                         already normalises this).
 * @property farMm        Far depth in millimetres.
 * @property bokehLevel   OEM-suggested bokeh strength (0–100), used as default slider value.
 * @property focusX       Normalised focus-point X (0–1), 0 if not set.
 * @property focusY       Normalised focus-point Y (0–1), 0 if not set.
 */
data class DepthPhoto(
    val depthMap: Bitmap?,
    val blurredImage: Bitmap?,
    val format: String,
    val nearMm: Float,
    val farMm: Float,
    val bokehLevel: Float,
    val focusX: Float,
    val focusY: Float,
) {
    /** Whether 255 in the depth map means "nearest" (default for Google/Nokia). */
    val isRangeInverse: Boolean
        get() = format.equals("RangeInverse", ignoreCase = true)
}

/**
 * Extracts Google-format depth data ([DepthPhoto]) from a JPEG file.
 *
 * The depth map and blurred background are stored as base64-encoded JPEGs inside the XMP
 * metadata block. When the XMP is too large for a single APP1 segment, Adobe's Extended XMP
 * convention splits it across many APP1 segments (each up to 65 507 bytes) with a 32-byte
 * GUID, a 4-byte total-size, and a 4-byte offset header. This extractor reassembles those
 * chunks, finds the `GDepth:Data` and `GImage:Data` attributes, and decodes them.
 *
 * Nokia 7.2 specifically produces:
 *  - Depth map: 1000×750 grayscale JPEG (~28 KB)
 *  - Blurred GImage: 4000×3000 colour JPEG (~4 MB)
 *  - `GDepth:Format="RangeInverse"`, `GDepth:Near="20.0"`, `GDepth:Far="800.0"`
 *  - `GBokeh:BokehLevel="50"`, `GCamera:SpecialTypeID="PORTRAIT_TYPE"`
 *
 * All I/O runs synchronously; callers must use [kotlinx.coroutines.Dispatchers.IO].
 */
object DepthPhotoExtractor {

    // JPEG markers.
    private const val MARKER = 0xFF
    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val APP1 = 0xE1
    private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    // XMP identifiers inside an APP1 payload.
    private val XMP_STD = "<x:xmpmeta".toByteArray(Charsets.US_ASCII)
    private val XMP_EXT_MARKER = "http://ns.adobe.com/xmp/extension/\u0000".toByteArray(Charsets.US_ASCII)

    // Base64 of a JPEG SOI: "/9j/" — quick sanity check on decoded depth data.
    private val B64_JPEG_SOI = "/9j/".toByteArray(Charsets.US_ASCII)

    private val cache = LruCache<Long, DepthPhoto>(8)

    /**
     * Extract [DepthPhoto] for [image]. Returns null if the image has no GDepth data.
     * Results are cached per [MediaImage.id].
     */
    fun detectDepthPhoto(context: Context, image: MediaImage): DepthPhoto? {
        cache.get(image.id)?.let { return it }
        val result = runCatching { extract(context, image) }.getOrNull()
        if (result != null) cache.put(image.id, result)
        return result
    }

    /** Drop the cached entry for [imageId]. */
    fun evict(imageId: Long) {
        cache.remove(imageId)
    }

    /** Clear the whole cache. */
    fun clearCache() {
        cache.evictAll()
    }

    private fun extract(context: Context, image: MediaImage): DepthPhoto? {
        val directFile = if (image.path.isNotBlank()) File(image.path) else null
        val useDirectFile = directFile != null && directFile.exists() && directFile.canRead()

        // Read the raw XMP (standard + reassembled extended) from the JPEG APP1 segments.
        val fullXmp = readFullXmp(directFile, useDirectFile, context, image) ?: return null

        // Must have GDepth:Data to be a depth photo.
        val depthB64 = extractAttr(fullXmp, "GDepth:Data") ?: return null
        val gImageB64 = extractAttr(fullXmp, "GImage:Data")

        val format = extractAttr(fullXmp, "GDepth:Format") ?: "RangeInverse"
        val nearMm = extractAttr(fullXmp, "GDepth:Near")?.toFloatOrNull() ?: 0f
        val farMm = extractAttr(fullXmp, "GDepth:Far")?.toFloatOrNull() ?: 0f
        val bokehLevel = extractAttr(fullXmp, "GBokeh:BokehLevel")?.toFloatOrNull() ?: 50f
        val focusX = extractAttr(fullXmp, "GFocus:FocusX")?.toFloatOrNull() ?: 0f
        val focusY = extractAttr(fullXmp, "GFocus:FocusY")?.toFloatOrNull() ?: 0f

        val depthMap = decodeBase64Jpeg(depthB64)
        val blurredImage = gImageB64?.let { decodeBase64Jpeg(it) }

        if (depthMap == null && blurredImage == null) return null

        return DepthPhoto(
            depthMap = depthMap,
            blurredImage = blurredImage,
            format = format,
            nearMm = nearMm,
            farMm = farMm,
            bokehLevel = bokehLevel,
            focusX = focusX,
            focusY = focusY,
        )
    }

    // ------------------------------------------------------------------
    // XMP reading (standard + extended reassembly)
    // ------------------------------------------------------------------

    /**
     * Reads all APP1 segments from the JPEG and reassembles the full XMP:
     * the standard `<x:xmpmeta>…</x:xmpmeta>` block plus the extended XMP chunks.
     */
    private fun readFullXmp(
        directFile: File?,
        useDirectFile: Boolean,
        context: Context,
        image: MediaImage,
    ): String? {
        val app1Segments = collectApp1Segments(directFile, useDirectFile, context, image)
        if (app1Segments.isEmpty()) return null

        var standardXmp: String? = null
        val extChunks = HashMap<Int, ByteArray>()  // offset → chunk
        var extTotalSize = 0

        for (seg in app1Segments) {
            when {
                seg.startsWith(XMP_STD, 0) || indexOf(seg, XMP_STD, 0) in 0..64 -> {
                    if (standardXmp == null) {
                        val start = indexOf(seg, XMP_STD, 0)
                        val end = indexOf(seg, "</x:xmpmeta>".toByteArray(), start)
                        standardXmp = if (end > start) {
                            String(seg, start, end - start + "</x:xmpmeta>".length, Charsets.UTF_8)
                        } else {
                            String(seg, start, seg.size - start, Charsets.UTF_8)
                        }
                    }
                }
                seg.size >= 75 && seg.startsWith(XMP_EXT_MARKER) -> {
                    // Extended XMP header: 35-byte marker + 32-byte GUID + 4-byte total + 4-byte offset + data
                    val total = readUInt32BE(seg, 67)
                    val offset = readUInt32BE(seg, 71)
                    if (extTotalSize == 0) extTotalSize = total
                    if (offset >= 0 && offset + (seg.size - 75) <= total) {
                        extChunks[offset] = seg.copyOfRange(75, seg.size)
                    }
                }
            }
        }

        if (standardXmp == null) return null

        // Insert extended XMP just before the closing </x:xmpmeta> tag.
        if (extChunks.isNotEmpty()) {
            val sb = StringBuilder(standardXmp.length + extTotalSize + 16)
            val closeTag = "</x:xmpmeta>"
            val insertPos = standardXmp.lastIndexOf(closeTag)
            if (insertPos >= 0) {
                sb.append(standardXmp, 0, insertPos)
                for (off in extChunks.keys.sorted()) {
                    sb.append(String(extChunks[off]!!, Charsets.UTF_8))
                }
                sb.append(standardXmp, insertPos, standardXmp.length)
            } else {
                sb.append(standardXmp)
                for (off in extChunks.keys.sorted()) {
                    sb.append(String(extChunks[off]!!, Charsets.UTF_8))
                }
            }
            return sb.toString()
        }
        return standardXmp
    }

    /** Scans the JPEG stream and collects the raw payload bytes of every APP1 marker. */
    private fun collectApp1Segments(
        directFile: File?,
        useDirectFile: Boolean,
        context: Context,
        image: MediaImage,
    ): List<ByteArray> {
        val segments = mutableListOf<ByteArray>()
        try {
            if (useDirectFile) {
                RandomAccessFile(directFile!!, "r").use { raf ->
                    parseJpegApp1Segments(raf) { payload -> segments.add(payload) }
                }
            } else {
                val targetUri = if (Build.VERSION.SDK_INT >= 29 &&
                    image.uri.scheme == android.content.ContentResolver.SCHEME_CONTENT
                ) {
                    runCatching { MediaStore.setRequireOriginal(image.uri) }.getOrDefault(image.uri)
                } else image.uri
                context.contentResolver.openInputStream(targetUri)?.use { stream ->
                    parseJpegApp1SegmentsStream(stream) { payload -> segments.add(payload) }
                }
            }
        } catch (_: Exception) {}
        return segments
    }

    // ------------------------------------------------------------------
    // RandomAccessFile-based APP1 scanner
    // ------------------------------------------------------------------

    private fun parseJpegApp1Segments(raf: RandomAccessFile, onSegment: (ByteArray) -> Unit) {
        val fileLen = raf.length()
        if (fileLen < 4) return
        // Verify SOI
        raf.seek(0)
        val b1 = raf.read()
        val b2 = raf.read()
        if (b1 != 0xFF || b2 != SOI) return

        var pos = 2L
        while (pos < fileLen - 4) {
            raf.seek(pos)
            val prefix = raf.read()
            if (prefix != 0xFF) { pos++; continue }
            var marker = raf.read()
            while (marker == 0xFF) marker = raf.read()
            if (marker == -1 || marker == EOI || marker == SOS) break
            if (marker in 0xD0..0xD7) { pos++; continue }

            val lenHi = raf.read()
            val lenLo = raf.read()
            if (lenHi == -1 || lenLo == -1) break
            val segLen = (lenHi shl 8) or lenLo
            val payloadLen = segLen - 2
            if (payloadLen <= 0) { pos += 2 + segLen; continue }

            if (marker == APP1) {
                val payload = ByteArray(payloadLen)
                var read = 0
                while (read < payloadLen) {
                    val n = raf.read(payload, read, payloadLen - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == payloadLen) onSegment(payload)
            }
            pos += 4 + payloadLen  // FF + marker + 2-byte length + payload
        }
    }

    // ------------------------------------------------------------------
    // Stream-based APP1 scanner
    // ------------------------------------------------------------------

    private fun parseJpegApp1SegmentsStream(stream: InputStream, onSegment: (ByteArray) -> Unit) {
        val b1 = stream.read()
        val b2 = stream.read()
        if (b1 != 0xFF || b2 != SOI) return

        while (true) {
            val prefix = stream.read()
            if (prefix != 0xFF) break
            var marker = stream.read()
            while (marker == 0xFF) marker = stream.read()
            if (marker == -1 || marker == EOI || marker == SOS) break
            if (marker in 0xD0..0xD7) continue

            val lenHi = stream.read()
            val lenLo = stream.read()
            if (lenHi == -1 || lenLo == -1) break
            val segLen = (lenHi shl 8) or lenLo
            val payloadLen = segLen - 2
            if (payloadLen <= 0) continue

            if (marker == APP1) {
                val payload = ByteArray(payloadLen)
                var read = 0
                while (read < payloadLen) {
                    val n = stream.read(payload, read, payloadLen - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == payloadLen) onSegment(payload)
            } else {
                var skipped = 0L
                while (skipped < payloadLen) {
                    val s = stream.skip(payloadLen - skipped)
                    if (s <= 0) break
                    skipped += s
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Base64 JPEG decoding
    // ------------------------------------------------------------------

    private fun decodeBase64Jpeg(b64: String): Bitmap? {
        return runCatching {
            val cleaned = b64
                .replace("&#xA;", "")
                .replace("&#xa;", "")
                .replace("&#xD;", "")
                .replace("&#xd;", "")
                .replace("\n", "")
                .replace("\r", "")
                .replace("\t", "")
                .replace(" ", "")
                .trim()
            if (cleaned.isEmpty()) return null
            val bytes = Base64.decode(cleaned, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // XMP attribute extraction
    // ------------------------------------------------------------------

    private fun extractAttr(xmp: String, name: String): String? {
        // Attribute form: name="value" — the value may contain &#xA; XML entities for
        // line breaks but the surrounding quotes are always balanced.
        val key = "$name="
        val attrIdx = xmp.indexOf(key, ignoreCase = true)
        if (attrIdx >= 0) {
            val quoteChar = xmp.getOrNull(attrIdx + key.length)
            if (quoteChar == '"' || quoteChar == '\'') {
                val valueStart = attrIdx + key.length + 1
                val valueEnd = xmp.indexOf(quoteChar, valueStart)
                if (valueEnd > valueStart) {
                    return xmp.substring(valueStart, valueEnd).trim().ifBlank { null }
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Byte helpers
    // ------------------------------------------------------------------

    private fun readUInt32BE(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0xFF) shl 24) or
                ((b[offset + 1].toInt() and 0xFF) shl 16) or
                ((b[offset + 2].toInt() and 0xFF) shl 8) or
                (b[offset + 3].toInt() and 0xFF)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return from
        val max = haystack.size - needle.size
        if (max < 0 || from > max) return -1
        var i = from.coerceAtLeast(0)
        while (i <= max) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    private fun ByteArray.startsWith(prefix: ByteArray, offset: Int = 0): Boolean {
        if (offset + prefix.size > this.size) return false
        for (i in prefix.indices) {
            if (this[offset + i] != prefix[i]) return false
        }
        return true
    }
}