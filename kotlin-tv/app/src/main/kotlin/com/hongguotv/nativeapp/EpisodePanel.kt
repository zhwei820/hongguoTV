// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 播放页选集面板：横屏为右侧抽屉，竖屏为底部面板；按段切换、点选即播；点面板外或关闭按钮收起。 */
class EpisodePanel(
    private val activity: Activity,
    private val count: Int,
    private val current: Int,
    private val select: (Int) -> Unit,
    private val closed: () -> Unit,
    private val lastWatched: Int? = null,
    private val lastCompleted: Boolean = false,
) {
    private companion object {
        const val RANGE = 50
        const val COLUMNS = 6
    }

    private val dialog = Dialog(activity).also { TouchUi.install(it) }
    private val ranges = LinearLayout(activity)
    private val rangeScroll =
        HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(ranges)
        }
    private val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val gridScroll =
        ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(grid)
        }
    private var range = current / RANGE
    private val portrait =
        activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    private fun dp(n: Number) = (n.toFloat() * activity.resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float, color: Int = TvStyle.text, bold: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { closed() }
        dialog.setContentView(build())
        render()
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(.35f)
            val metrics = activity.resources.displayMetrics
            if (portrait) {
                setGravity(Gravity.BOTTOM)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (metrics.heightPixels * .6f).toInt())
            } else {
                setGravity(Gravity.END or Gravity.TOP)
                setLayout(minOf(dp(400), (metrics.widthPixels * .48f).toInt()), ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
    }

    fun dismiss() = dialog.dismiss()

    private fun build(): View {
        val panel =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(if (portrait) 16 else 12), dp(8))
                background =
                    GradientDrawable().apply {
                        setColor(Color.argb(242, Color.red(TvStyle.background), Color.green(TvStyle.background), Color.blue(TvStyle.background)))
                        val r = dp(14).toFloat()
                        cornerRadii =
                            if (portrait) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                            else floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                    }
            }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("选集", 18f, bold = true))
        header.addView(
            text("  共 $count 集 · 正在看第 ${current + 1} 集", 13f, TvStyle.muted).apply {
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        header.addView(
            text("✕", 18f, TvStyle.muted).apply {
                gravity = Gravity.CENTER
                contentDescription = "关闭"
                setOnClickListener { dismiss() }
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        panel.addView(header)
        if (count > RANGE)
            panel.addView(rangeScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        panel.addView(gridScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        return FrameLayout(activity).apply { addView(panel, FrameLayout.LayoutParams(-1, -1)) }
    }

    private fun render() {
        ranges.removeAllViews()
        if (count > RANGE)
            for (index in 0..(count - 1) / RANGE) {
                val selected = index == range
                val chip =
                    text(
                        "${index * RANGE + 1}-${minOf(count, (index + 1) * RANGE)}",
                        13f,
                        if (selected) TvStyle.accent else TvStyle.text,
                        bold = selected,
                    ).apply {
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                        background =
                            TvStyle.shape(
                                activity,
                                if (selected) TvStyle.raised else TvStyle.surface,
                                if (selected) TvStyle.accent else Color.TRANSPARENT,
                                radius = 14,
                            )
                        setOnClickListener {
                            range = index
                            render()
                            gridScroll.scrollTo(0, 0)
                        }
                    }
                ranges.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { if (index > 0) leftMargin = dp(8) })
                if (selected) rangeScroll.post { rangeScroll.smoothScrollTo((chip.left - dp(40)).coerceAtLeast(0), 0) }
            }

        grid.removeAllViews()
        val start = range * RANGE
        val end = minOf(count, start + RANGE)
        var row: LinearLayout? = null
        var currentCell: View? = null
        for (index in start until end) {
            val column = (index - start) % COLUMNS
            if (column == 0) {
                row = LinearLayout(activity)
                grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            val playing = index == current
            val cell = FrameLayout(activity).apply {
                background = TvStyle.shape(activity, if (playing) TvStyle.accent else TvStyle.surface, radius = 8)
                contentDescription = "第 ${index + 1} 集" + if (playing) "，正在播放" else ""
                setOnClickListener { select(index) }
            }
            cell.addView(
                text(if (playing) "▶ ${index + 1}" else "${index + 1}", 14f, if (playing) Color.WHITE else TvStyle.text, playing)
                    .apply {
                        gravity = Gravity.CENTER
                        setSingleLine(true)
                    },
                FrameLayout.LayoutParams(-1, -1),
            )
            if (index == lastWatched && !playing)
                cell.addView(
                    View(activity).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(if (lastCompleted) TvStyle.muted else TvStyle.accent)
                        }
                    },
                    FrameLayout.LayoutParams(dp(6), dp(6), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) },
                )
            row?.addView(cell, LinearLayout.LayoutParams(0, dp(42), 1f).apply { if (column > 0) leftMargin = dp(8) })
            if (playing) currentCell = row
        }
        val tail = (end - start) % COLUMNS
        if (tail > 0)
            repeat(COLUMNS - tail) {
                row?.addView(View(activity), LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
            }
        currentCell?.let { target -> gridScroll.post { gridScroll.scrollTo(0, (target.top - dp(50)).coerceAtLeast(0)) } }
    }
}
