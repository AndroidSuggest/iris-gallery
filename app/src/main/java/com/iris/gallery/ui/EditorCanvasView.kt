package com.iris.gallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

enum class EditorTool { ADJUST, CROP, TRANSFORM, RESIZE, DRAW, SHAPE, TEXT, PIXELATE, BLUR }
enum class BrushEffect { PIXELATE, BLUR, COLOR }
enum class ShapeType { NONE, RECTANGLE, OVAL, ARROW, LINE }

data class BrushPoint(val x: Float, val y: Float)
data class BrushStroke(
    val effect: BrushEffect,
    val radius: Float,
    val strength: Int,
    val points: MutableList<BrushPoint> = mutableListOf(),
    val color: Int = android.graphics.Color.RED,
    val shape: ShapeType = ShapeType.NONE,
    val filled: Boolean = false
)

data class TextOverlay(
    val id: Long = System.currentTimeMillis() + (0..10000).random(),
    var text: String = "",
    var x: Float = 0.5f,
    var y: Float = 0.5f,
    var color: Int = android.graphics.Color.WHITE,
    var bgColor: Int = android.graphics.Color.argb(160, 0, 0, 0),
    var textSizeRatio: Float = 0.05f
)

class EditorSession {
    val crop = RectF(0f, 0f, 1f, 1f)
    val strokes = mutableListOf<BrushStroke>()
    val textOverlays = mutableListOf<TextOverlay>()
}

class EditorCanvasView(context: Context) : View(context) {
    val session = EditorSession()
    var tool = EditorTool.ADJUST; set(value) { field = value; invalidate() }
    var brushRadius = .06f
    var brushColor = android.graphics.Color.RED
    var currentShapeType: ShapeType = ShapeType.RECTANGLE; set(value) { field = value; invalidate() }
    var shapeFilled: Boolean = false; set(value) { field = value; invalidate() }
    var effectStrength = 18; set(value) { if (field != value) { field = value; rebuildEffects() } }
    var erasing = false
    var selectedTextId: Long? = null; set(value) { field = value; invalidate() }
    var onTextSelected: ((TextOverlay?) -> Unit)? = null
    private var isDraggingText = false
    private var draggedTextOverlay: TextOverlay? = null
    var colorFilter: android.graphics.ColorFilter? = null; set(value) { field = value; invalidate() }
    private var bitmap: Bitmap? = null
    private var composite: Bitmap? = null
    private var pixelated: Bitmap? = null
    private var blurred: Bitmap? = null
    private val destination = RectF()
    private var activeStroke: BrushStroke? = null
    private val redoStrokes = mutableListOf<BrushStroke>()
    private var cropHandle = -1
    private var lastCropPoint = BrushPoint(0f, 0f)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun setSource(value: Bitmap?) {
        if (bitmap === value) return
        bitmap = value
        rebuildComposite()
        rebuildEffects()
        invalidate()
    }

    fun clearBitmaps() {
        pixelated?.recycle(); pixelated = null
        blurred?.recycle(); blurred = null
        composite?.takeIf { it !== bitmap }?.recycle(); composite = null
        bitmap = null
    }

    var lockedAspect: Float? = null

    fun setCropAspect(aspect: Float?) {
        lockedAspect = aspect?.takeIf { it > 0f }
        val targetAspect = lockedAspect ?: run {
            invalidate()
            return
        }
        val source = bitmap ?: return
        val normalizedAspect = targetAspect * source.height / source.width
        val centerX = session.crop.centerX()
        val centerY = session.crop.centerY()
        var width = session.crop.width()
        var height = width / normalizedAspect
        if (height > 1f) {
            height = 1f
            width = height * normalizedAspect
        }
        if (width > 1f) {
            width = 1f
            height = width / normalizedAspect
        }
        val left = (centerX - width / 2f).coerceIn(0f, (1f - width).coerceAtLeast(0f))
        val top = (centerY - height / 2f).coerceIn(0f, (1f - height).coerceAtLeast(0f))
        session.crop.set(left, top, left + width, top + height)
        keepCropInBounds()
        invalidate()
    }

