package app.cheessee.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import app.cheessee.brain.Coach
import app.cheessee.brain.Hint
import app.cheessee.brain.Label
import app.cheessee.brain.Threats
import app.cheessee.core.Position
import app.cheessee.vision.ScreenReader
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The transparent full-screen layer drawn on top of Lichess:
 * thin straight hint arrows (4 colours), dotted threat arrows, thick hand-drawn arrows (knights bend in an L),
 * eval bar + number, and the move-quality sticker.
 *
 * Everything translucent is drawn at <= 80% opacity so the screen reader can divide it back out.
 */
class OverlayView(ctx: Context) : View(ctx) {
    class UserArrow(val from: Int, val to: Int, val color: Int)

    // ---- board position on screen (pixels) and orientation
    var bx = 0f
    var by = 0f
    var size = 0f
    var whiteBottom = true

    // ---- what to draw
    @Volatile
    var report: Coach.Report? = null
    var showHints = true
    var showAgainst = true
    var showMine = true
    var showLoose = false
    var showEval = true
    var showStickers = true
    var statusText: String? = null

    var calibMode = false
    var drawMode = false
    var drawColor = ARROW_COLORS[0]
    val userArrows = ArrayList<UserArrow>()
    val marks = ArrayList<Pair<Int, Int>>()
    var dots: List<Int> = emptyList()
    var onSquareTap: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var startSq = -1
    private var curSq = -1
    private var lastX = 0f
    private var lastY = 0f
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val ns = (size * det.scaleFactor).coerceIn(200f, width.toFloat())
            bx = det.focusX - (det.focusX - bx) * ns / size
            by = det.focusY - (det.focusY - by) * ns / size
            size = ns
            invalidate()
            return true
        }
    })

    fun hasContent(): Boolean =
        size > 0f && (calibMode || report != null || userArrows.isNotEmpty() || marks.isNotEmpty() || dots.isNotEmpty() || statusText != null)

    fun clearUser() {
        userArrows.clear()
        marks.clear()
        dots = emptyList()
        invalidate()
    }

    // ------------------------------------------------------------------ geometry

    private fun cx(sq: Int): Float = bx + (ScreenReader.squareToScreen(sq, whiteBottom) % 8 + 0.5f) * size / 8f
    private fun cy(sq: Int): Float = by + (ScreenReader.squareToScreen(sq, whiteBottom) / 8 + 0.5f) * size / 8f

    private fun squareAt(x: Float, y: Float): Int {
        if (size <= 0f || x < bx || y < by || x >= bx + size || y >= by + size) return -1
        val s = size / 8f
        val col = ((x - bx) / s).toInt().coerceIn(0, 7)
        val row = ((y - by) / s).toInt().coerceIn(0, 7)
        return ScreenReader.screenToSquare(row * 8 + col, whiteBottom)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        if (size <= 0f) {
            drawStatus(c)
            return
        }
        if (calibMode) {
            drawCalib(c)
            return
        }
        val rep = report
        if (rep != null) {
            if (showEval) drawEval(c, rep)
            if (showLoose) {
                for (sq in rep.looseMine) drawRing(c, sq, 0xFFFFB020.toInt())
                for (sq in rep.looseTheirs) drawRing(c, sq, 0xFFB06CFF.toInt())
            }
        }
        for (m in marks) drawMark(c, m.first, m.second)
        if (rep != null) {
            if (showAgainst) for (t in rep.against) drawThreat(c, t, AGAINST)
            if (showMine) for (t in rep.mine) drawThreat(c, t, MINE)
            val pred = rep.predicted
            if (showHints && pred != null && pred.length >= 4) {
                val a = Position.sqFromName(pred.substring(0, 2))
                val b = Position.sqFromName(pred.substring(2, 4))
                if (a >= 0 && b >= 0) drawDotted(c, a, b, 0xFF9AA0A6.toInt(), size / 8f * 0.06f, 150)
            }
            if (showHints) drawHints(c, rep)
        }
        for (u in userArrows) drawUser(c, u.from, u.to, u.color)
        if (drawMode && startSq >= 0 && curSq >= 0 && curSq != startSq) drawUser(c, startSq, curSq, drawColor)
        for (sq in dots) drawDot(c, sq)
        if (rep != null && showStickers) {
            val lab = rep.label
            if (lab != null && rep.labelSquare >= 0) drawSticker(c, lab.label, rep.labelSquare)
        }
        drawStatus(c)
    }

    private fun resetPaint(style: Paint.Style, color: Int, alpha: Int) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = style
        paint.color = color
        paint.alpha = alpha
    }

    /** One filled polygon (shaft + head) so translucency stays even. */
    private fun filledArrow(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, w: Float, hl: Float, hw: Float, color: Int, alpha: Int) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx, dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        val nx = -uy
        val ny = ux
        val hl2 = min(hl, len * 0.9f)
        val bxp = x2 - ux * hl2
        val byp = y2 - uy * hl2
        path.reset()
        path.moveTo(x1 + nx * w / 2, y1 + ny * w / 2)
        path.lineTo(bxp + nx * w / 2, byp + ny * w / 2)
        path.lineTo(bxp + nx * hw / 2, byp + ny * hw / 2)
        path.lineTo(x2, y2)
        path.lineTo(bxp - nx * hw / 2, byp - ny * hw / 2)
        path.lineTo(bxp - nx * w / 2, byp - ny * w / 2)
        path.lineTo(x1 - nx * w / 2, y1 - ny * w / 2)
        path.close()
        resetPaint(Paint.Style.FILL, color, alpha)
        c.drawPath(path, paint)
    }

    /** Thin, straight hint arrows. Best is drawn last so it sits on top. */
    private fun drawHints(c: Canvas, rep: Coach.Report) {
        val s = size / 8f
        for (h in rep.hints.reversed()) {
            val col = when (h.kind) {
                Hint.Kind.BEST -> HINT_BEST
                Hint.Kind.GOOD -> HINT_GOOD
                Hint.Kind.TRICKY -> HINT_TRICKY
                Hint.Kind.BRILLIANT -> HINT_BRILLIANT
            }
            val x1 = cx(h.from())
            val y1 = cy(h.from())
            val x2 = cx(h.to())
            val y2 = cy(h.to())
            filledArrow(c, x1, y1, x2, y2, s * 0.075f, s * 0.32f, s * 0.3f, col, 190)
            if (h.trap) pill(c, (x1 + x2) / 2, (y1 + y2) / 2, "TRAP", col, s * 0.16f, 0xFF202020.toInt())
        }
    }

    private fun drawDotted(c: Canvas, a: Int, b: Int, color: Int, width: Float, alpha: Int) {
        val x1 = cx(a)
        val y1 = cy(a)
        val x2 = cx(b)
        val y2 = cy(b)
        val len = hypot(x2 - x1, y2 - y1)
        if (len < 1f) return
        val s = size / 8f
        val ux = (x2 - x1) / len
        val uy = (y2 - y1) / len
        val hl = s * 0.3f
        resetPaint(Paint.Style.STROKE, color, alpha)
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.pathEffect = DashPathEffect(floatArrayOf(0.1f, width * 2.1f), 0f)
        c.drawLine(x1, y1, x2 - ux * hl, y2 - uy * hl, paint)
        paint.pathEffect = null
        val nx = -uy
        val ny = ux
        path.reset()
        path.moveTo(x2, y2)
        path.lineTo(x2 - ux * hl + nx * hl * 0.5f, y2 - uy * hl + ny * hl * 0.5f)
        path.lineTo(x2 - ux * hl - nx * hl * 0.5f, y2 - uy * hl - ny * hl * 0.5f)
        path.close()
        resetPaint(Paint.Style.FILL, color, alpha)
        c.drawPath(path, paint)
    }

    private fun drawThreat(c: Canvas, t: Threats.Threat, color: Int) {
        val s = size / 8f
        // dotted lines from the landing square to what the idea is aimed at (forked pieces, pinned piece, ...)
        for (target in t.targets) {
            if (target == t.to) {
                drawRing(c, target, color)
                continue
            }
            resetPaint(Paint.Style.STROKE, color, 140)
            paint.strokeWidth = s * 0.05f
            paint.strokeCap = Paint.Cap.ROUND
            paint.pathEffect = DashPathEffect(floatArrayOf(0.1f, s * 0.12f), 0f)
            c.drawLine(cx(t.to), cy(t.to), cx(target), cy(target), paint)
            paint.pathEffect = null
            drawRing(c, target, color)
        }
        drawDotted(c, t.from, t.to, color, s * 0.075f, 185)
        val tags = t.tags().take(2)
        if (tags.isNotEmpty()) {
            val mx = (cx(t.from) + cx(t.to)) / 2
            val my = (cy(t.from) + cy(t.to)) / 2
            pill(c, mx, my, tags.joinToString("+"), color, s * 0.16f, 0xFFFFFFFF.toInt())
        }
    }

    private fun drawRing(c: Canvas, sq: Int, color: Int) {
        val s = size / 8f
        resetPaint(Paint.Style.STROKE, color, 200)
        paint.strokeWidth = s * 0.06f
        paint.strokeCap = Paint.Cap.ROUND
        paint.pathEffect = DashPathEffect(floatArrayOf(0.1f, s * 0.1f), 0f)
        c.drawCircle(cx(sq), cy(sq), s * 0.44f, paint)
    }

    private fun pill(c: Canvas, x: Float, y: Float, text: String, bg: Int, textSize: Float, fg: Int) {
        resetPaint(Paint.Style.FILL, fg, 255)
        paint.textSize = textSize
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        val w = paint.measureText(text) + textSize * 0.9f
        val h = textSize * 1.5f
        val r = RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = bg
        fill.alpha = 205
        c.drawRoundRect(r, h / 2, h / 2, fill)
        val fm = paint.fontMetrics
        c.drawText(text, x, y - (fm.ascent + fm.descent) / 2, paint)
    }

    /** Thick, desktop-style arrow drawn with a finger. Knight moves bend in an L with a rounded corner. */
    private fun drawUser(c: Canvas, from: Int, to: Int, color: Int) {
        val s = size / 8f
        val x1 = cx(from)
        val y1 = cy(from)
        val x2 = cx(to)
        val y2 = cy(to)
        val dxs = (abs(x2 - x1) / s).roundToInt()
        val dys = (abs(y2 - y1) / s).roundToInt()
        val knight = (dxs == 1 && dys == 2) || (dxs == 2 && dys == 1)
        val layer = c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 205)
        if (!knight) {
            filledArrow(c, x1, y1, x2, y2, s * 0.2f, s * 0.5f, s * 0.52f, color, 255)
        } else {
            val kx = if (dys > dxs) x1 else x2
            val ky = if (dys > dxs) y2 else y1
            val ll = hypot(x2 - kx, y2 - ky)
            val ux = (x2 - kx) / ll
            val uy = (y2 - ky) / ll
            val hl = s * 0.5f
            path.reset()
            path.moveTo(x1, y1)
            path.lineTo(kx, ky)
            path.lineTo(x2 - ux * hl * 0.8f, y2 - uy * hl * 0.8f)
            resetPaint(Paint.Style.STROKE, color, 255)
            paint.strokeWidth = s * 0.2f
            paint.strokeJoin = Paint.Join.ROUND
            paint.pathEffect = CornerPathEffect(s * 0.28f)
            c.drawPath(path, paint)
            val bxp = x2 - ux * hl
            val byp = y2 - uy * hl
            val nx = -uy
            val ny = ux
            path.reset()
            path.moveTo(x2, y2)
            path.lineTo(bxp + nx * s * 0.26f, byp + ny * s * 0.26f)
            path.lineTo(bxp - nx * s * 0.26f, byp - ny * s * 0.26f)
            path.close()
            resetPaint(Paint.Style.FILL, color, 255)
            c.drawPath(path, paint)
        }
        c.restoreToCount(layer)
    }

    private fun drawMark(c: Canvas, sq: Int, color: Int) {
        val s = size / 8f
        resetPaint(Paint.Style.FILL, color, 120)
        c.drawRect(cx(sq) - s / 2, cy(sq) - s / 2, cx(sq) + s / 2, cy(sq) + s / 2, paint)
    }

    private fun drawDot(c: Canvas, sq: Int) {
        val s = size / 8f
        resetPaint(Paint.Style.FILL, 0xFF14551E.toInt(), 150)
        c.drawCircle(cx(sq), cy(sq), s * 0.17f, paint)
    }

    private fun drawEval(c: Canvas, rep: Coach.Report) {
        val s = size / 8f
        val bw = max(5f * d, s * 0.08f)
        val frac = (rep.whiteWin / 100.0).toFloat().coerceIn(0.02f, 0.98f)
        val whiteH = size * frac
        val whiteTop = if (whiteBottom) by + size - whiteH else by
        val darkTop = if (whiteBottom) by else by + whiteH
        resetPaint(Paint.Style.FILL, 0xFFF4F4F4.toInt(), 200)
        c.drawRect(bx, whiteTop, bx + bw, whiteTop + whiteH, paint)
        resetPaint(Paint.Style.FILL, 0xFF2B2A28.toInt(), 200)
        c.drawRect(bx, darkTop, bx + bw, darkTop + size - whiteH, paint)
        val text = rep.evalText
        val white = text.startsWith("+") || text.startsWith("#")
        val py = if (by > 46f * d) by - 20f * d else by + 16f * d
        pill(c, bx + 36f * d, py, text, if (white) 0xFFF0F0F0.toInt() else 0xFF2B2A28.toInt(), 13f * d, if (white) 0xFF1B1B1B.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun drawSticker(c: Canvas, label: Label, sq: Int) {
        val s = size / 8f
        val x = cx(sq) + s * 0.34f
        val y = cy(sq) - s * 0.34f
        val r = s * 0.21f
        resetPaint(Paint.Style.FILL, label.color, 255)
        c.drawCircle(x, y, r, paint)
        resetPaint(Paint.Style.STROKE, 0xFFFFFFFF.toInt(), 255)
        paint.strokeWidth = max(1.5f, s * 0.03f)
        c.drawCircle(x, y, r, paint)
        resetPaint(Paint.Style.FILL, 0xFFFFFFFF.toInt(), 255)
        when (label.glyph) {
            "star" -> {
                path.reset()
                for (i in 0 until 10) {
                    val rad = if (i % 2 == 0) r * 0.7f else r * 0.3f
                    val ang = Math.toRadians(-90.0 + i * 36.0)
                    val px = x + (rad * Math.cos(ang)).toFloat()
                    val py = y + (rad * Math.sin(ang)).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                c.drawPath(path, paint)
            }
            "check", "thumb" -> {
                resetPaint(Paint.Style.STROKE, 0xFFFFFFFF.toInt(), 255)
                paint.strokeWidth = r * 0.32f
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                path.reset()
                path.moveTo(x - r * 0.45f, y + r * 0.02f)
                path.lineTo(x - r * 0.1f, y + r * 0.38f)
                path.lineTo(x + r * 0.5f, y - r * 0.38f)
                c.drawPath(path, paint)
            }
            "x" -> {
                resetPaint(Paint.Style.STROKE, 0xFFFFFFFF.toInt(), 255)
                paint.strokeWidth = r * 0.32f
                paint.strokeCap = Paint.Cap.ROUND
                c.drawLine(x - r * 0.4f, y - r * 0.4f, x + r * 0.4f, y + r * 0.4f, paint)
                c.drawLine(x - r * 0.4f, y + r * 0.4f, x + r * 0.4f, y - r * 0.4f, paint)
            }
            else -> {
                val text = if (label.glyph == "book") "B" else label.glyph
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = if (text.length > 1) r * 1.0f else r * 1.25f
                val fm = paint.fontMetrics
                c.drawText(text, x, y - (fm.ascent + fm.descent) / 2, paint)
            }
        }
    }

    private fun drawStatus(c: Canvas) {
        val t = statusText ?: return
        pill(c, width / 2f, 64f * d, t, 0xFF14161C.toInt(), 13f * d, 0xFFFFFFFF.toInt())
    }

    private fun drawCalib(c: Canvas) {
        val s = size / 8f
        resetPaint(Paint.Style.STROKE, 0xFF00E5FF.toInt(), 200)
        paint.strokeWidth = 3f
        c.drawRect(bx, by, bx + size, by + size, paint)
        paint.strokeWidth = 1.5f
        paint.alpha = 120
        for (i in 1 until 8) {
            c.drawLine(bx + i * s, by, bx + i * s, by + size, paint)
            c.drawLine(bx, by + i * s, bx + size, by + i * s, paint)
        }
        pill(c, width / 2f, 64f * d, "Fit the frame to the board (drag / pinch), then tap ✓", 0xFF14161C.toInt(), 12f * d, 0xFFFFFFFF.toInt())
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (calibMode) return calibTouch(e)
        if (!drawMode) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startSq = squareAt(e.x, e.y)
                curSq = startSq
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                curSq = squareAt(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (startSq >= 0) {
                    if (curSq < 0 || curSq == startSq) tap(startSq) else toggleArrow(startSq, curSq)
                }
                startSq = -1
                curSq = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                startSq = -1
                curSq = -1
                invalidate()
            }
        }
        return true
    }

    private fun tap(sq: Int) {
        val i = marks.indexOfFirst { it.first == sq }
        if (i >= 0) marks.removeAt(i) else marks.add(Pair(sq, drawColor))
        onSquareTap?.invoke(sq)
    }

    private fun toggleArrow(a: Int, b: Int) {
        val i = userArrows.indexOfFirst { it.from == a && it.to == b }
        if (i >= 0) userArrows.removeAt(i) else userArrows.add(UserArrow(a, b, drawColor))
    }

    private fun calibTouch(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scaler.isInProgress) {
                bx += e.x - lastX
                by += e.y - lastY
                lastX = e.x
                lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                lastX = e.x
                lastY = e.y
            }
        }
        return true
    }

    companion object {
        val ARROW_COLORS = intArrayOf(0xFFFFAA00.toInt(), 0xFF2EC27E.toInt(), 0xFFFF5555.toInt(), 0xFF4DA3FF.toInt(), 0xFFFFE14D.toInt())
        val HINT_BEST = 0xFF138A36.toInt()
        val HINT_GOOD = 0xFF7ED957.toInt()
        val HINT_TRICKY = 0xFFFFD43B.toInt()
        val HINT_BRILLIANT = 0xFF26C6F9.toInt()
        val AGAINST = 0xFFFF4D4D.toInt()
        val MINE = 0xFFB06CFF.toInt()
    }
}
