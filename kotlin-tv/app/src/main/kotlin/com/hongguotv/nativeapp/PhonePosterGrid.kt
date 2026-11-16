// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.hongguotv.core.Series

/** 宽度决定高度，保持竖版海报比例。 */
internal class PosterImage(context: Context) : ImageView(context) {
    init {
        scaleType = ScaleType.CENTER_CROP
        clipToOutline = true
        background = TvStyle.shape(context, TvStyle.surface, radius = 8)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width / TvStyle.POSTER_ASPECT).toInt())
    }
}

/**
 * 竖屏海报网格：ListView 每行放 columns 张卡片，复用行视图；表头随列表一起滚动，
 * 这样筛选、页签等内容会自然滚出屏幕，不会挤占网格高度。
 */
internal class PhonePosterGrid(
    context: Context,
    private val cover: (ImageView, String) -> Unit,
    private val open: (Series) -> Unit,
    private val longPress: ((Series) -> Unit)? = null,
    private val columns: Int = 3,
) : ListView(context) {
    var subtitle: (Series) -> String = { it.badge }
    var corner: (Series) -> String? = { null }
    var onNearEnd: (() -> Unit)? = null
    var items: List<Series> = emptyList()
        set(value) {
            field = value
            rows.notifyDataSetChanged()
        }

    private val footer =
        TextView(context).apply {
            textSize = 13f
            setTextColor(TvStyle.muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(28))
        }
    private var retry: (() -> Unit)? = null
    private val rows = Rows()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        divider = null
        dividerHeight = 0
        selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(dp(12), 0, dp(12), 0)
        footer.setOnClickListener { retry?.invoke() }
        addFooterView(footer, null, false)
        setAdapter(rows)
        setOnScrollListener(
            object : OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, state: Int) = Unit

                override fun onScroll(view: AbsListView?, first: Int, count: Int, total: Int) {
                    if (items.isNotEmpty() && count > 0 && first + count >= total - 2) onNearEnd?.invoke()
                }
            }
        )
    }

    fun header(view: View) = addHeaderView(view, null, false)

    /** 列表底部状态行；给出 retry 时整行可点击重试。 */
    fun status(text: String?, retry: (() -> Unit)? = null) {
        this.retry = retry
        footer.text = text.orEmpty()
        footer.isClickable = retry != null
        footer.setTextColor(if (retry != null) TvStyle.accent else TvStyle.muted)
    }

    fun refreshCards() = rows.notifyDataSetChanged()

    private inner class Card : LinearLayout(context) {
        val image = PosterImage(context)
        val badge =
            TextView(context).apply {
                textSize = 11f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = TvStyle.shape(context, TvStyle.accent, radius = 6)
            }
        val title =
            TextView(context).apply {
                textSize = 13f
                setTextColor(TvStyle.text)
                maxLines = 2
                minLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            }
        val detail =
            TextView(context).apply {
                textSize = 11f
                setTextColor(TvStyle.muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        var series: Series? = null

        init {
            orientation = VERTICAL
            val frame = android.widget.FrameLayout(context)
            frame.addView(image)
            frame.addView(
                badge,
                android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                    setMargins(dp(4), dp(4), 0, 0)
                },
            )
            addView(frame)
            addView(title)
            addView(detail)
            setOnClickListener { series?.let(open) }
            if (longPress != null)
                setOnLongClickListener {
                    series?.let { longPress.invoke(it) }
                    series != null
                }
        }

        fun bind(item: Series?) {
            series = item
            visibility = if (item == null) INVISIBLE else VISIBLE
            if (item == null) return
            title.text = item.title
            detail.text = subtitle(item)
            val mark = corner(item)
            badge.text = mark.orEmpty()
            badge.visibility = if (mark.isNullOrEmpty()) GONE else VISIBLE
            image.setImageDrawable(null)
            if (item.cover.isNotEmpty()) cover(image, item.cover)
        }
    }

    private inner class Rows : BaseAdapter() {
        override fun getCount() = (items.size + columns - 1) / columns

        override fun getItem(position: Int) = position

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row =
                (convertView as? LinearLayout)
                    ?: LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(6), 0, dp(6))
                        repeat(columns) { index ->
                            addView(
                                Card(),
                                LinearLayout.LayoutParams(0, -2, 1f).apply {
                                    if (index > 0) leftMargin = dp(10)
                                },
                            )
                        }
                    }
            for (index in 0 until columns)
                (row.getChildAt(index) as Card).bind(items.getOrNull(position * columns + index))
            return row
        }
    }
}
