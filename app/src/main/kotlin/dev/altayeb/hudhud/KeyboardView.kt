package dev.altayeb.hudhud

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Draws the whole keyboard on one Canvas and turns touches into key codes. Main thread only. */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        /** A key was tapped (also repeated while DELETE is held). */
        fun onKey(code: Int)
        /** A long-press picked [text] (a hint or a popup choice); commit it. */
        fun onLongPress(text: String)
        /** Long-press on a special key: SPACE = switch keyboard app, SYMBOLS = settings. */
        fun onSpecialLongPress(code: Int)
        /** Finger slid sideways on space: [dir] is +1 or -1 → next/previous language. */
        fun onSwipeSpace(dir: Int)
        /** Held then dragged on space: move the cursor one step left (-1) or right (+1) on screen. */
        fun onCursor(dir: Int)
        /** Slid sideways on delete: remove one more word. */
        fun onDeleteWord()
        /** Finished glide in key units, with where each letter's keys are in the same units. */
        fun onGlide(path: FloatArray, centers: Map<Char, List<FloatArray>>)
        /** One-handed side changed from the keyboard: -1 left, 0 off, +1 right. */
        fun onHand(side: Int)
    }

    var listener: Listener? = null
    var shifted: Boolean = false
        set(v) { field = v; invalidate() }
    var glideEnabled = false
    /** Keys part in the middle for two thumbs. Sizes shrink to make room, like one-handed mode. */
    var split = false
        set(v) { if (field != v) { field = v; computeRects(); invalidate() } }
    var swipeEdit = false   // hold + drag on space moves the cursor, slide on delete removes words
    /** One-handed: -1 keys on the left, +1 on the right, 0 full width. Only key widths change. */
    var hand = 0
        set(v) { if (field != v) { field = v; computeRects(); invalidate() } }

    // dp → px. Density, not sp, so the system font scale does not change the keyboard.
    private val density = resources.displayMetrics.density
    private val sidePad = 1f * density
    private val topPad = 4f * density
    private val bottomPad = 6f * density
    private val hGap = 4.3f * density
    private val vGap = 6f * density
    private var corner = 5f * density
    private var drawEdge = true      // theme: 3D bottom strip (classic) or flat (material)
    private var pressScale = 1f      // theme: < 1 = held key shrinks, springs back on release
    private val edge = 2.5f * density       // darker bottom strip = 3D key
    private val hintInset = 4f * density
    private val swipeStep = 32f * density

    private var bgColor = Color.BLACK
    private var keyColor = KEY_COLOR
    private val keyPaint = fill("#474747")
    private val specialPaint = fill("#474747")
    private val edgePaint = fill("#2E2E2E")
    private val pressedPaint = fill("#6B6B6B")
    private val shiftOnPaint = fill("#33B5E5")
    private val popupPaint = fill("#2C2D2F")
    private val labelPaint = text("#FFFFFF", Paint.Align.CENTER)
    private val specialLabelPaint = text("#71E2FB", Paint.Align.CENTER)
    private val shiftOnLabelPaint = text("#000000", Paint.Align.CENTER)
    private val spaceLabelPaint = text("#71E2FB", Paint.Align.CENTER)
    private val popupSelLabelPaint = text("#000000", Paint.Align.CENTER)
    private val hintPaint = text("#9E9E9E", Paint.Align.RIGHT).apply { textSize = 13f * density }
    // Icons for shift / delete / enter / emoji, drawn as paths (bolder than font glyphs, no colour emoji).
    private val iconFill = fill("#71E2FB")
    private val iconOnCyan = fill("#000000")
    private val iconCut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = KEY_COLOR; style = Paint.Style.STROKE
        strokeWidth = 2.2f * density; strokeCap = Paint.Cap.ROUND
    }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#71E2FB"); style = Paint.Style.STROKE
        strokeWidth = 2.6f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val spacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#71E2FB")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val rectF = RectF()
    private val path = Path()
    private val fm = Paint.FontMetrics()

    // Flattened keys: keys[i] has rect rects[4i..4i+3]; row r = keys[rowStart[r] until rowStart[r+1]].
    private var keys: Array<Key> = emptyArray()
    private var lower: Array<String> = emptyArray()       // labels drawn when not shifted
    private var popups: Array<List<String>> = emptyArray()
    private var labelSize = FloatArray(0)                 // per key, shrunk so wide glyphs (ض ص ش) fit
    private var rowStart = IntArray(0)
    private var rects = FloatArray(0)
    private var keyH = 0f
    private var showHints = false
    private var vibrate = false
    private var spaceLabel = ""

    private var pressedIndex = -1
    private var pressedPointer = -1
    private var longFired = false
    private var downX = 0f
    private var downY = 0f

    private var keyUnit = 1f     // one key across, gap included: the unit a glide is measured in
    private var spaceHeld = false // space was held still long enough: a drag now moves the cursor

    // Glide: the finger's path in px; drawn as a trail, handed over in key units on release.
    private var gliding = false
    private val glidePts = FloatArray(MAX_GLIDE_POINTS * 2)
    private var glideCount = 0
    private val trail = Path()
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    // Long-press popup: choices of key [popupKey], drawn in [popupRect], [popupSel] under the finger.
    private var popupKey = -1
    private var popupSel = 0
    private val popupRect = RectF()
    private var popupCell = 0f
    private var popupUnit = 0f   // normal cell; a popup with many choices shrinks its cell to fit the screen

    /** Off = no motion at all (setting "Animations"). */
    var animations = true

    // Released key fades from the pressed colour back to normal.
    private var fadeIndex = -1
    private var fadeT = 0f
    private val fadeAnim = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = Motion.FAST
        interpolator = Motion.DECELERATE
        addUpdateListener { fadeT = it.animatedValue as Float; invalidate() }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) { fadeIndex = -1 }
        })
    }

    // Long-press popup grows out of its key.
    private var popupScale = 1f
    private val popupAnim = ValueAnimator.ofFloat(0.6f, 1f).apply {
        duration = Motion.NORMAL
        interpolator = Motion.POP
        addUpdateListener { popupScale = it.animatedValue as Float; invalidate() }
    }

    private val touchHandler = Handler(Looper.getMainLooper())
    private val longPressTask = Runnable {
        val i = pressedIndex
        if (i < 0) return@Runnable
        val code = keys[i].code
        val choices = popups[i]
        longFired = true
        when {
            code == Codes.SPACE || code == Codes.SYMBOLS || code == Codes.EMOJI -> listener?.onSpecialLongPress(code)
            choices.size == 1 -> listener?.onLongPress(choices[0])
            choices.size > 1 -> openPopup(i)
            else -> longFired = false
        }
    }
    private val spaceHoldTask = Runnable {
        spaceHeld = true
        if (vibrate) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
    private val deleteRepeat: Runnable = object : Runnable {
        override fun run() {
            listener?.onKey(Codes.DELETE)
            touchHandler.postDelayed(this, 50)
        }
    }

    /** Colours only; sizes and positions never change with the theme. */
    fun setTheme(t: KeyTheme) {
        bgColor = t.bg
        keyColor = t.special
        keyPaint.color = t.key
        specialPaint.color = t.special
        corner = t.cornerDp * density
        drawEdge = t.edge
        pressScale = t.pressScale
        edgePaint.color = t.keyEdge
        pressedPaint.color = t.pressed
        shiftOnPaint.color = t.accent
        popupPaint.color = t.panel
        labelPaint.color = t.label
        specialLabelPaint.color = t.specialLabel
        shiftOnLabelPaint.color = t.onAccent
        spaceLabelPaint.color = t.accent
        popupSelLabelPaint.color = t.onAccent
        hintPaint.color = t.hint
        spacePaint.color = t.accent
        trailPaint.color = t.accent
        iconFill.color = t.specialLabel
        iconOnCyan.color = t.onAccent
        iconCut.color = t.special
        iconStroke.color = t.specialLabel
        invalidate()
    }

    /** Apply a layout and display settings; recomputes key rectangles. [spaceLabel] = language name on space. */
    fun setLayout(layout: Layout, keyHeightPercent: Int, showHints: Boolean, vibrate: Boolean, spaceLabel: String) {
        cancelGlide()
        releasePressed(fire = false)
        this.showHints = showHints
        this.vibrate = vibrate
        // LRM keeps the arrows in place around an Arabic name.
        this.spaceLabel = if (spaceLabel.isEmpty()) "" else "\u200E◂  $spaceLabel  ▸"
        keyH = 67f * density * keyHeightPercent / 100f // 67dp = Ginger at 100%
        // Landscape has no room for portrait-height rows: the keys may take about half the screen height, strip aside.
        val dm = resources.displayMetrics
        if (dm.widthPixels > dm.heightPixels && layout.rows.isNotEmpty()) {
            keyH = minOf(keyH, dm.heightPixels * 0.5f / layout.rows.size - vGap)
        }

        val flat = ArrayList<Key>()
        rowStart = IntArray(layout.rows.size + 1)
        layout.rows.forEachIndexed { r, row -> flat.addAll(row); rowStart[r + 1] = flat.size }
        keys = flat.toTypedArray()
        lower = Array(keys.size) { i ->
            val k = keys[i]
            if (k.code > 0 && Character.isLetter(k.code)) k.label.lowercase() else k.label
        }
        popups = Array(keys.size) { keys[it].popup }
        labelSize = FloatArray(keys.size) { keyH * 0.46f } // until the width is known
        rects = FloatArray(keys.size * 4)

        val normal = keyH * 0.46f
        labelPaint.textSize = normal
        specialLabelPaint.textSize = normal * 0.8f
        shiftOnLabelPaint.textSize = normal * 0.8f
        popupSelLabelPaint.textSize = normal
        spaceLabelPaint.textSize = keyH * 0.24f

        computeRects()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val rows = rowStart.size - 1
        val content = if (rows > 0) rows * keyH + (rows - 1) * vGap else 0f
        val h = topPad + bottomPad + content
        setMeasuredDimension(View.MeasureSpec.getSize(widthSpec), h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeRects()
    }

    /** Each row gets the same unit width: the tightest row decides, looser rows are centered. */
    private fun computeRects() {
        val rows = rowStart.size - 1
        val w = width.toFloat()
        if (rows <= 0 || w <= 0f) return
        val full = w - 2 * sidePad
        val avail = if (hand == 0) full else full * ONE_HAND_WIDTH
        val startX = if (hand > 0) w - sidePad - avail else sidePad
        val gap = if (split && hand == 0) w * SPLIT_GAP else 0f

        var unit = Float.MAX_VALUE
        for (r in 0 until rows) {
            var units = 0f
            for (i in rowStart[r] until rowStart[r + 1]) units += keys[i].width
            val gaps = (rowStart[r + 1] - rowStart[r] - 1) * hGap
            if (units > 0f) unit = minOf(unit, (avail - gap - gaps) / units)
        }
        popupUnit = unit
        popupCell = unit
        keyUnit = unit + hGap

        for (r in 0 until rows) {
            val start = rowStart[r]
            val end = rowStart[r + 1]
            var units = 0f
            for (i in start until end) units += keys[i].width
            val rowW = units * unit + (end - start - 1) * hGap + gap
            var x = startX + (avail - rowW) / 2f
            var done = 0f // units laid out so far: the gap goes in once half the row is down
            var gapLeft = gap
            val top = topPad + r * (keyH + vGap)
            for (i in start until end) {
                // A wide key on the middle (space) bridges the gap instead of leaving a hole in the row.
                val across = gapLeft > 0f && keys[i].width >= 3f && done + keys[i].width > units / 2f
                val kw = keys[i].width * unit + if (across) gapLeft else 0f
                if (across) gapLeft = 0f
                else if (gapLeft > 0f && done + keys[i].width / 2f >= units / 2f) { x += gapLeft; gapLeft = 0f }
                done += keys[i].width
                rects[i * 4] = x
                rects[i * 4 + 1] = top
                rects[i * 4 + 2] = x + kw
                rects[i * 4 + 3] = top + keyH
                x += kw + hGap
            }
        }
        fitLabels()
    }

    /** Letter size per key: normal size, shrunk when the glyph is wider than its key (never below 72%). */
    private fun fitLabels() {
        val normal = keyH * 0.46f
        labelSize = FloatArray(keys.size)
        for (i in keys.indices) {
            labelPaint.textSize = normal
            val max = (rects[i * 4 + 2] - rects[i * 4]) * 0.95f
            val w = maxOf(labelPaint.measureText(keys[i].label), labelPaint.measureText(lower[i]))
            labelSize[i] = if (w > max && w > 0f) normal * maxOf(0.72f, max / w) else normal
        }
        labelPaint.textSize = normal
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        for (i in keys.indices) {
            val k = keys[i]
            val l = rects[i * 4]
            val t = rects[i * 4 + 1]
            val rr = rects[i * 4 + 2]
            val b = rects[i * 4 + 3]
            val code = k.code
            val bg = when {
                i == pressedIndex && popupKey < 0 -> pressedPaint
                code == Codes.SHIFT && shifted -> shiftOnPaint
                code < 0 && code != Codes.SPACE -> specialPaint
                else -> keyPaint
            }
            // Expressive press: the held key is a little smaller, the released one grows back as it fades.
            val scale = when {
                pressScale >= 1f -> 1f
                i == pressedIndex && popupKey < 0 -> pressScale
                i == fadeIndex -> 1f - (1f - pressScale) * fadeT
                else -> 1f
            }
            if (scale != 1f) { canvas.save(); canvas.scale(scale, scale, (l + rr) / 2f, (t + b) / 2f) }
            val bodyBottom = if (drawEdge) b - edge else b
            if (drawEdge) {
                rectF.set(l, t + edge, rr, b)
                canvas.drawRoundRect(rectF, corner, corner, edgePaint)
            }
            rectF.set(l, t, rr, bodyBottom)
            canvas.drawRoundRect(rectF, corner, corner, bg)
            if (i == fadeIndex && fadeT > 0f && bg !== pressedPaint) {
                pressedPaint.alpha = (255 * fadeT).toInt()
                canvas.drawRoundRect(rectF, corner, corner, pressedPaint)
                pressedPaint.alpha = 255
            }

            val cx = (l + rr) / 2f
            val cy = (t + bodyBottom) / 2f
            if (code == Codes.SPACE) {
                drawSpace(canvas, cx, t, bodyBottom)
            } else if (drawIcon(canvas, code, cx, cy)) {
                // drawn
            } else if (k.label.isNotEmpty()) {
                val lp = when {
                    code == Codes.SHIFT && shifted -> shiftOnLabelPaint
                    code < 0 -> specialLabelPaint
                    else -> labelPaint
                }
                if (lp === labelPaint) lp.textSize = labelSize[i]
                lp.getFontMetrics(fm)
                val label = if (shifted) k.label else lower[i]
                canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, lp)
            }
            if (showHints && k.hint.isNotEmpty()) {
                hintPaint.getFontMetrics(fm)
                canvas.drawText(k.hint, rr - hintInset, t + hintInset - fm.ascent, hintPaint)
            }
            if (scale != 1f) canvas.restore()
        }
        if (hand != 0) drawHandBar(canvas)
        if (popupKey >= 0) drawPopup(canvas)
        if (gliding) canvas.drawPath(trail, trailPaint)
    }

    /** The free side in one-handed mode: top half moves the keys across, bottom half goes back to full width. */
    private fun drawHandBar(canvas: Canvas) {
        val w = width * (1f - ONE_HAND_WIDTH)
        val cx = if (hand > 0) w / 2f else width - w / 2f
        val s = keyH * 0.17f
        val dir = if (hand > 0) -1f else 1f // arrow points where the keys would go
        val y1 = height * 0.3f
        path.reset()
        path.moveTo(cx - dir * s * 0.5f, y1 - s)
        path.lineTo(cx + dir * s * 0.5f, y1)
        path.lineTo(cx - dir * s * 0.5f, y1 + s)
        canvas.drawPath(path, iconStroke)
        val y2 = height * 0.7f
        rectF.set(cx - s, y2 - s * 0.8f, cx + s, y2 + s * 0.8f)
        canvas.drawRoundRect(rectF, corner, corner, iconStroke)
    }

    /** True if [x] is on the free side in one-handed mode. */
    private fun onHandBar(x: Float): Boolean {
        if (hand == 0) return false
        val w = width * (1f - ONE_HAND_WIDTH)
        return if (hand > 0) x < w else x > width - w
    }

    /** Draws the icon for special keys; false = no icon, draw the text label. */
    private fun drawIcon(canvas: Canvas, code: Int, cx: Float, cy: Float): Boolean {
        val s = keyH * 0.17f
        path.reset()
        when (code) {
            Codes.SHIFT -> {
                path.moveTo(cx, cy - s)
                path.lineTo(cx + s, cy)
                path.lineTo(cx + s * 0.45f, cy)
                path.lineTo(cx + s * 0.45f, cy + s * 0.8f)
                path.lineTo(cx - s * 0.45f, cy + s * 0.8f)
                path.lineTo(cx - s * 0.45f, cy)
                path.lineTo(cx - s, cy)
                path.close()
                canvas.drawPath(path, if (shifted) iconOnCyan else iconFill)
            }
            Codes.DELETE -> {
                val h = s * 0.72f
                path.moveTo(cx - s * 1.15f, cy)
                path.lineTo(cx - s * 0.5f, cy - h)
                path.lineTo(cx + s, cy - h)
                path.lineTo(cx + s, cy + h)
                path.lineTo(cx - s * 0.5f, cy + h)
                path.close()
                canvas.drawPath(path, iconFill)
                val x = cx + s * 0.25f
                val d = s * 0.32f
                canvas.drawLine(x - d, cy - d, x + d, cy + d, iconCut)
                canvas.drawLine(x - d, cy + d, x + d, cy - d, iconCut)
            }
            Codes.ENTER -> {
                val y = cy + s * 0.25f
                path.moveTo(cx + s * 0.85f, cy - s * 0.65f)
                path.lineTo(cx + s * 0.85f, y)
                path.lineTo(cx - s * 0.85f, y)
                path.moveTo(cx - s * 0.4f, y - s * 0.45f)
                path.lineTo(cx - s * 0.85f, y)
                path.lineTo(cx - s * 0.4f, y + s * 0.45f)
                canvas.drawPath(path, iconStroke)
            }
            Codes.EMOJI -> {
                canvas.drawCircle(cx, cy, s, iconFill)
                canvas.drawCircle(cx - s * 0.35f, cy - s * 0.25f, s * 0.13f, iconOnCyan)
                canvas.drawCircle(cx + s * 0.35f, cy - s * 0.25f, s * 0.13f, iconOnCyan)
                rectF.set(cx - s * 0.55f, cy - s * 0.35f, cx + s * 0.55f, cy + s * 0.55f)
                iconCut.color = Color.BLACK
                canvas.drawArc(rectF, 25f, 130f, false, iconCut)
                iconCut.color = keyColor
            }
            else -> return false
        }
        return true
    }

    private fun drawSpace(canvas: Canvas, cx: Float, top: Float, bottom: Float) {
        val h = bottom - top
        if (spaceLabel.isNotEmpty()) {
            spaceLabelPaint.getFontMetrics(fm)
            canvas.drawText(spaceLabel, cx, top + h * 0.45f - (fm.ascent + fm.descent) / 2f, spaceLabelPaint)
        }
        val hw = 12f * density
        val hh = 3.5f * density
        val y = top + h * (if (spaceLabel.isEmpty()) 0.5f else 0.8f)
        path.reset()
        path.moveTo(cx - hw, y - hh)
        path.lineTo(cx - hw, y + hh)
        path.lineTo(cx + hw, y + hh)
        path.lineTo(cx + hw, y - hh)
        canvas.drawPath(path, spacePaint)
    }

    private fun drawPopup(canvas: Canvas) {
        val choices = popups[popupKey]
        canvas.save()
        canvas.scale(popupScale, popupScale, popupRect.centerX(), popupRect.bottom)
        canvas.drawRoundRect(popupRect, corner, corner, popupPaint)
        labelPaint.textSize = popupSelLabelPaint.textSize
        labelPaint.getFontMetrics(fm)
        val cy = (popupRect.top + popupRect.bottom) / 2f - (fm.ascent + fm.descent) / 2f
        for (j in choices.indices) {
            val l = popupRect.left + j * popupCell
            if (j == popupSel) {
                rectF.set(l + edge, popupRect.top + edge, l + popupCell - edge, popupRect.bottom - edge)
                canvas.drawRoundRect(rectF, corner, corner, shiftOnPaint)
            }
            canvas.drawText(choices[j], l + popupCell / 2f, cy, if (j == popupSel) popupSelLabelPaint else labelPaint)
        }
        canvas.restore()
    }

    /** Popup row above key [i], centred on it and kept inside the view. */
    private fun openPopup(i: Int) {
        val n = popups[i].size
        popupCell = minOf(popupUnit, (width - 2 * sidePad) / n)
        val w = n * popupCell
        val kx = (rects[i * 4] + rects[i * 4 + 2]) / 2f
        val left = (kx - popupCell / 2f).coerceIn(sidePad, maxOf(sidePad, width - sidePad - w))
        val top = (rects[i * 4 + 1] - keyH - vGap).coerceAtLeast(0f)
        popupRect.set(left, top, left + w, top + keyH)
        popupKey = i
        popupSel = 0
        if (animations) popupAnim.start() else popupScale = 1f
        if (vibrate) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val p = e.actionIndex
                if (gliding) return true // a stray second finger does not end the glide
                if (e.actionMasked == MotionEvent.ACTION_DOWN && onHandBar(e.getX(p))) {
                    listener?.onHand(if (e.getY(p) < height / 2f) -hand else 0)
                    return true
                }
                val idx = hitTest(e.getX(p), e.getY(p))
                if (idx >= 0) {
                    // Fast typing with a second finger: the first key lands now.
                    releasePressed(fire = true)
                    pressKey(idx, e.getPointerId(p), e.getX(p), e.getY(p))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val p = e.findPointerIndex(pressedPointer)
                if (p >= 0) onMove(e.getX(p), e.getY(p))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == pressedPointer) {
                    if (gliding) finishGlide() else if (pressedIndex >= 0) releasePressed(fire = true)
                }
            }
            MotionEvent.ACTION_CANCEL -> { cancelGlide(); releasePressed(fire = false) }
        }
        return true
    }

    private fun onMove(x: Float, y: Float) {
        if (gliding) { addGlidePoint(x, y); return }
        val i = pressedIndex
        if (i < 0) return
        if (popupKey >= 0) {
            val sel = ((x - popupRect.left) / popupCell).toInt().coerceIn(0, popups[popupKey].size - 1)
            if (sel != popupSel) { popupSel = sel; invalidate() }
        } else if (keys[i].code == Codes.SPACE && spaceHeld) {
            touchHandler.removeCallbacks(longPressTask)
            longFired = true
            val step = keyUnit / 3f
            while (abs(x - downX) > step) {
                val dir = if (x > downX) 1 else -1
                listener?.onCursor(dir)
                downX += dir * step
            }
        } else if (keys[i].code == Codes.DELETE && swipeEdit && abs(x - downX) > swipeStep) {
            touchHandler.removeCallbacks(deleteRepeat)
            listener?.onDeleteWord()
            downX = x
        } else if (glideEnabled && !longFired && keys[i].code > 0 && Character.isLetter(keys[i].code) &&
            abs(x - downX) + abs(y - downY) > keyUnit * GLIDE_START) {
            startGlide(x, y)
        } else if (keys[i].code == Codes.SPACE && abs(x - downX) > swipeStep) {
            touchHandler.removeCallbacks(longPressTask)
            longFired = true // the release must not type a space
            listener?.onSwipeSpace(if (x > downX) 1 else -1)
            downX = x
        }
    }

    private fun startGlide(x: Float, y: Float) {
        touchHandler.removeCallbacks(longPressTask)
        pressedIndex = -1
        gliding = true
        glideCount = 0
        trail.reset()
        trail.moveTo(downX, downY)
        addGlidePoint(downX, downY)
        addGlidePoint(x, y)
    }

    private fun addGlidePoint(x: Float, y: Float) {
        if (glideCount < MAX_GLIDE_POINTS) {
            glidePts[2 * glideCount] = x
            glidePts[2 * glideCount + 1] = y
            glideCount++
        }
        trail.lineTo(x, y)
        invalidate()
    }

    private fun finishGlide() {
        val rowH = keyH + vGap
        val path = FloatArray(glideCount * 2) { if (it % 2 == 0) glidePts[it] / keyUnit else glidePts[it] / rowH }
        val centers = HashMap<Char, MutableList<FloatArray>>()
        for (i in keys.indices) {
            val code = keys[i].code
            if (code <= 0 || !Character.isLetter(code)) continue
            val c = Normalize.key(String(Character.toChars(code))).singleOrNull() ?: continue
            centers.getOrPut(c) { ArrayList(1) }.add(floatArrayOf(
                (rects[i * 4] + rects[i * 4 + 2]) / 2f / keyUnit,
                (rects[i * 4 + 1] + rects[i * 4 + 3]) / 2f / rowH,
            ))
        }
        cancelGlide()
        listener?.onGlide(path, centers)
    }

    private fun cancelGlide() {
        if (!gliding) return
        gliding = false
        pressedPointer = -1
        invalidate()
    }

    private fun pressKey(i: Int, pointerId: Int, x: Float, y: Float) {
        pressedIndex = i
        pressedPointer = pointerId
        longFired = false
        downX = x
        downY = y
        spaceHeld = false
        val k = keys[i]
        if (k.code == Codes.DELETE) {
            listener?.onKey(k.code)
            touchHandler.postDelayed(deleteRepeat, 400)
        } else if (k.code == Codes.SPACE && swipeEdit) {
            // A quick flick still switches language; only a held space turns into a cursor.
            touchHandler.postDelayed(spaceHoldTask, 250)
            touchHandler.postDelayed(longPressTask, 800)
        } else if (popups[i].isNotEmpty() || k.code == Codes.SPACE || k.code == Codes.SYMBOLS || k.code == Codes.EMOJI) {
            touchHandler.postDelayed(longPressTask, 400)
        }
        if (vibrate) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    private fun releasePressed(fire: Boolean) {
        val i = pressedIndex
        if (i < 0) return
        touchHandler.removeCallbacks(longPressTask)
        touchHandler.removeCallbacks(spaceHoldTask)
        touchHandler.removeCallbacks(deleteRepeat)
        val code = keys[i].code
        val popup = popupKey
        pressedIndex = -1
        pressedPointer = -1
        popupKey = -1
        if (animations && popup < 0) {
            fadeIndex = i
            fadeAnim.start()
        }
        when {
            popup >= 0 -> if (fire) listener?.onLongPress(popups[popup][popupSel])
            // DELETE already fired on down; a long-press already did its job.
            fire && !longFired && code != Codes.DELETE -> listener?.onKey(code)
        }
        invalidate()
    }

    private fun hitTest(x: Float, y: Float): Int {
        val rows = rowStart.size - 1
        if (rows <= 0) return -1
        val r = ((y - topPad) / (keyH + vGap)).toInt().coerceIn(0, rows - 1)
        var best = -1
        var bestDist = Float.MAX_VALUE
        // The key whose box holds x (gaps go to the closer edge). Nearest *centre* would hand the
        // outer part of wide keys (space, shift) to their neighbours.
        for (i in rowStart[r] until rowStart[r + 1]) {
            val l = rects[i * 4]
            val rr = rects[i * 4 + 2]
            val d = if (x < l) l - x else if (x > rr) x - rr else 0f
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    override fun onDetachedFromWindow() {
        touchHandler.removeCallbacksAndMessages(null)
        fadeAnim.cancel()
        popupAnim.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        val KEY_COLOR = Color.parseColor("#474747")
        const val ONE_HAND_WIDTH = 0.82f
        const val SPLIT_GAP = 0.22f
        const val GLIDE_START = 0.6f     // key widths the finger must travel before a press becomes a glide
        const val MAX_GLIDE_POINTS = 1024
    }

    private fun fill(hex: String): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(hex)
        style = Paint.Style.FILL
    }

    private fun text(hex: String, align: Paint.Align): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(hex)
        textAlign = align
    }
}
