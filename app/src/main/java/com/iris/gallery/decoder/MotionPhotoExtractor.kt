package com.iris.gallery.decoder

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import com.iris.gallery.data.MediaImage
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Result of extracting the embedded motion video from a [MediaImage].
 *
 * @param videoUri  Uri of a cached temp file holding the extracted MP4.
 * @param mimeType  MIME type of the embedded video (usually "video/mp4").
 * @param length    Byte length of the extracted video.
 */
data class MotionVideo(
    val videoUri: Uri,
    val mimeType: String,
    val length: Long,
)

/**
 * Extracts the embedded short video from a Motion Photo produced by Samsung, Google (Pixel)
 * or Nokia (Living Image) cameras.
 *
 * All three formats are fundamentally a still image (JPEG/HEIC) with an MP4 appended. The
 * differences are in how the video offset is signposted:
 *
 *  - **Samsung**: a binary "SEF" trailer (`SEFH` … `SEFT`). The tag `MotionPhoto_Data`
 *    (preceded by the 4-byte marker `00 00 30 0A` and a 4-byte length) is immediately followed
 *    by the raw MP4, which runs up to `SEFH`. XMP carries `MotionPhoto=1`.
 *  - **Google**: XMP carries `MicroVideoOffset` = bytes from EOF back to the video start.
 *  - **Nokia / generic**: no dedicated metadata — scan for the MP4 `ftyp` box past the JPEG
 *    EOI marker.
 *
 * The extractor reads from the file path when accessible, otherwise falls back to the
 * ContentResolver stream (mirroring [com.iris.gallery.data.loadExifMetadata]). All I/O must
 * run on [kotlinx.coroutines.Dispatchers.IO]; this object performs synchronous reads only.
 */
object MotionPhotoExtractor {