    fun undoStroke() {
        if (session.strokes.isNotEmpty()) redoStrokes.add(session.strokes.removeLast())
        rebuildComposite(); invalidate()
    }
    fun redoStroke() {
        if (redoStrokes.isNotEmpty()) session.strokes.add(redoStrokes.removeLast())
        rebuildComposite(); invalidate()
    }
    fun clearStrokes() {
        redoStrokes.clear(); redoStrokes.addAll(session.strokes.asReversed()); session.strokes.clear()
        rebuildComposite(); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = composite ?: bitmap ?: return
        fitRect(source, destination, cropped = tool != EditorTool.CROP)
        imagePaint.colorFilter = colorFilter
        canvas.drawBitmap(source, sourcePixelRect(source), destination, imagePaint)
        imagePaint.colorFilter = null
        canvas.save(); canvas.clipRect(destination)
        activeStroke?.let { drawStroke(canvas, it) }
        drawTextOverlays(canvas)
        canvas.restore()
        if (tool == EditorTool.CROP) drawCrop(canvas)
    }

    private fun drawStroke(canvas: Canvas, stroke: BrushStroke) {
        if (stroke.shape != ShapeType.NONE && stroke.points.size >= 2) {
            val p1 = stroke.points.first()
            val p2 = stroke.points.last()
            val x1 = destination.left + (p1.x - visibleLeft()) / visibleWidth() * destination.width()
            val y1 = destination.top + (p1.y - visibleTop()) / visibleHeight() * destination.height()
            val x2 = destination.left + (p2.x - visibleLeft()) / visibleWidth() * destination.width()
            val y2 = destination.top + (p2.y - visibleTop()) / visibleHeight() * destination.height()
            val strokeWidth = stroke.radius * destination.width()
            renderShape(canvas, stroke, x1, y1, x2, y2, strokeWidth)
            return
        }
        val path = Path()
        stroke.points.forEachIndexed { index, point ->
            val vx = destination.left + (point.x - visibleLeft()) / visibleWidth() * destination.width()
            val vy = destination.top + (point.y - visibleTop()) / visibleHeight() * destination.height()
            if (index == 0) path.moveTo(vx, vy) else path.lineTo(vx, vy)
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = stroke.radius * destination.width()
            if (stroke.effect == BrushEffect.COLOR) {
                color = stroke.color
            } else {
                shader = BitmapShader(if (stroke.effect == BrushEffect.PIXELATE) pixelated ?: return else blurred ?: return,
                    Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
        }
        canvas.drawPath(path, strokePaint)
    }

    private fun drawTextOverlays(canvas: Canvas) {
        if (session.textOverlays.isEmpty()) return
        session.textOverlays.forEach { overlay ->
            val vx = destination.left + (overlay.x - visibleLeft()) / visibleWidth() * destination.width()
            val vy = destination.top + (overlay.y - visibleTop()) / visibleHeight() * destination.height()
            val pxSize = (overlay.textSizeRatio / visibleWidth()) * destination.width()
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = overlay.color
                textSize = pxSize.coerceAtLeast(14f)
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val bounds = Rect()
            textPaint.getTextBounds(overlay.text, 0, overlay.text.length, bounds)
            val paddingX = pxSize * 0.4f
            val paddingY = pxSize * 0.25f
            val bgRect = RectF(
                vx - bounds.width() / 2f - paddingX,
                vy - bounds.height() / 2f - paddingY,
                vx + bounds.width() / 2f + paddingX,
                vy + bounds.height() / 2f + paddingY
            )
            if (overlay.bgColor != 0) {
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = overlay.bgColor
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(bgRect, pxSize * 0.25f, pxSize * 0.25f, bgPaint)
            }
            if (tool == EditorTool.TEXT && overlay.id == selectedTextId) {
                val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }
                canvas.drawRoundRect(bgRect, pxSize * 0.25f, pxSize * 0.25f, selectPaint)
            }
            val textY = vy + bounds.height() / 2f - bounds.bottom
            canvas.drawText(overlay.text, vx, textY, textPaint)
        }
    }

    fun addTextOverlay(text: String, color: Int, bgColor: Int, sizeRatio: Float): TextOverlay {
        val overlay = TextOverlay(
            text = text,
            x = visibleLeft() + visibleWidth() / 2f,
            y = visibleTop() + visibleHeight() / 2f,
            color = color,
            bgColor = bgColor,
            textSizeRatio = sizeRatio
        )
        session.textOverlays.add(overlay)
        selectedTextId = overlay.id
        invalidate()
        return overlay
    }

    fun updateSelectedText(text: String, color: Int, bgColor: Int, sizeRatio: Float) {
        val overlay = session.textOverlays.find { it.id == selectedTextId } ?: return
        overlay.text = text
        overlay.color = color
        overlay.bgColor = bgColor
        overlay.textSizeRatio = sizeRatio
        invalidate()
    }

    fun removeSelectedText() {
        session.textOverlays.removeAll { it.id == selectedTextId }
        selectedTextId = null
        invalidate()
    }

    fun clearTextOverlays() {
        session.textOverlays.clear()
        selectedTextId = null
        invalidate()
    }

    private fun drawCrop(canvas: Canvas) {
        val cropRect = cropViewRect()
        val dimPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }
        canvas.drawRect(destination.left, destination.top, destination.right, cropRect.top, dimPaint)
        canvas.drawRect(destination.left, cropRect.bottom, destination.right, destination.bottom, dimPaint)
        canvas.drawRect(destination.left, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, destination.right, cropRect.bottom, dimPaint)

        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawRect(cropRect, framePaint)

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255); strokeWidth = 1.5f }
        val thirdW = cropRect.width() / 3f; val thirdH = cropRect.height() / 3f
        canvas.drawLine(cropRect.left + thirdW, cropRect.top, cropRect.left + thirdW, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left + thirdW * 2, cropRect.top, cropRect.left + thirdW * 2, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + thirdH, cropRect.right, cropRect.top + thirdH, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + thirdH * 2, cropRect.right, cropRect.top + thirdH * 2, gridPaint)

        val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val handleSize = 14f
        listOf(cropRect.left to cropRect.top, cropRect.right to cropRect.top,
            cropRect.right to cropRect.bottom, cropRect.left to cropRect.bottom).forEach { (hx, hy) ->
            canvas.drawCircle(hx, hy, handleSize, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tool != EditorTool.CROP && tool != EditorTool.PIXELATE && tool != EditorTool.BLUR &&
            tool != EditorTool.DRAW && tool != EditorTool.SHAPE && tool != EditorTool.TEXT) return super.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_DOWN && !destination.contains(event.x, event.y)) return true
        val point = viewToNormalized(event.x, event.y)
        when (tool) {
            EditorTool.CROP -> handleCropTouch(event, point)
            EditorTool.PIXELATE, EditorTool.BLUR, EditorTool.DRAW -> handleBrushTouch(event, point)
            EditorTool.SHAPE -> handleShapeTouch(event, point)
            EditorTool.TEXT -> handleTextTouch(event, point)
            else -> {}
        }
        return true
    }

    private fun handleBrushTouch(event: MotionEvent, point: BrushPoint) {
        if (erasing) {
            if (event.action != MotionEvent.ACTION_UP) {
                val changed = session.strokes.removeAll { stroke ->
                    isPointNearStrokeOrShape(point, stroke, brushRadius)
                }
                if (changed) { redoStrokes.clear(); rebuildComposite() }
                invalidate()
            }
            return
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                redoStrokes.clear()
                val effect = when (tool) {
                    EditorTool.PIXELATE -> BrushEffect.PIXELATE
                    EditorTool.BLUR -> BrushEffect.BLUR
                    else -> BrushEffect.COLOR
                }
                activeStroke = BrushStroke(
                    effect = effect,
                    radius = brushRadius,
                    strength = effectStrength,
                    points = mutableListOf(point),
                    color = brushColor
                )
            }
            MotionEvent.ACTION_MOVE -> activeStroke?.points?.add(point)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activeStroke?.let { session.strokes.add(it) }.also {
                activeStroke = null; rebuildComposite(); rebuildEffects()
            }
        }
        invalidate()
    }

    private fun handleShapeTouch(event: MotionEvent, point: BrushPoint) {
        if (erasing) {
            if (event.action != MotionEvent.ACTION_UP) {
                val changed = session.strokes.removeAll { stroke ->
                    isPointNearStrokeOrShape(point, stroke, brushRadius)
                }
                if (changed) { redoStrokes.clear(); rebuildComposite() }
                invalidate()
            }
            return
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                redoStrokes.clear()
                activeStroke = BrushStroke(
                    effect = BrushEffect.COLOR,
                    radius = brushRadius,
                    strength = 0,
                    points = mutableListOf(point, point),
                    color = brushColor,
                    shape = currentShapeType,
                    filled = shapeFilled
                )
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = activeStroke ?: return
                if (stroke.points.size >= 2) {
                    stroke.points[1] = point
                } else {
                    stroke.points.add(point)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val stroke = activeStroke
                if (stroke != null && stroke.points.size >= 2) {
                    val p1 = stroke.points.first()
                    val p2 = stroke.points.last()
                    if (hypot(p2.x - p1.x, p2.y - p1.y) > 0.006f) {
                        session.strokes.add(stroke)
                    }
                }
                activeStroke = null
                rebuildComposite()
            }
        }
        invalidate()
    }

    private fun handleTextTouch(event: MotionEvent, point: BrushPoint) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val hit = session.textOverlays.findLast { overlay ->
                    val dist = hypot(overlay.x - point.x, overlay.y - point.y)
                    dist < (overlay.textSizeRatio * 2.5f).coerceAtLeast(0.08f)
                }
                if (hit != null) {
                    selectedTextId = hit.id
                    draggedTextOverlay = hit
                    isDraggingText = true
                    onTextSelected?.invoke(hit)
                } else {
                    selectedTextId = null
                    draggedTextOverlay = null
                    isDraggingText = false
                    onTextSelected?.invoke(null)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingText && draggedTextOverlay != null) {
                    draggedTextOverlay?.x = point.x.coerceIn(0.02f, 0.98f)
                    draggedTextOverlay?.y = point.y.coerceIn(0.02f, 0.98f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDraggingText = false
                draggedTextOverlay = null
            }
        }
    }

    private fun handleCropTouch(event: MotionEvent, point: BrushPoint) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val corners = listOf(BrushPoint(session.crop.left, session.crop.top), BrushPoint(session.crop.right, session.crop.top),
                    BrushPoint(session.crop.right, session.crop.bottom), BrushPoint(session.crop.left, session.crop.bottom))
                cropHandle = corners.indices.minByOrNull { hypot(corners[it].x - point.x, corners[it].y - point.y) } ?: -1
                if (cropHandle >= 0 && hypot(corners[cropHandle].x - point.x, corners[cropHandle].y - point.y) > .12f) cropHandle = 4
                lastCropPoint = point
            }
            MotionEvent.ACTION_MOVE -> {
                val source = bitmap
                if (cropHandle == 4) {
                    val dx = point.x - lastCropPoint.x
                    val dy = point.y - lastCropPoint.y
                    session.crop.offset(dx, dy)
                    keepCropInBounds()
                } else if (lockedAspect != null && source != null) {
                    val normalizedAspect = lockedAspect!! * source.height / source.width
                    when (cropHandle) {
                        0 -> {
                            val rawW = session.crop.right - point.x
                            val rawH = session.crop.bottom - point.y
                            val maxW = session.crop.right.coerceIn(0.08f, 1f)
                            val maxH = session.crop.bottom.coerceIn(0.08f, 1f)
                            val w = maxOf(rawW, rawH * normalizedAspect).coerceIn(0.08f, minOf(maxW, maxH * normalizedAspect))
                            val h = w / normalizedAspect
                            session.crop.left = session.crop.right - w
                            session.crop.top = session.crop.bottom - h
                        }
                        1 -> {
                            val rawW = point.x - session.crop.left
                            val rawH = session.crop.bottom - point.y
                            val maxW = (1f - session.crop.left).coerceIn(0.08f, 1f)
                            val maxH = session.crop.bottom.coerceIn(0.08f, 1f)
                            val w = maxOf(rawW, rawH * normalizedAspect).coerceIn(0.08f, minOf(maxW, maxH * normalizedAspect))
                            val h = w / normalizedAspect
                            session.crop.right = session.crop.left + w
                            session.crop.top = session.crop.bottom - h
                        }
                        2 -> {
                            val rawW = point.x - session.crop.left
                            val rawH = point.y - session.crop.top
                            val maxW = (1f - session.crop.left).coerceIn(0.08f, 1f)
                            val maxH = (1f - session.crop.top).coerceIn(0.08f, 1f)
                            val w = maxOf(rawW, rawH * normalizedAspect).coerceIn(0.08f, minOf(maxW, maxH * normalizedAspect))
                            val h = w / normalizedAspect
                            session.crop.right = session.crop.left + w
                            session.crop.bottom = session.crop.top + h
                        }
                        3 -> {
                            val rawW = session.crop.right - point.x
                            val rawH = point.y - session.crop.top
                            val maxW = session.crop.right.coerceIn(0.08f, 1f)
                            val maxH = (1f - session.crop.top).coerceIn(0.08f, 1f)
                            val w = maxOf(rawW, rawH * normalizedAspect).coerceIn(0.08f, minOf(maxW, maxH * normalizedAspect))
                            val h = w / normalizedAspect
                            session.crop.left = session.crop.right - w
                            session.crop.bottom = session.crop.top + h
                        }
                    }
                    normalizeCrop()
                    keepCropInBounds()
                } else {
                    when (cropHandle) {
                        0 -> {
                            session.crop.left = point.x.coerceIn(0f, session.crop.right - 0.08f)
                            session.crop.top = point.y.coerceIn(0f, session.crop.bottom - 0.08f)
                        }
                        1 -> {
                            session.crop.right = point.x.coerceIn(session.crop.left + 0.08f, 1f)
                            session.crop.top = point.y.coerceIn(0f, session.crop.bottom - 0.08f)
                        }
                        2 -> {
                            session.crop.right = point.x.coerceIn(session.crop.left + 0.08f, 1f)
                            session.crop.top = point.y.coerceIn(session.crop.top + 0.08f, 1f)
                        }
                        3 -> {
                            session.crop.left = point.x.coerceIn(0f, session.crop.right - 0.08f)
                            session.crop.bottom = point.y.coerceIn(session.crop.top + 0.08f, 1f)
                        }
                    }
                    normalizeCrop()
                    keepCropInBounds()
                }
                lastCropPoint = point
                invalidate()
            }
        }
    }

    private fun normalizeCrop() {
        if (session.crop.width() < .08f) session.crop.right = session.crop.left + .08f
        if (session.crop.height() < .08f) session.crop.bottom = session.crop.top + .08f
    }
    private fun keepCropInBounds() {
        if (session.crop.left < 0f) session.crop.offset(-session.crop.left, 0f)
        if (session.crop.top < 0f) session.crop.offset(0f, -session.crop.top)
        if (session.crop.right > 1f) session.crop.offset(1f - session.crop.right, 0f)
        if (session.crop.bottom > 1f) session.crop.offset(0f, 1f - session.crop.bottom)
    }
    private fun cropViewRect() = RectF(destination.left + session.crop.left * destination.width(),
        destination.top + session.crop.top * destination.height(), destination.left + session.crop.right * destination.width(),
        destination.top + session.crop.bottom * destination.height())
    private fun viewToNormalized(x: Float, y: Float) = BrushPoint(
        visibleLeft() + ((x - destination.left) / destination.width()).coerceIn(0f, 1f) * visibleWidth(),
        visibleTop() + ((y - destination.top) / destination.height()).coerceIn(0f, 1f) * visibleHeight())
    private fun fitRect(source: Bitmap, out: RectF, cropped: Boolean) {
        val sourceWidth = source.width * if (cropped) session.crop.width() else 1f
        val sourceHeight = source.height * if (cropped) session.crop.height() else 1f
        val scale = minOf(width / sourceWidth, height / sourceHeight)
        val w = sourceWidth * scale; val h = sourceHeight * scale
        out.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }
    private fun visibleLeft() = if (tool == EditorTool.CROP) 0f else session.crop.left
    private fun visibleTop() = if (tool == EditorTool.CROP) 0f else session.crop.top
    private fun visibleWidth() = if (tool == EditorTool.CROP) 1f else session.crop.width()
    private fun visibleHeight() = if (tool == EditorTool.CROP) 1f else session.crop.height()
    private fun sourcePixelRectF(source: Bitmap) = RectF(visibleLeft() * source.width, visibleTop() * source.height,
        (visibleLeft() + visibleWidth()) * source.width, (visibleTop() + visibleHeight()) * source.height)
    private fun sourcePixelRect(source: Bitmap): Rect {
        val rect = sourcePixelRectF(source)
        return Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
    }
    private fun rebuildEffects() {
        val source = composite ?: bitmap ?: return
        pixelated?.recycle(); blurred?.recycle()
        val block = effectStrength.coerceAtLeast(2)
        val tinyPixel = Bitmap.createScaledBitmap(source, (source.width / block).coerceAtLeast(1), (source.height / block).coerceAtLeast(1), false)
        pixelated = Bitmap.createScaledBitmap(tinyPixel, source.width, source.height, false).also { tinyPixel.recycle() }
        blurred = createBlurredBitmap(source, effectStrength)
        invalidate()
    }

    private fun rebuildComposite() {
        val source = bitmap ?: return
        composite?.takeIf { it !== source }?.recycle()
        composite = source.copy(Bitmap.Config.ARGB_8888, true).also { target ->
            session.strokes.forEach { applyStroke(target, it) }
        }
    }

    private fun applyStroke(target: Bitmap, stroke: BrushStroke) {
        if (stroke.shape != ShapeType.NONE && stroke.points.size >= 2) {
            val p1 = stroke.points.first()
            val p2 = stroke.points.last()
            val x1 = p1.x * target.width
            val y1 = p1.y * target.height
            val x2 = p2.x * target.width
            val y2 = p2.y * target.height
            val strokeWidth = stroke.radius * target.width
            renderShape(Canvas(target), stroke, x1, y1, x2, y2, strokeWidth)
            return
        }
        if (stroke.effect == BrushEffect.COLOR) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                strokeWidth = stroke.radius * target.width; color = stroke.color
            }
            val path = Path()
            stroke.points.forEachIndexed { index, point ->
                val x = point.x * target.width; val y = point.y * target.height
                if (index == 0) { path.moveTo(x, y); path.lineTo(x + .1f, y + .1f) } else path.lineTo(x, y)
            }
            Canvas(target).drawPath(path, paint)
            return
        }
        val effect = if (stroke.effect == BrushEffect.PIXELATE) createPixelatedBitmap(target, stroke.strength)
            else createBlurredBitmap(target, stroke.strength)
        val shader = BitmapShader(effect, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = stroke.radius * target.width; this.shader = shader
        }
        val path = Path()
        stroke.points.forEachIndexed { index, point ->
            val x = point.x * target.width; val y = point.y * target.height
            if (index == 0) { path.moveTo(x, y); path.lineTo(x + .1f, y + .1f) } else path.lineTo(x, y)
        }
        Canvas(target).drawPath(path, paint); effect.recycle()
    }
}

