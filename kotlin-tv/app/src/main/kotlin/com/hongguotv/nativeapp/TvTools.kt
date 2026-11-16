// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hongguotv.core.EpisodeTarget
import java.net.Inet4Address
import java.util.concurrent.Executors

/** Native modal controls kept separate from catalog/player navigation. */
class TvTools(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var dialog: AlertDialog? = null
    val showing: Boolean
        get() = dialog?.isShowing == true


    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float = 17f) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(TvStyle.text)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

    private fun column() =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }

    private fun open(next: AlertDialog, anchor: View?) {
        TouchUi.install(next)
        val previous = dialog
        dialog = null
        previous?.dismiss()
        dialog = next
        next.setOnDismissListener {
            if (dialog === next) {
                dialog = null
                if (anchor?.isAttachedToWindow == true) anchor.requestFocus()
            }
        }
        next.show()
        // Framework dialog labels otherwise retain the fixed dark XML theme.
        next.findViewById<TextView>(android.R.id.message)?.setTextColor(TvStyle.text)
        val titleId = activity.resources.getIdentifier("alertTitle", "id", "android")
        if (titleId != 0) next.findViewById<TextView>(titleId)?.setTextColor(TvStyle.text)
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .forEach { which ->
                next.getButton(which)?.apply {
                    setTextColor(TvStyle.text)
                    backgroundTintList = android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                        intArrayOf(TvStyle.raised, TvStyle.surface),
                    )
                }
            }
        next.window?.setBackgroundDrawable(TvVisual.panel(activity, modal = true))
        next.window?.setDimAmount(.6f)
    }

    fun close() {
        dialog?.dismiss()
    }

    fun destroy() {
        close()
        worker.shutdownNow()
    }

    fun info(title: String, message: String, anchor: View?) =
        open(
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .create(),
            anchor,
        )

    fun confirm(title: String, message: String, anchor: View?, action: () -> Unit) {
        val next =
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认") { _, _ -> action() }
                .create()
        open(next, anchor)
        next.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    fun choose(
        title: String,
        labels: List<String>,
        anchor: View?,
        onChoice: (Int) -> Unit,
        extra: String? = null,
        compact: Boolean = false,
        selected: Int = -1,
        covers: List<String> = emptyList(),
        loadCover: (ImageView, String) -> Unit = { _, _ -> },
        onExtra: () -> Unit = {},
    ) {
        require(labels.isNotEmpty())
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = TvVisual.panel(activity, modal = true)
        }
        content.addView(label(title, 21f).apply {
            setTextColor(TvStyle.text)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(8), 0, dp(8), dp(14))
        })
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
        }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        content.addView(scroll)
        val choices = mutableListOf<View>()
        lateinit var next: AlertDialog
        fun choice(value: String, checked: Boolean, action: () -> Unit) {
            val row = LinearLayout(activity).apply {
                id = View.generateViewId()
                tag = "choice-${choices.size}"
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                minimumHeight = dp(48)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                contentDescription = value + if (checked) "，当前已选" else ""
            }
            val name = label(value, 16f).apply { setPadding(0, 0, dp(8), 0) }
            val marker = label(if (checked) "✓" else "", 18f).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(0, 0, 0, 0)
            }
            covers.getOrNull(choices.size)?.takeIf { it.isNotBlank() }?.let { url ->
                val poster = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                row.addView(poster, LinearLayout.LayoutParams(dp(54), dp(72)).apply { rightMargin = dp(12) })
                loadCover(poster, url)
            }
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(marker, LinearLayout.LayoutParams(dp(26), -2))
            fun paint(focused: Boolean) {
                row.background = TvVisual.row(activity, focused, checked)
                name.setTextColor(TvVisual.primary)
                marker.setTextColor(TvVisual.accent)
            }
            paint(false)
            row.setOnFocusChangeListener { _, focused -> paint(focused) }
            row.setOnClickListener { next.dismiss(); action() }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            choices += row
        }
        labels.forEachIndexed { index, value -> choice(value, index == selected) { onChoice(index) } }
        if (extra != null) choice(extra, false, onExtra)
        choices.forEachIndexed { index, view ->
            view.nextFocusUpId = choices[maxOf(0, index - 1)].id
            view.nextFocusDownId = choices[minOf(choices.lastIndex, index + 1)].id
            view.nextFocusLeftId = view.id
            view.nextFocusRightId = view.id
        }
        content.addView(label("确定选择 · 返回取消", 12f).apply {
            setTextColor(TvStyle.muted)
            setPadding(dp(8), dp(8), dp(8), 0)
        })
        next = AlertDialog.Builder(activity).create()
        next.setView(content, 0, 0, 0, 0)
        open(next, anchor)
        next.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        next.window?.setDimAmount(.6f)
        val width = minOf(dp(if (compact) 440 else 480), (activity.resources.displayMetrics.widthPixels * .82f).toInt())
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = minOf(content.measuredHeight, (activity.resources.displayMetrics.heightPixels * .84f).toInt())
        scroll.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        next.window?.setLayout(width, height)
        val target = choices[selected.takeIf { it in labels.indices } ?: 0]
        target.requestFocus()
        target.post { if (next.isShowing) scroll.requestChildFocus(list, target) }
    }

    fun chooseGrid(
        title: String,
        labels: List<String>,
        selected: Int,
        anchor: View?,
        onChoice: (Int) -> Unit,
    ) {
        val content = column()
        val scroll = android.widget.ScrollView(activity)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        content.addView(scroll)
        val choices = mutableListOf<android.widget.TextView>()
        lateinit var next: AlertDialog
        labels.chunked(4).forEachIndexed { rowIndex, values ->
            val row = LinearLayout(activity)
            values.forEachIndexed { col, value ->
                val index = rowIndex * 4 + col
                val choice =
                    android.widget.TextView(activity).apply {
                        id = View.generateViewId()
                        text = value
                        textSize = 15f
                        gravity = android.view.Gravity.CENTER
                        isFocusable = true
                        isClickable = true
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        contentDescription = value
                        fun paint(focused: Boolean) {
                            setTextColor(if (index == selected) TvStyle.accent else TvStyle.text)
                            background = TvStyle.card(activity, focused, index == selected)
                        }
                        paint(false)
                        setOnFocusChangeListener { _, focused -> paint(focused) }
                        setOnClickListener {
                            next.dismiss()
                            onChoice(index)
                        }
                    }
                row.addView(
                    choice,
                    LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    },
                )
                choices += choice
            }
            repeat(4 - values.size) {
                row.addView(View(activity), LinearLayout.LayoutParams(0, dp(46), 1f))
            }
            list.addView(row)
        }
        choices.forEachIndexed { i, v ->
            v.nextFocusLeftId = if (i % 4 == 0) v.id else choices[i - 1].id
            v.nextFocusRightId =
                if (i % 4 == 3 || i == choices.lastIndex) v.id else choices[i + 1].id
            v.nextFocusUpId = choices.getOrNull(i - 4)?.id ?: v.id
            v.nextFocusDownId =
                choices.getOrNull(i + 4)?.id
                    ?: if (i / 4 < choices.lastIndex / 4) choices.last().id else v.id
        }
        next =
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(content)
                .setNegativeButton("返回", null)
                .create()
        open(next, anchor)
        next.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * .85f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        choices.getOrNull(selected.coerceAtLeast(0))?.requestFocus()
    }

    fun episodePicker(count: Int, current: Int, anchor: View?, select: (Int) -> Unit) {
        val content = column()
        var digits = (current + 1).toString()
        var replace = true
        val value = label(digits, 28f).apply { gravity = Gravity.CENTER }
        content.addView(value)
        val error = label("输入集数后，选择「播放此集」", 14f)
        content.addView(error)
        fun digit(key: String) {
            digits = if (replace) key else (digits + key).take(6)
            replace = false
            value.text = digits
            error.text = "输入集数后，选择「播放此集」"
        }
        fun erase() {
            digits = if (replace) "" else digits.dropLast(1)
            replace = false
            value.text = digits.ifEmpty { "—" }
        }
        val buttons = mutableListOf<Button>()
        listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("清空", "0", "退格"),
            )
            .forEach { keys ->
                val row = LinearLayout(activity)
                keys.forEach { key ->
                    val button =
                        Button(activity).apply {
                            id = View.generateViewId()
                            text = key
                            setTextColor(TvStyle.text)
                            minHeight = dp(42)
                            backgroundTintList =
                                android.content.res.ColorStateList(
                                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                                    intArrayOf(TvStyle.raised, TvStyle.surface),
                                )
                            setOnClickListener {
                                when (key) {
                                    "清空" -> {
                                        digits = ""
                                        replace = false
                                        value.text = "—"
                                    }
                                    "退格" -> erase()
                                    else -> digit(key)
                                }
                            }
                        }
                    buttons += button
                    row.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
                }
                content.addView(row)
            }
        val next =
            AlertDialog.Builder(activity)
                .setTitle("跳转集数 · 1—$count")
                .setView(ScrollView(activity).apply { addView(content) })
                .setNegativeButton("取消", null)
                .setPositiveButton("播放此集", null)
                .create()
        next.setOnKeyListener { _, key, event ->
            when {
                key in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    if (event.action == KeyEvent.ACTION_DOWN)
                        digit((key - KeyEvent.KEYCODE_0).toString())
                    true
                }
                key == KeyEvent.KEYCODE_DEL -> {
                    if (event.action == KeyEvent.ACTION_DOWN) erase()
                    true
                }
                else -> false
            }
        }
        open(next, anchor)
        val locate = next.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancel = next.getButton(AlertDialog.BUTTON_NEGATIVE)
        buttons.forEachIndexed { i, button ->
            button.nextFocusLeftId = buttons[if (i % 3 == 0) i else i - 1].id
            button.nextFocusRightId = buttons[if (i % 3 == 2) i else i + 1].id
            button.nextFocusUpId = buttons[if (i < 3) i else i - 3].id
            button.nextFocusDownId = if (i < 9) buttons[i + 3].id else locate.id
        }
        locate.nextFocusUpId = buttons[10].id
        cancel.nextFocusUpId = buttons[9].id
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val target = EpisodeTarget.parse(digits, count)
            if (target == null) error.text = "请输入 1—$count 之间的集数"
            else {
                next.dismiss()
                select(target)
            }
        }
        buttons.first().requestFocus()
    }

}