    // Samsung SEF anchors.
    private val SEFH = "SEFH".toByteArray(Charsets.US_ASCII)
    private val SEFT = "SEFT".toByteArray(Charsets.US_ASCII)
    private val MOTION_PHOTO_DATA = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)

    // MP4 ftyp box brand: <4-byte size><"ftyp">.
    private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)
    // JPEG End-Of-Image marker.
    private val JPEG_EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    // Bytes from EOF we scan for the SEF trailer. The SEF header + tag table are tiny (tens of
    // bytes); 64 KiB comfortably covers them even for large embedded videos.
    private const val TAIL_SCAN_SIZE = 64 * 1024

    private val cache = LruCache<Long, MotionVideo>(64)

    /** Cached extraction result, or null if [image] is not a motion photo. */
    fun detectMotionPhoto(context: Context, image: MediaImage): MotionVideo? {
        cache.get(image.id)?.let { return it }
        val result = runCatching { extract(context, image) }.getOrNull() ?: return null
        if (result != null) cache.put(image.id, result)
        return result
    }

    /** Drop the cached entry for [imageId] (e.g. when the item is deleted). */
    fun evict(imageId: Long) {
        cache.remove(imageId)
    }

    /** Clear the whole cache. */
    fun clearCache() {
        cache.evictAll()
    }

    private fun extract(context: Context, image: MediaImage): MotionVideo? {
        val directFile = if (image.path.isNotBlank()) File(image.path) else null
        val useDirectFile = directFile != null && directFile.exists() && directFile.canRead()

        val openInput: () -> InputStream? = {
            if (useDirectFile) {
                java.io.FileInputStream(directFile!!)
            } else {
                val targetUri = if (Build.VERSION.SDK_INT >= 29 &&
                    image.uri.scheme == ContentResolver.SCHEME_CONTENT
                ) {
                    runCatching { MediaStore.setRequireOriginal(image.uri) }.getOrDefault(image.uri)
                } else image.uri
                context.contentResolver.openInputStream(targetUri)
            }
        }

        val fileLength = when {
            useDirectFile -> directFile!!.length()
            image.sizeBytes > 0 -> image.sizeBytes
            else -> -1L
        }
        if (fileLength <= 0) return null

        // 1) Read the tail (cheap, 64 KiB). Try Samsung SEF + quick "plain JPEG" reject.
        val tail = readTail(directFile, openInput, fileLength)
        if (tail != null) {
            extractSamsungSefFromTail(context, image, openInput, directFile, tail, fileLength)
                ?.let { return it }
            // A plain JPEG ends with the EOI marker (FF D9) — no trailing video, not a motion photo.
            val n = tail.size
            if (n >= 2 && tail[n - 2] == JPEG_EOI[0] && tail[n - 1] == JPEG_EOI[1]) return null
        }

        // 2) Google: XMP MicroVideoOffset.
        extractViaXmp(context, image, openInput, fileLength, directFile)?.let { return it }

        // 3) Generic fallback: JPEG with an MP4 trailer past the EOI (Nokia / others).
        return extractGenericMp4(context, image, openInput, fileLength, directFile)
    }

    // ------------------------------------------------------------------
    // Path 1: XMP metadata (Google)
    // ------------------------------------------------------------------

    private fun extractViaXmp(
        context: Context,
        image: MediaImage,
        openInput: () -> InputStream?,
        fileLength: Long,
        directFile: File?,
    ): MotionVideo? {
        if (fileLength <= 0) return null
        val exif = runCatching {
            openInput()?.use { stream -> androidx.exifinterface.media.ExifInterface(stream) }
        }.getOrNull() ?: return null

        val xmp = cleanExif(exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_XMP))
            ?: return null

        // Google "MicroVideo" (pre-Android 11): MicroVideoOffset = bytes from EOF to video start.
        val microOffset = extractAttr(xmp, "MicroVideoOffset")
            ?: extractAttr(xmp, "GCamera:MicroVideoOffset")
        if (microOffset != null) {
            val offset = microOffset.toLongOrNull() ?: return null
            if (offset in 1 until fileLength) {
                val start = fileLength - offset
                return copyVideoRange(context, image, openInput, directFile, start, fileLength)
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Path 2: Samsung SEF trailer
    // ------------------------------------------------------------------

    private fun extractSamsungSefFromTail(
        context: Context,
        image: MediaImage,
        openInput: () -> InputStream?,
        directFile: File?,
        tail: ByteArray,
        fileLength: Long,
    ): MotionVideo? {
        if (tail.size < SEFH.size + SEFT.size + MOTION_PHOTO_DATA.size + 16) return null

        val tailAbsStart = fileLength - tail.size

        val mpdRel = indexOf(tail, MOTION_PHOTO_DATA, 0)
        if (mpdRel < 0) return null

        // Verify a SEF trailer is present (distinguishes a Samsung motion photo from a JPEG that
        // merely happens to contain the literal "MotionPhoto_Data" string).
        val sefhRel = indexOf(tail, SEFH, mpdRel)
        if (sefhRel < 0) return null
        if (indexOf(tail, SEFT, sefhRel) < 0) return null

        // The MP4 begins immediately after "MotionPhoto_Data" (the EmbeddedVideoType tag is
        // marker(4) + length(4) + "MotionPhoto_Data"(16); we anchor on the literal so the MP4
        // starts right after it). The MP4 ends at the SEFH marker.
        val mp4Start = tailAbsStart + mpdRel + MOTION_PHOTO_DATA.size
        val mp4End = tailAbsStart + sefhRel
        if (mp4End <= mp4Start) return null

        return copyVideoRange(context, image, openInput, directFile, mp4Start, mp4End)
    }

    // ------------------------------------------------------------------
    // Path 3: generic MP4 past JPEG EOI (Nokia / others)
    // ------------------------------------------------------------------

    private fun extractGenericMp4(
        context: Context,
        image: MediaImage,
        openInput: () -> InputStream?,
        fileLength: Long,
        directFile: File?,
    ): MotionVideo? {
        if (fileLength <= 0) return null
        // Bounded head scan: the appended MP4's ftyp box sits right after the JPEG EOI marker.
        // Cap at 16 MiB so very large files don't blow up memory; only reached for files that
        // already passed the tail check (i.e. they look like motion photos, not plain JPEGs).
        val scanWindow = minOf(fileLength, 16L * 1024 * 1024).toInt()
        val buf = ByteArray(scanWindow)
        val read = openInput()?.use { stream ->
            var total = 0
            while (total < scanWindow) {
                val n = stream.read(buf, total, scanWindow - total)
                if (n <= 0) break
                total += n
            }
            total
        } ?: return null
        if (read <= 0) return null

        // Require a JPEG EOI — this path handles JPEG+MP4 trailers (Nokia/Generic). A plain HEIC
        // has no JPEG EOI and would otherwise match its own ftyp box, so bail out.
        val eoi = indexOf(buf, JPEG_EOI, 0, read)
        if (eoi < 0) return null
        val ftypRel = indexOf(buf, FTYP, eoi + JPEG_EOI.size, read)
        if (ftypRel < 0) return null

        // MP4 starts 4 bytes before "ftyp" (the size field). Video runs to EOF.
        val start = (ftypRel - 4).toLong().coerceAtLeast(0)
        val end = fileLength
        if (end - start < 16) return null
        return copyVideoRange(context, image, openInput, directFile, start, end)
    }

    // ------------------------------------------------------------------
    // Range copy to a cached temp file
    // ------------------------------------------------------------------

    private fun copyVideoRange(
        context: Context,
        image: MediaImage,
        openInput: () -> InputStream?,
        directFile: File?,
        start: Long,
        end: Long,
    ): MotionVideo? {
        if (start < 0 || end <= start) return null
        val len = end - start
        val outFile = File(context.cacheDir, "motion_${image.id}_${start}_${len}.mp4")
        if (outFile.exists() && outFile.length() == len) {
            return MotionVideo(Uri.fromFile(outFile), "video/mp4", len)
        }

        return runCatching {
            if (directFile != null && directFile.exists() && directFile.canRead()) {
                RandomAccessFile(directFile, "r").use { raf ->
                    raf.seek(start)
                    val tmp = File(outFile.absolutePath + ".tmp")
                    java.io.FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var remaining = len
                        while (remaining > 0) {
                            val n = raf.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    if (!tmp.renameTo(outFile)) tmp.delete()
                }
            } else {
                openInput()?.use { stream ->
                    var skipped = 0L
                    while (skipped < start) {
                        val s = stream.skip(start - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                    val tmp = File(outFile.absolutePath + ".tmp")
                    java.io.FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var remaining = len
                        while (remaining > 0) {
                            val n = stream.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    if (!tmp.renameTo(outFile)) tmp.delete()
                } ?: return@runCatching null
            }
            if (outFile.exists() && outFile.length() > 0) {
                MotionVideo(Uri.fromFile(outFile), "video/mp4", outFile.length())
            } else null
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Tail reading
    // ------------------------------------------------------------------

    private fun readTail(
        directFile: File?,
        openInput: () -> InputStream?,
        fileLength: Long,
    ): ByteArray? {
        val size = minOf(fileLength, TAIL_SCAN_SIZE.toLong()).toInt()
        if (size <= 0) return null
        return if (directFile != null && directFile.exists() && directFile.canRead()) {
            readTailViaRandomAccess(directFile, fileLength, size)
        } else {
            readTailViaStream(openInput, fileLength, size)
        }
    }

    private fun readTailViaRandomAccess(file: File, fileLength: Long, size: Int): ByteArray {
        val buf = ByteArray(size)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(fileLength - size)
            var read = 0
            while (read < size) {
                val n = raf.read(buf, read, size - read)
                if (n <= 0) break
                read += n
            }
            return if (read == size) buf else buf.copyOf(read)
        }
    }

    private fun readTailViaStream(openInput: () -> InputStream?, fileLength: Long, size: Int): ByteArray? {
        val skipAhead = fileLength - size
        return openInput()?.use { stream ->
            var skipped = 0L
            while (skipped < skipAhead) {
                val s = stream.skip(skipAhead - skipped)
                if (s <= 0) break
                skipped += s
            }
            val buf = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = stream.read(buf, read, size - read)
                if (n <= 0) break
                read += n
            }
            if (read == 0) null else buf.copyOf(read)
        }
    }

    // ------------------------------------------------------------------
    // Byte helpers
    // ------------------------------------------------------------------

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int, limit: Int = haystack.size): Int {
        if (needle.isEmpty()) return from
        val max = limit - needle.size
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

    private fun extractAttr(xmp: String, name: String): String? {
        // Attribute form: name="value" or name='value' (tolerates namespace prefixes).
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
        // Element form: <[prefix:]name>value</[prefix:]name>
        val tagRegex = Regex("<[^<>:]*:?$name>([^<]+)</[^<>:]*:?$name>", RegexOption.IGNORE_CASE)
        return tagRegex.find(xmp)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
    }

    private fun cleanExif(raw: String?): String? =
        raw?.trim()?.replace("\u0000", "")?.replace("�", "")?.ifBlank { null }
}
