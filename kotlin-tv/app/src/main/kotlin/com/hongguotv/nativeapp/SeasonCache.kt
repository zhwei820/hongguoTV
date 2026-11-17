// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.RelatedWork
import com.hongguotv.core.Series
import org.json.JSONArray
import org.json.JSONObject

/**
 * 详情页「系列作品」缓存：先显示上次结果，后台刷新后替换（待上线可能已上线）。
 * 按剧目 id 存，7 天过期，按最近使用保留 MAX_KEYS 部。
 */
internal class SeasonCache(context: Context) {
    private companion object {
        const val MAX_KEYS = 40
        const val MAX_AGE = 7 * 24 * 60 * 60 * 1000L
        const val ORDER = "__order"
    }

    private val prefs = context.getSharedPreferences("season-cache", Context.MODE_PRIVATE)

    fun get(id: String): List<RelatedWork>? =
        runCatching {
                val o = JSONObject(prefs.getString(id, null) ?: return null)
                if (System.currentTimeMillis() - o.getLong("savedAt") !in 0..MAX_AGE) return null
                val rows = o.getJSONArray("rows")
                (0 until rows.length()).map {
                    val row = rows.getJSONObject(it)
                    RelatedWork(
                        Series.fromJson(row.getJSONObject("series")),
                        row.optString("season"),
                        row.optBoolean("upcoming"),
                        row.optInt("seasonIndex"),
                    )
                }
            }
            .getOrNull()

    fun put(id: String, rows: List<RelatedWork>) {
        if (id == ORDER) return
        val encoded =
            JSONObject()
                .put("savedAt", System.currentTimeMillis())
                .put(
                    "rows",
                    JSONArray(
                        rows.take(60).map {
                            JSONObject()
                                .put("series", it.series.copy(description = "").toJson())
                                .put("season", it.season)
                                .put("upcoming", it.upcoming)
                                .put("seasonIndex", it.seasonIndex)
                        }
                    ),
                )
                .toString()
        val order = prefs.getString(ORDER, "").orEmpty().split('\n').filter { it.isNotEmpty() && it != id } + id
        val edit = prefs.edit().putString(id, encoded).putString(ORDER, order.takeLast(MAX_KEYS).joinToString("\n"))
        order.dropLast(MAX_KEYS).forEach { edit.remove(it) }
        edit.apply()
    }
}
