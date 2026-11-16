// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.widget.EditText
import kotlin.math.abs

internal object TouchUi {
    /** 有触屏且不是电视：提示文案与默认字号按手机处理。手势和单击修复不依赖此判断，电视上没有触摸事件。 */
    fun enabled(context: Context): Boolean {
        val television =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
                Configuration.UI_MODE_TYPE_TELEVISION
        return !television && context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /**
     * 遥控焦点控件都设了 isFocusableInTouchMode，系统对这类控件的第一次轻点只取得焦点、不触发点击。
     * 在窗口层补一次 performClick，焦点副作用（预览、封面加载、记住位置）照常发生。
     * 每个含此类控件的窗口都要装，Dialog 有自己的窗口。
     */
    fun install(window: Window) {
        val base = window.callback
        if (base !is TapThrough) window.callback = TapThrough(window, base)
    }

    fun install(dialog: Dialog) {
        dialog.window?.let(::install)
    }

    private class TapThrough(private val window: Window, private val base: Window.Callback) :
        Window.Callback by base {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_UP) return base.dispatchTouchEvent(event)
            val before = window.currentFocus
            val handled = base.dispatchTouchEvent(event)
            val target = window.currentFocus ?: return handled
            if (
                target !== before &&
                    target.isClickable &&
                    target.isFocusableInTouchMode &&
                    target !is EditText &&
                    !(target.isLongClickable &&
                        event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()) &&
                    hit(target, event)
            )
                target.performClick()
            return handled
        }

        private fun hit(view: View, event: MotionEvent): Boolean {
            val bounds = Rect()
            return view.getGlobalVisibleRect(bounds) && bounds.contains(event.rawX.toInt(), event.rawY.toInt())
        }
    }

    /** 视频区域手势。onDoubleTap 收到点击位置占宽度的比例；onSwipe 收到 -1（向右滑，上一个）或 1（向左滑，下一个）。 */
    fun gestures(
        context: Context,
        onTap: () -> Unit,
        onDoubleTap: ((Float) -> Unit)? = null,
        onSwipe: ((Int) -> Unit)? = null,
        onLongPress: (() -> Unit)? = null,
    ): View.OnTouchListener {
        var width = 1
        val minFling = ViewConfiguration.get(context).scaledTouchSlop * 4
        val detector =
            GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        onTap()
                        return true
                    }

                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        if (onDoubleTap == null) onTap()
                        return onDoubleTap == null
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        onDoubleTap?.invoke(e.x / width)
                        return onDoubleTap != null
                    }

                    override fun onLongPress(e: MotionEvent) {
                        onLongPress?.invoke()
                    }

                    override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                        val start = e1 ?: return false
                        val dx = e2.x - start.x
                        if (onSwipe == null || abs(dx) < minFling || abs(dx) < abs(e2.y - start.y) * 1.5f) return false
                        onSwipe(if (dx < 0) 1 else -1)
                        return true
                    }
                },
            )
        // 无双击回调时 onSingleTapUp 已处理轻点，onSingleTapConfirmed 不应再触发一次。
        if (onDoubleTap == null) detector.setOnDoubleTapListener(null)
        return View.OnTouchListener { view, event ->
            width = view.width.coerceAtLeast(1)
            detector.onTouchEvent(event)
            true
        }
    }
}