internal fun createPixelatedBitmap(source: Bitmap, strength: Int): Bitmap {
    val block = strength.coerceAtLeast(2)
    val tiny = Bitmap.createScaledBitmap(source, (source.width / block).coerceAtLeast(1),
        (source.height / block).coerceAtLeast(1), false)
    return Bitmap.createScaledBitmap(tiny, source.width, source.height, false).also { tiny.recycle() }
}

internal fun createBlurredBitmap(source: Bitmap, strength: Int): Bitmap {
    val down = maxOf(3, maxOf(source.width, source.height) / 1200)
    val width = (source.width / down).coerceAtLeast(1); val height = (source.height / down).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(source, width, height, true).copy(Bitmap.Config.ARGB_8888, true)
    val pixels = IntArray(width * height); small.getPixels(pixels, 0, width, 0, 0, width, height)
    val radius = (strength / down).coerceIn(1, 18)
    repeat(3) { boxBlur(pixels, width, height, radius) }
    small.setPixels(pixels, 0, width, 0, 0, width, height)
    return Bitmap.createScaledBitmap(small, source.width, source.height, true).also { small.recycle() }
}

private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
    val source = pixels.copyOf(); val diameter = radius * 2 + 1
    for (y in 0 until height) {
        var a=0; var r=0; var g=0; var b=0
        for (dx in -radius..radius) { val c=source[y*width+dx.coerceIn(0,width-1)]
            a+=c ushr 24; r+=c shr 16 and 255; g+=c shr 8 and 255; b+=c and 255 }
        for (x in 0 until width) {
            pixels[y*width+x]=(a/diameter shl 24) or (r/diameter shl 16) or (g/diameter shl 8) or b/diameter
            val remove=source[y*width+(x-radius).coerceIn(0,width-1)]
            val add=source[y*width+(x+radius+1).coerceIn(0,width-1)]
            a+=(add ushr 24)-(remove ushr 24); r+=(add shr 16 and 255)-(remove shr 16 and 255)
            g+=(add shr 8 and 255)-(remove shr 8 and 255); b+=(add and 255)-(remove and 255)
        }
    }
    val horizontal = pixels.copyOf()
    for (x in 0 until width) {
        var a=0; var r=0; var g=0; var b=0
        for (dy in -radius..radius) { val c=horizontal[dy.coerceIn(0,height-1)*width+x]
            a+=c ushr 24; r+=c shr 16 and 255; g+=c shr 8 and 255; b+=c and 255 }
        for (y in 0 until height) {
            pixels[y*width+x]=(a/diameter shl 24) or (r/diameter shl 16) or (g/diameter shl 8) or b/diameter
            val remove=horizontal[(y-radius).coerceIn(0,height-1)*width+x]
            val add=horizontal[(y+radius+1).coerceIn(0,height-1)*width+x]
            a+=(add ushr 24)-(remove ushr 24); r+=(add shr 16 and 255)-(remove shr 16 and 255)
            g+=(add shr 8 and 255)-(remove shr 8 and 255); b+=(add and 255)-(remove and 255)
        }
    }
}

