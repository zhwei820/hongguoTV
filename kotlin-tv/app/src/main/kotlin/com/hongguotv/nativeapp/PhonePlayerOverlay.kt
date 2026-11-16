// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 手机横屏播放控制层：顶部返回与标题，中间播放/暂停，底部可拖动进度条与功能按钮。
 * 只负责展示和转发操作；显示/隐藏时机由宿主的 showHud / hidePlaybackOverlay 决定。
 */
internal class PhonePlayerOverlay(
    context: Context,
    private val back: () -> Unit,
    private val toggle: () -> Unit,
    private val seek: (Long) -> Unit,
    private val interacted: () -> Unit,
    private val previous: () -> Unit,
    private val next: () -> Unit,
    private val episodes: (View) -> Unit,
    private val speed: (TextView) -> Unit,
    private val quality: (TextView) -> Unit,
    private val danmaku: (TextView) -> Unit,
    private val comments: (TextView) -> Unit,
    private val favorite: () -> Boolean,
    private val settings: (TextView) -> Unit,
) : FrameLayout(context) {
    private val title = text("", 16f, bold = true).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val playButton = text("", 30f).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(110, 0, 0, 0))
        }
        setOnClickListener {
            toggle()
            interacted()
        }
    }
    private val elapsed = text("00:00", 12f)
    private val total = text("00:00", 12f)
    private val bar = SeekBar(context).apply {
        max = 1000
        progressTintList = android.content.res.ColorStateList.valueOf(TvStyle.accent)
        thumbTintList = android.content.res.ColorStateList.valueOf(TvStyle.accent)
    }
    private val previousButton = action("上一集") { previous() }
    private val nextButton = action("下一集") { next() }
    private val speedButton = action("倍速") { speed(it) }
    private val qualityButton = action("清晰度") { quality(it) }
    private val favoriteButton = action("收藏") { it.text = favoriteLabel(favorite()) }
    private var duration = 0L
    private var dragging = false
    val showing get() = visibility == VISIBLE

    private fun dp(value: Number) = (value.toFloat() * resources.displayMetrics.density).toInt()

    init {
        visibility = GONE
        isClickable = false
        val top = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(24), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(190, 0, 0, 0), Color.TRANSPARENT),
            )
        }
        top.addView(text("‹", 30f).apply {
            setPadding(dp(14), 0, dp(14), dp(4))
            contentDescription = "返回"
            setOnClickListener { back() }
        })
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        addView(top, LayoutParams(-1, -2, Gravity.TOP))

        addView(playButton, LayoutParams(dp(64), dp(64), Gravity.CENTER))

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(10))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(200, 0, 0, 0), Color.TRANSPARENT),
            )
        }
        val progress = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        progress.addView(elapsed)
        progress.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
        progress.addView(total)
        bottom.addView(progress)
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf(
            previousButton,
            nextButton,
            action("选集") { episodes(it) },
            speedButton,
            qualityButton,
            action("弹幕") { danmaku(it) },
            action("评论") { comments(it) },
            favoriteButton,
            action("更多") { settings(it) },
        ).forEach { actions.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        bottom.addView(actions)
        addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM))

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    elapsed.text = format(duration * value / 1000)
                    interacted()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                dragging = false
                if (duration > 0) seek(duration * bar.progress / 1000)
                interacted()
            }
        })
    }

    fun update(
        heading: String,
        position: Long,
        length: Long,
        playing: Boolean,
        speedLabel: String,
        qualityLabel: String,
        saved: Boolean,
        hasPrevious: Boolean,
        hasNext: Boolean,
    ) {
        title.text = heading
        playButton.text = if (playing) "❚❚" else "▶"
        playButton.textSize = if (playing) 20f else 28f
        duration = length.coerceAtLeast(0)
        total.text = format(duration)
        if (!dragging) {
            elapsed.text = format(position)
            bar.progress = if (duration > 0) (position * 1000 / duration).toInt().coerceIn(0, 1000) else 0
        }
        speedButton.text = speedLabel
        qualityButton.text = qualityLabel
        favoriteButton.text = favoriteLabel(saved)
        previousButton.alpha = if (hasPrevious) 1f else .35f
        previousButton.isEnabled = hasPrevious
        nextButton.alpha = if (hasNext) 1f else .35f
        nextButton.isEnabled = hasNext
    }

    private fun favoriteLabel(saved: Boolean) = if (saved) "已收藏" else "收藏"

    private fun format(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun text(value: String, size: Float, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            if (bold) setTypeface(null, Typeface.BOLD)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }

    private fun action(label: String, onClick: (TextView) -> Unit) =
        text(label, 13f).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            tag = "playback-settings"
            setPadding(0, dp(10), 0, dp(6))
            setOnClickListener {
                onClick(this)
                interacted()
            }
        }
}
