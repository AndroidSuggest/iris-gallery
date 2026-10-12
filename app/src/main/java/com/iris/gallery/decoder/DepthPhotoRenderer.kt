package com.iris.gallery.decoder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Compute a power-of-two sample size so that the decoded image's larger dimension is
 * roughly [target] pixels. Used when loading the sharp primary image of a depth photo
 * at a preview resolution.
 */
fun calcSampleSize(width: Int, height: Int, target: Int): Int {
    var s = 1
    val maxDim = maxOf(width, height)
    while (maxDim / (s * 2) > target) s *= 2
    return s
}

/**
 * Apply an EXIF orientation constant (as defined in [ExifInterface.TAG_ORIENTATION])
 * to a [Bitmap]. Returns the same bitmap if no rotation is needed, otherwise returns
 * a new rotated bitmap. The caller is responsible for recycling the original if it
 * is no longer used.
 */
fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    val needsTransform = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
            matrix.postScale(-1f, 1f); true
        }
        ExifInterface.ORIENTATION_ROTATE_180 -> {
            matrix.postRotate(180f); true
        }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.postScale(1f, -1f); true
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f); matrix.postScale(-1f, 1f); true
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> {
            matrix.postRotate(90f); true
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f); matrix.postScale(-1f, 1f); true
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> {
            matrix.postRotate(270f); true
        }
        else -> false
    }
    if (!needsTransform) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/**
 * Blends a sharp primary image with a pre-blurred background image using a depth map,
 * producing the portrait/bokeh effect that depth photos (Google GDepth format, e.g. Nokia 7.2)
 * are designed for.
 *
 * The depth map is a grayscale image where each pixel encodes the distance to the nearest
 * surface (0–255). The renderer:
 *
 *  1. Resizes the depth map to the output resolution.
 *  2. For every pixel, computes a **blur alpha** from the depth value, the user-selected
 *     [focusDepth] (0=far … 1=near, or reversed for [RangeInverse]), [transition] (how quickly
 *     blur ramps away from the focal plane), and [strength] (overall blur amount, 0=no blur
 *     → 1=fully blurred everywhere).
 *  3. Blends `sharp × (1−α) + blurred × α`.
 *
 * For smooth interactive performance, a down-scaled preview pass can be requested via
 * [previewMaxDimension]; the caller can then re-render at full resolution when the user
 * releases the slider.
 */
object DepthPhotoRenderer {

    /**
     * Render a depth-blended bitmap.
     *
     * @param sharp          The primary (in-focus) image.
     * @param blurred        The pre-blurred background (GImage). Will be scaled to match [sharp].
     * @param depthMap       Grayscale depth map (typically ¼ resolution).
     * @param isRangeInverse true if 255 = nearest (Google/Nokia default), false if 255 = farthest.
     * @param focusDepth     0–1, which depth to keep sharp (0=far, 1=near).
     * @param transition     0–1, focal-plane half-width as a fraction of the full 0–255 range.
     *                       Larger = broader in-focus band.
     * @param strength       0–1, overall blur intensity (0=fully sharp, 1=fully blurred).
     * @param previewMaxDimension If > 0, the output is downscaled to fit this dimension for
     *                       fast interactive preview.
     * @return Blended [Bitmap] (ARGB_8888). Never null.
     */
    fun render(
        sharp: Bitmap,
        blurred: Bitmap,
        depthMap: Bitmap,
        isRangeInverse: Boolean,
        focusDepth: Float,
        transition: Float,
        strength: Float,
        previewMaxDimension: Int = 0,
    ): Bitmap {
        val srcW = sharp.width
        val srcH = sharp.height

        // Determine output size (optionally downscaled for preview).
        val scale = if (previewMaxDimension > 0) {
            val maxDim = maxOf(srcW, srcH)
            (previewMaxDimension.toFloat() / maxDim).coerceAtMost(1f)
        } else 1f
        val outW = (srcW * scale).roundToInt().coerceAtLeast(1)
        val outH = (srcH * scale).roundToInt().coerceAtLeast(1)

        // Scale the depth map to the output resolution.
        val scaledDepth = if (depthMap.width == outW && depthMap.height == outH) {
            depthMap
        } else {
            Bitmap.createScaledBitmap(depthMap, outW, outH, true)
        }

        // Scale the blurred image to match.
        val scaledBlurred = if (blurred.width == outW && blurred.height == outH) {
            blurred
        } else {
            Bitmap.createScaledBitmap(blurred, outW, outH, true)
        }

        // Scale the sharp image if we're rendering a preview.
        val scaledSharp = if (scale >= 1f) {
            sharp
        } else {
            Bitmap.createScaledBitmap(sharp, outW, outH, true)
        }

        // Convert focus/transition from normalised [0,1] to 8-bit depth space.
        val focusByte = (focusDepth * 255f).roundToInt().coerceIn(0, 255)
        val transitionBytes = (transition * 255f).coerceAtLeast(1f)

        // Per-pixel blend.
        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val sharpPixels = IntArray(outW * outH)
        val blurredPixels = IntArray(outW * outH)
        val depthPixels = IntArray(outW * outH)
        scaledSharp.getPixels(sharpPixels, 0, outW, 0, 0, outW, outH)
        scaledBlurred.getPixels(blurredPixels, 0, outW, 0, 0, outW, outH)
        scaledDepth.getPixels(depthPixels, 0, outW, 0, 0, outW, outH)

        val outPixels = IntArray(outW * outH)
        val s = strength.coerceIn(0f, 1f)
        val invTrans = 1f / transitionBytes

        for (i in outPixels.indices) {
            // Depth value: R channel of the depth map (grayscale, R=G=B).
            var d = (depthPixels[i] shr 16) and 0xFF
            if (isRangeInverse) {
                // RangeInverse: 255 = nearest. The user's focusDepth=1 means "nearest",
                // so we invert to keep the convention: focusByte maps to the user's intent.
                d = 255 - d
            }

            // Distance from the focal plane, normalised by transition width.
            val dist = abs(d - focusByte) * invTrans
            // alpha = how much of the blurred image to show at this pixel.
            val alpha = (dist * s).coerceIn(0f, 1f)

            val sp = sharpPixels[i]
            val bp = blurredPixels[i]

            val oneMinusAlpha = 1f - alpha
            val r = (((sp shr 16) and 0xFF) * oneMinusAlpha + (((bp shr 16) and 0xFF)) * alpha).roundToInt()
            val g = (((sp shr 8) and 0xFF) * oneMinusAlpha + (((bp shr 8) and 0xFF)) * alpha).roundToInt()
            val b = ((sp and 0xFF) * oneMinusAlpha + ((bp and 0xFF)) * alpha).roundToInt()
            val a = ((sp ushr 24) * oneMinusAlpha + ((bp ushr 24)) * alpha).roundToInt()
            outPixels[i] = (a shl 24) or (r.coerceIn(0, 255) shl 16) or
                    (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
        output.setPixels(outPixels, 0, outW, 0, 0, outW, outH)

        // Recycle intermediate bitmaps that we created (not the caller's originals).
        if (scaledDepth !== depthMap) scaledDepth.recycle()
        if (scaledBlurred !== blurred) scaledBlurred.recycle()
        if (scaledSharp !== sharp) scaledSharp.recycle()

        return output
    }
}