internal fun renderShape(
    canvas: Canvas,
    stroke: BrushStroke,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    strokeWidth: Float
) {
    val isFilled = stroke.filled && (stroke.shape == ShapeType.RECTANGLE || stroke.shape == ShapeType.OVAL)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stroke.color
        style = if (isFilled) Paint.Style.FILL else Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    when (stroke.shape) {
        ShapeType.RECTANGLE -> {
            val left = minOf(x1, x2)
            val top = minOf(y1, y2)
            val right = maxOf(x1, x2)
            val bottom = maxOf(y1, y2)
            val rect = RectF(left, top, right, bottom)
            val cornerRadius = (strokeWidth * 1.2f).coerceIn(4f, 32f)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        }
        ShapeType.OVAL -> {
            val left = minOf(x1, x2)
            val top = minOf(y1, y2)
            val right = maxOf(x1, x2)
            val bottom = maxOf(y1, y2)
            val rect = RectF(left, top, right, bottom)
            canvas.drawOval(rect, paint)
        }
        ShapeType.ARROW -> {
            drawArrow(canvas, x1, y1, x2, y2, paint, strokeWidth)
        }
        ShapeType.LINE -> {
            val linePaint = Paint(paint).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(x1, y1, x2, y2, linePaint)
        }
        else -> {}
    }
}

