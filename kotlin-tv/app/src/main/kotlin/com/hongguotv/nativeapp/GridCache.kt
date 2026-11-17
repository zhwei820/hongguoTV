// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.CatalogPage
import com.hongguotv.core.CatalogSnapshot
import com.hongguotv.core.DiscoveryFilter
import com.hongguotv.core.DiscoveryGroup
import com.hongguotv.core.Series
import org.json.JSONArray
import org.json.JSONObject

/**
 * 列表首页内容缓存：进入栏目先显示上次结果，后台刷新后替换。
 * 只存首页元数据（CatalogSnapshot：最多 30 部、7 天过期），筛选组合可能很多，按最近使用保留 MAX_KEYS 个。
 */
internal class GridCache(context: Context) {
    private companion object {
        const val MAX_KEYS = 24
        const val ORDER = "__order"
        const val GROUPS = "__discoveryGroups.comic"
    }

    private val prefs = context.getSharedPreferences("grid-cache", Context.MODE_PRIVATE)
    private val memory = HashMap<String, CatalogPage>()

    fun get(key: String): CatalogPage? =
        memory[key]
            ?: prefs.getString(key, null)
                ?.let { CatalogSnapshot.decode(it, System.currentTimeMillis()) }
                ?.also { memory[key] = it }

    /** 找剧筛选项：随首页结果一起返回，单独存一份，进入页面时先渲染筛选。不过期，每次请求成功都覆盖。 */
    fun discoveryGroups(): List<DiscoveryGroup> =
        runCatching {
                val rows = JSONArray(prefs.getString(GROUPS, null) ?: return emptyList())
                (0 until rows.length()).map { i ->
                    val g = rows.getJSONObject(i)
                    val options = g.getJSONArray("options")
                    DiscoveryGroup(
                        g.getString("type"),
                        g.getString("label"),
                        (0 until options.length()).map {
                            val o = options.getJSONObject(it)
                            DiscoveryFilter(o.getString("id"), o.getString("label"))
                        },
                    )
                }
            }
            .getOrDefault(emptyList())

    fun putDiscoveryGroups(groups: List<DiscoveryGroup>) {
        if (groups.isEmpty()) return
        val encoded =
            JSONArray(
                groups.map { g ->
                    JSONObject()
                        .put("type", g.type)
                        .put("label", g.label)
                        .put("options", JSONArray(g.options.map { JSONObject().put("id", it.id).put("label", it.label) }))
                }
            )
        prefs.edit().putString(GROUPS, encoded.toString()).apply()
    }

    fun put(key: String, items: List<Series>, hasMore: Boolean) {
        if (items.isEmpty() || key == ORDER || key == GROUPS) return
        val now = System.currentTimeMillis()
        val encoded = CatalogSnapshot.encode(items, hasMore, now)
        CatalogSnapshot.decode(encoded, now)?.let { memory[key] = it } ?: return
        val order = (prefs.getString(ORDER, "").orEmpty().split('\n').filter { it.isNotEmpty() && it != key } + key)
        val evicted = order.dropLast(MAX_KEYS)
        val edit = prefs.edit().putString(key, encoded).putString(ORDER, order.takeLast(MAX_KEYS).joinToString("\n"))
        evicted.forEach {
            edit.remove(it)
            memory.remove(it)
        }
        edit.apply()
    }
}
