package dev.altayeb.hudhud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * Bar above the keys: clipboard button, 3 word slots (middle = what space inserts), quick-toggle button.
 * Draws on one Canvas; nothing allocated in onDraw.
 */
class SuggestionStrip(context: Context) : View(context) {

    interface Listener {
        fun onPick(word: String)
        fun onClipboard()
        fun onToggles()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val stripHeight = (48 * density).toInt()
    private val iconWidth = 44 * density

    /** Slots left, middle, right. "" = empty slot. */
    private val slots = arrayOf("", "", "")
    private var rtl = false

    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE6E6E6.toInt(); textSize = 16 * density; textAlign = Paint.Align.CENTER
    }
    private val bestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CYAN; textSize = 16 * density; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val gear = android.graphics.Path()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CYAN; style = Paint.Style.STROKE; strokeWidth = 2 * density; strokeJoin = Paint.Join.ROUND
    }
    private val rect = android.graphics.RectF()
    private val linePaint = Paint().apply { color = 0xFF3A3C40.toInt(); strokeWidth = density }

    init { setBackgroundColor(0xFF000000.toInt()) }

    var animations = true

    /** Colours from the keyboard theme. */
    fun setTheme(t: KeyTheme) {
        setBackgroundColor(t.bg)
        wordPaint.color = t.label
        bestPaint.color = t.accent
        iconPaint.color = t.accent
        linePaint.color = t.card
        invalidate()
    }

    /** [best] goes in the middle; [others] fill left then right. Mirrored for RTL. */
    fun show(best: String, others: List<String>, rtl: Boolean) {
        // Words fade in when they appear on an empty strip (not on every letter, that would flicker).
        if (animations && best.isNotEmpty() && slots[1].isEmpty()) { alpha = 0.35f; animate().alpha(1f).setDuration(Motion.FAST).setInterpolator(Motion.DECELERATE).start() }
        slots[1] = best
        slots[0] = others.getOrElse(0) { "" }
        slots[2] = others.getOrElse(1) { "" }
        if (rtl) { val t = slots[0]; slots[0] = slots[2]; slots[2] = t }
        this.rtl = rtl
        invalidate()
    }

    fun clear() = show("", emptyList(), rtl)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), stripHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val slotW = (w - 2 * iconWidth) / 3
        val baseline = h / 2 - (wordPaint.descent() + wordPaint.ascent()) / 2
        drawClipboardIcon(canvas, iconWidth / 2, h / 2)
        drawGearIcon(canvas, w - iconWidth / 2, h / 2)
        for (i in 0..2) {
            val text = slots[i]
            if (text.isEmpty()) continue
            val cx = iconWidth + slotW * i + slotW / 2
            val paint = if (i == 1) bestPaint else wordPaint
            canvas.drawText(text, 0, text.length, cx, baseline, paint)
        }
        canvas.drawLine(iconWidth + slotW, h * 0.25f, iconWidth + slotW, h * 0.75f, linePaint)
        canvas.drawLine(iconWidth + 2 * slotW, h * 0.25f, iconWidth + 2 * slotW, h * 0.75f, linePaint)
        canvas.drawLine(0f, h - density, w, h - density, linePaint)
    }

    /** Settings gear: one closed outline with eight trapezoid teeth and a hole, ~18dp. */
    private fun drawGearIcon(canvas: Canvas, cx: Float, cy: Float) {
        val u = density
        val outer = 9 * u; val root = 6.5f * u
        gear.rewind()
        for (i in 0 until 8) {
            val mid = i * 45.0
            // root start, tooth base, tooth top, tooth top, tooth base, root end (degrees around the tooth centre)
            for ((da, r) in listOf(-22.5 to root, -15.0 to root, -10.0 to outer, 10.0 to outer, 15.0 to root)) {
                val a = Math.toRadians(mid + da)
                val x = cx + Math.cos(a).toFloat() * r; val y = cy + Math.sin(a).toFloat() * r
                if (gear.isEmpty) gear.moveTo(x, y) else gear.lineTo(x, y)
            }
        }
        gear.close()
        canvas.drawPath(gear, iconPaint)
        canvas.drawCircle(cx, cy, 2.8f * u, iconPaint)
    }

    /** Clipboard outline with the clip on top, ~18dp tall. */
    private fun drawClipboardIcon(canvas: Canvas, cx: Float, cy: Float) {
        val u = density
        rect.set(cx - 7 * u, cy - 8 * u, cx + 7 * u, cy + 9 * u)
        canvas.drawRoundRect(rect, 2 * u, 2 * u, iconPaint)
        rect.set(cx - 3.5f * u, cy - 10 * u, cx + 3.5f * u, cy - 6 * u)
        canvas.drawRoundRect(rect, 1.5f * u, 1.5f * u, iconPaint)
        canvas.drawLine(cx - 3.5f * u, cy - 1 * u, cx + 3.5f * u, cy - 1 * u, iconPaint)
        canvas.drawLine(cx - 3.5f * u, cy + 3.5f * u, cx + 1.5f * u, cy + 3.5f * u, iconPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = event.x
        val w = width.toFloat()
        when {
            x < iconWidth -> listener?.onClipboard()
            x > w - iconWidth -> listener?.onToggles()
            else -> {
                val i = ((x - iconWidth) / ((w - 2 * iconWidth) / 3)).toInt().coerceIn(0, 2)
                if (slots[i].isNotEmpty()) listener?.onPick(slots[i])
            }
        }
        return true
    }

    private companion object { const val CYAN = 0xFF33B5E5.toInt() }
}