internal fun drawArrow(
    canvas: Canvas,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    paint: Paint,
    strokeWidth: Float
) {
    val dx = (x2 - x1).toDouble()
    val dy = (y2 - y1).toDouble()
    val length = kotlin.math.hypot(dx, dy)
    if (length < 2f) return

    val angle = kotlin.math.atan2(dy, dx)
    val headSize = (strokeWidth * 4f).coerceIn(16f, (length * 0.45).toFloat())
    val arrowAngle = Math.toRadians(28.0)

    val tipX = x2
    val tipY = y2

    val shaftEndX = (tipX - (headSize * 0.7f * kotlin.math.cos(angle)).toFloat())
    val shaftEndY = (tipY - (headSize * 0.7f * kotlin.math.sin(angle)).toFloat())

    val linePaint = Paint(paint).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawLine(x1, y1, shaftEndX, shaftEndY, linePaint)

    val wing1X = tipX - headSize * kotlin.math.cos(angle - arrowAngle).toFloat()
    val wing1Y = tipY - headSize * kotlin.math.sin(angle - arrowAngle).toFloat()
    val wing2X = tipX - headSize * kotlin.math.cos(angle + arrowAngle).toFloat()
    val wing2Y = tipY - headSize * kotlin.math.sin(angle + arrowAngle).toFloat()

    val path = Path().apply {
        moveTo(tipX, tipY)
        lineTo(wing1X, wing1Y)
        lineTo(shaftEndX, shaftEndY)
        lineTo(wing2X, wing2Y)
        close()
    }
    val headPaint = Paint(paint).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        this.strokeWidth = (strokeWidth * 0.4f).coerceAtLeast(1f)
    }
    canvas.drawPath(path, headPaint)
}

