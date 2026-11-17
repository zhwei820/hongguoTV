// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hongguotv.core.Detail
import com.hongguotv.core.RelatedWork
import com.hongguotv.core.Series

/** 手机竖屏详情页：封面信息、播放与收藏、分段选集。播放逻辑由宿主提供。 */
internal class PhoneDetail(
    context: Context,
    private val data: Detail,
    private val current: Int,
    private val playLabel: String,
    favorite: Boolean,
    private val cover: (ImageView, String) -> Unit,
    private val back: () -> Unit,
    private val play: () -> Unit,
    private val playEpisode: (Int) -> Unit,
    private val toggleFavorite: () -> Boolean,
    private val openSeason: (RelatedWork) -> Unit,
) : LinearLayout(context) {
    private companion object {
        const val RANGE = 60
        const val COLUMNS = 6
    }

    private val episodes = LinearLayout(context).apply { orientation = VERTICAL }
    private val seasons = LinearLayout(context).apply { orientation = VERTICAL }
    private val ranges = LinearLayout(context)
    private var range = current / RANGE

    private fun dp(value: Number) = (value.toFloat() * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(TvStyle.background)
        val bar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(12), dp(6))
        }
        bar.addView(
            TextView(context).apply {
                text = "‹"
                textSize = 28f
                setTextColor(TvStyle.text)
                setPadding(dp(12), 0, dp(12), dp(4))
                contentDescription = "返回"
                setOnClickListener { back() }
            }
        )
        bar.addView(
            label(data.series.title, 17f, TvStyle.text, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LayoutParams(0, -2, 1f),
        )
        addView(bar)
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        addView(
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(body)
            },
            LayoutParams(-1, 0, 1f),
        )

        val hero = LinearLayout(context)
        val image = PosterImage(context)
        hero.addView(image, LayoutParams(dp(112), -2))
        if (data.series.cover.isNotEmpty()) cover(image, data.series.cover)
        val info = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        info.addView(label(data.series.title, 19f, TvStyle.text, bold = true))
        info.addView(
            label(
                listOf("共 ${data.episodes.size} 集", data.series.badge)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString("  ·  "),
                13f,
                TvStyle.muted,
            ).apply { setPadding(0, dp(6), 0, 0) }
        )
        if (data.series.tags.isNotBlank())
            info.addView(label(data.series.tags, 12f, TvStyle.muted).apply { setPadding(0, dp(6), 0, 0) })
        hero.addView(info, LayoutParams(0, -2, 1f))
        body.addView(hero)

        if (data.series.description.isNotBlank()) {
            val synopsis =
                label(data.series.description, 14f, TvStyle.text).apply {
                    maxLines = 4
                    ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(0f, 1.2f)
                    setPadding(0, dp(14), 0, 0)
                }
            synopsis.setOnClickListener {
                val expanded = synopsis.maxLines != Int.MAX_VALUE
                synopsis.maxLines = if (expanded) Int.MAX_VALUE else 4
            }
            body.addView(synopsis)
        }

        val actions = LinearLayout(context).apply { setPadding(0, dp(16), 0, dp(4)) }
        actions.addView(
            button("▶  $playLabel", TvStyle.accent, Color.WHITE) { play() },
            LayoutParams(0, dp(44), 1f),
        )
        lateinit var favoriteButton: TextView
        favoriteButton =
            button(favoriteLabel(favorite), TvStyle.surface, TvStyle.text) {
                favoriteButton.text = favoriteLabel(toggleFavorite())
            }
        actions.addView(favoriteButton, LayoutParams(dp(104), dp(44)).apply { leftMargin = dp(10) })
        body.addView(actions)
        body.addView(seasons)

        body.addView(
            label("选集 · 共 ${data.episodes.size} 集", 16f, TvStyle.text, bold = true).apply {
                setPadding(0, dp(18), 0, dp(6))
            }
        )
        if (data.episodes.size > RANGE)
            body.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(ranges)
            })
        body.addView(episodes)
        renderEpisodes()
    }

    /** 系列各季（含当前季），rows 已按季排序；没有其他季时不显示。 */
    fun showSeasons(rows: List<RelatedWork>) {
        seasons.removeAllViews()
        if (rows.none { it.series.id != data.series.id }) return
        seasons.addView(
            label("系列作品 · ${rows.size} 部", 16f, TvStyle.text, bold = true).apply {
                setPadding(0, dp(18), 0, dp(8))
            }
        )
        val row = LinearLayout(context)
        var currentCard: View? = null
        rows.forEachIndexed { index, work ->
            val current = work.series.id == data.series.id
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(3), dp(3), dp(3), dp(6))
                background =
                    TvStyle.shape(context, TvStyle.surface, if (current) TvStyle.accent else Color.TRANSPARENT, radius = 10)
                val image = PosterImage(context)
                addView(image, LayoutParams(dp(88), -2))
                if (work.series.cover.isNotEmpty()) cover(image, work.series.cover)
                addView(
                    label(work.season.ifBlank { work.series.title }, 13f, if (current) TvStyle.accent else TvStyle.text, bold = current).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(3), dp(5), dp(3), 0)
                    },
                    LayoutParams(dp(88), -2),
                )
                addView(
                    label(if (current) "当前" else work.series.badge, 11f, TvStyle.muted).apply {
                        maxLines = 1
                        setPadding(dp(3), dp(1), dp(3), 0)
                    },
                    LayoutParams(dp(88), -2),
                )
                if (!current) setOnClickListener { openSeason(work) }
            }
            row.addView(card, LayoutParams(-2, -2).apply { if (index > 0) leftMargin = dp(10) })
            if (current) currentCard = card
        }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        seasons.addView(scroll)
        currentCard?.let { card -> scroll.post { scroll.scrollTo((card.left - dp(24)).coerceAtLeast(0), 0) } }
    }

    private fun favoriteLabel(saved: Boolean) = if (saved) "★ 已收藏" else "☆ 收藏"

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun button(value: String, fill: Int, color: Int, onClick: () -> Unit) =
        TextView(context).apply {
            text = value
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(color)
            setTypeface(null, Typeface.BOLD)
            background = TvStyle.shape(context, fill, radius = 22)
            setOnClickListener { onClick() }
        }

    private fun renderEpisodes() {
        ranges.removeAllViews()
        val count = data.episodes.size
        if (count > RANGE)
            for (index in 0..(count - 1) / RANGE) {
                val selected = index == range
                ranges.addView(
                    label("${index * RANGE + 1}-${minOf(count, (index + 1) * RANGE)}", 13f,
                        if (selected) TvStyle.accent else TvStyle.text, bold = selected).apply {
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                        background = TvStyle.shape(context, if (selected) TvStyle.raised else TvStyle.surface, radius = 14)
                        setOnClickListener {
                            range = index
                            renderEpisodes()
                        }
                    },
                    LayoutParams(-2, -2).apply { if (index > 0) leftMargin = dp(8); bottomMargin = dp(8) },
                )
            }
        episodes.removeAllViews()
        val start = range * RANGE
        val end = minOf(count, start + RANGE)
        var row: LinearLayout? = null
        for (index in start until end) {
            if ((index - start) % COLUMNS == 0) {
                row = LinearLayout(context)
                episodes.addView(row, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            val selected = index == current
            row?.addView(
                label("${index + 1}", 14f, if (selected) Color.WHITE else TvStyle.text, bold = selected).apply {
                    gravity = Gravity.CENTER
                    background = TvStyle.shape(context, if (selected) TvStyle.accent else TvStyle.surface, radius = 8)
                    setOnClickListener { playEpisode(index) }
                },
                LayoutParams(0, dp(40), 1f).apply { if ((index - start) % COLUMNS > 0) leftMargin = dp(8) },
            )
        }
        val tail = (end - start) % COLUMNS
        if (tail > 0)
            repeat(COLUMNS - tail) {
                row?.addView(View(context), LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(8) })
            }
    }
}
