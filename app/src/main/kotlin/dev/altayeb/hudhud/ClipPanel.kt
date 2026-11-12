package dev.altayeb.hudhud

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Clipboard panel shown in place of the keys: header (back, title, clear), pinned clips first, then recent ones. */
class ClipPanel(context: Context, private val panelHeight: Int) : LinearLayout(context) {
    interface Listener {
        fun onClipPaste(clip: Clip)
        fun onClipPin(clip: Clip, pinned: Boolean)
        fun onClipDelete(clip: Clip)
        fun onClipClearAll()   // removes unpinned clips
        fun onClipClose()
        fun onEditAction(action: Int)
    }

    var listener: Listener? = null
    var theme = KeyTheme.DEFAULT
    var animations = true
    private val d = resources.displayMetrics.density

    init {
        orientation = VERTICAL
    }

    /** Rebuild the list. [now] = current time ms, for the "2m" age labels. */
    fun show(items: List<Clip>, now: Long) {
        removeAllViews()
        setBackgroundColor(theme.bg)
        addView(header(items), lp(MATCH, dp(52)))
        addView(editRow(), lp(MATCH, dp(44)))
        val list = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, dp(4), 0, dp(12)) }
        if (items.isEmpty()) {
            list.addView(empty(), lp(MATCH, WRAP))
        } else {
            var n = 0
            val pinned = items.filter { it.pinned }
            val recent = items.filterNot { it.pinned }
            if (pinned.isNotEmpty()) {
                list.addView(label("Pinned"))
                pinned.forEach { list.addView(enter(card(it, now), n++)) }
            }
            if (recent.isNotEmpty()) {
                list.addView(label("Recent"))
                recent.forEach { list.addView(enter(card(it, now), n++)) }
            }
        }
        addView(ScrollView(context).apply { addView(list); isVerticalScrollBarEnabled = false }, lp(MATCH, 0, 1f))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(panelHeight, View.MeasureSpec.EXACTLY))
    }

    private fun header(items: List<Clip>): View {
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.panel)
        }
        bar.addView(icon(R.drawable.ic_back, theme.accent, 52) { listener?.onClipClose() })
        bar.addView(TextView(context).apply {
            text = if (items.isEmpty()) "Clipboard" else "Clipboard  ·  ${items.size}"
            textSize = 16f
            setTextColor(theme.label)
        }, lp(0, WRAP, 1f))
        val clear = icon(R.drawable.ic_clear_all, theme.accent, 52) { listener?.onClipClearAll() }
        clear.visibility = if (items.any { !it.pinned }) View.VISIBLE else View.GONE
        bar.addView(clear)
        return bar
    }

    /** Undo, redo and the text menu actions, for fields where they are otherwise a long-press away. */
    private fun editRow(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setBackgroundColor(theme.panel)
        for ((action, label) in listOf(
            UNDO to R.string.edit_undo, REDO to R.string.edit_redo, android.R.id.selectAll to R.string.edit_select_all,
            android.R.id.cut to R.string.edit_cut, android.R.id.copy to R.string.edit_copy, android.R.id.paste to R.string.edit_paste,
            VOICE to R.string.edit_voice,
        )) {
            addView(TextView(context).apply {
                setText(label)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(theme.accent)
                setOnClickListener { listener?.onEditAction(action) }
            }, lp(0, MATCH, 1f))
        }
    }

    private fun empty() = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(40), dp(24), 0)
        addView(ImageView(context).apply {
            setImageDrawable(context.getDrawable(R.drawable.ic_pin))
            setColorFilter(theme.textDim)
            alpha = 0.6f
        }, lp(dp(40), dp(40)))
        addView(TextView(context).apply {
            text = "Copied text shows up here.\nTap the pin to keep it."
            setTextColor(theme.textDim); textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }, lp(MATCH, WRAP))
    }

    private fun card(clip: Clip, now: Long): View {
        val shape = GradientDrawable().apply { setColor(theme.card); cornerRadius = dp(18).toFloat() }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = RippleDrawable(ColorStateList.valueOf(theme.accent and 0x40FFFFFF), shape, shape)
            setOnClickListener { listener?.onClipPaste(clip) }
            setOnLongClickListener { listener?.onClipPin(clip, !clip.pinned); true }
            layoutParams = lp(MATCH, WRAP).apply { setMargins(dp(8), dp(4), dp(8), dp(4)) }
        }
        if (clip.pinned) row.addView(View(context).apply {
            background = GradientDrawable().apply { setColor(theme.accent); cornerRadius = dp(2).toFloat() }
        }, lp(dp(3), dp(28)).apply { leftMargin = dp(10) })
        val col = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(14), dp(12), dp(8), dp(12)) }
        col.addView(TextView(context).apply {
            text = clip.text
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            textSize = 15f
            setTextColor(theme.label)
        }, lp(MATCH, WRAP))
        col.addView(TextView(context).apply {
            text = age(clip.time, now)
            textSize = 11f
            setTextColor(theme.textDim)
        }, lp(WRAP, WRAP).apply { topMargin = dp(4) })
        row.addView(col, lp(0, WRAP, 1f))
        row.addView(icon(R.drawable.ic_pin, if (clip.pinned) theme.accent else theme.textDim, 44) {
            listener?.onClipPin(clip, !clip.pinned)
        }.apply { if (!clip.pinned) alpha = 0.55f })
        row.addView(icon(R.drawable.ic_delete, theme.textDim, 44) { leave(row) { listener?.onClipDelete(clip) } })
        return row
    }

    /** Cards slide up into place one after another. */
    private fun enter(v: View, i: Int): View {
        if (!animations || i > 12) return v
        v.alpha = 0f
        v.translationY = dp(14).toFloat()
        v.animate().alpha(1f).translationY(0f).setStartDelay(i * Motion.STAGGER).setDuration(Motion.NORMAL)
            .setInterpolator(Motion.DECELERATE).start()
        return v
    }

    /** Deleted card slides away before the list is rebuilt. */
    private fun leave(v: View, then: () -> Unit) {
        if (!animations) return then()
        v.animate().alpha(0f).translationX(v.width * 0.4f).setDuration(Motion.EXIT)
            .setInterpolator(Motion.ACCELERATE).withEndAction(then).start()
    }

    private fun label(title: String) = TextView(context).apply {
        text = title
        textSize = 12f
        setTextColor(theme.accent)
        setPadding(dp(18), dp(12), dp(16), dp(4))
    }

    /** Tappable 24dp icon centred in a [touch]dp box, with a round ripple. */
    private fun icon(res: Int, tint: Int, touch: Int, onTap: () -> Unit) = ImageView(context).apply {
        setImageDrawable(context.getDrawable(res))
        setColorFilter(tint)
        scaleType = ImageView.ScaleType.CENTER
        background = RippleDrawable(ColorStateList.valueOf(theme.accent and 0x40FFFFFF), null, null)
        isClickable = true
        layoutParams = lp(dp(touch), dp(touch))
        setOnClickListener { onTap() }
    }

    private fun age(time: Long, now: Long): String {
        val s = (now - time) / 1000
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60}m"
            s < 86400 -> "${s / 3600}h"
            else -> "${s / 86400}d"
        }
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    companion object {
        const val UNDO = -1
        const val REDO = -2
        const val VOICE = -3
    }
}