internal fun isPointNearStrokeOrShape(point: BrushPoint, stroke: BrushStroke, threshold: Float): Boolean {
    if (stroke.shape == ShapeType.NONE) {
        return stroke.points.any { hypot(it.x - point.x, it.y - point.y) < threshold }
    }
    if (stroke.points.size < 2) return false
    val p1 = stroke.points.first()
    val p2 = stroke.points.last()
    val left = minOf(p1.x, p2.x) - threshold
    val right = maxOf(p1.x, p2.x) + threshold
    val top = minOf(p1.y, p2.y) - threshold
    val bottom = maxOf(p1.y, p2.y) + threshold

    if (point.x !in left..right || point.y !in top..bottom) return false

    return when (stroke.shape) {
        ShapeType.RECTANGLE -> {
            if (stroke.filled) {
                true
            } else {
                val minX = minOf(p1.x, p2.x)
                val maxX = maxOf(p1.x, p2.x)
                val minY = minOf(p1.y, p2.y)
                val maxY = maxOf(p1.y, p2.y)
                val nearHoriz = (kotlin.math.abs(point.y - minY) < threshold || kotlin.math.abs(point.y - maxY) < threshold) && point.x in (minX - threshold)..(maxX + threshold)
                val nearVert = (kotlin.math.abs(point.x - minX) < threshold || kotlin.math.abs(point.x - maxX) < threshold) && point.y in (minY - threshold)..(maxY + threshold)
                nearHoriz || nearVert
            }
        }
        ShapeType.OVAL -> {
            val cx = (p1.x + p2.x) / 2f
            val cy = (p1.y + p2.y) / 2f
            val rx = kotlin.math.abs(p2.x - p1.x) / 2f
            val ry = kotlin.math.abs(p2.y - p1.y) / 2f
            if (rx < 0.001f || ry < 0.001f) return false
            val normDist = ((point.x - cx) * (point.x - cx)) / (rx * rx) + ((point.y - cy) * (point.y - cy)) / (ry * ry)
            if (stroke.filled) {
                normDist <= 1.0f
            } else {
                kotlin.math.abs(kotlin.math.sqrt(normDist) - 1.0f) < (threshold / minOf(rx, ry).coerceAtLeast(0.01f))
            }
        }
        ShapeType.ARROW, ShapeType.LINE -> {
            distToSegment(point.x, point.y, p1.x, p1.y, p2.x, p2.y) < threshold
        }
        else -> false
    }
}

private fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x2 - x1
    val dy = y2 - y1
    val lenSq = dx * dx + dy * dy
    if (lenSq < 1e-6f) return hypot(px - x1, py - y1)
    val t = ((px - x1) * dx + (py - y1) * dy) / lenSq
    val clampedT = t.coerceIn(0f, 1f)
    val projX = x1 + clampedT * dx
    val projY = y1 + clampedT * dy
    return hypot(px - projX, py - projY)
}
