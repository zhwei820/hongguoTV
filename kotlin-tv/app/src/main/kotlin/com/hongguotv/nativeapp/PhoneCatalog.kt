// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.ContentType
import com.hongguotv.core.DiscoveryCursor
import com.hongguotv.core.DiscoveryFilter
import com.hongguotv.core.DiscoveryGroup
import com.hongguotv.core.DiscoverySelection
import com.hongguotv.core.EpisodeSummary
import com.hongguotv.core.RankBoard
import com.hongguotv.core.RankPosition
import com.hongguotv.core.Series
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * 手机竖屏的列表页外壳：顶部搜索框、内容区、底部五个栏目。
 * 实例在进出详情页和播放页时保留，各栏目的数据和滚动位置随之保留。
 */
internal class PhoneCatalog(
    private val activity: Activity,
    private val repository: ContentRepository,
    private val library: Library,
    private val cover: (ImageView, String) -> Unit,
    private val open: (Series) -> Unit,
    private val resume: (Series) -> Unit,
    private val checkUpdates: (View) -> Unit,
    private val confirm: (String, String, () -> Unit) -> Unit,
) : LinearLayout(activity), Closeable {
    companion object {
        const val HOME = 0
        const val DISCOVER = 1
        const val RANK = 2
        const val MINE = 3
        private val destinations =
            listOf(HOME to "首页", DISCOVER to "找剧", RANK to "排行", MINE to "我的")
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(3)
    private val cache = GridCache(activity)
    private var closed = false
    private val content = FrameLayout(activity)
    private val topBar = LinearLayout(activity)
    private val input = EditText(activity)
    private val cancel = TextView(activity)
    private val navButtons = mutableMapOf<Int, TextView>()
    private val pages = mutableMapOf<Int, Page>()
    private var search: SearchPage? = null
    var tab = HOME
        private set

    private fun dp(value: Number) = (value.toFloat() * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(TvStyle.background)
        buildTopBar()
        addView(topBar, LayoutParams(-1, -2))
        addView(content, LayoutParams(-1, 0, 1f))
        addView(View(activity).apply { setBackgroundColor(TvStyle.outline) }, LayoutParams(-1, 1))
        val nav = LinearLayout(activity).apply { setBackgroundColor(TvStyle.background) }
        destinations.forEach { (index, label) ->
            val button =
                TextView(activity).apply {
                    text = label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, dp(6))
                    compoundDrawablePadding = dp(2)
                    setOnClickListener { select(index) }
                }
            navButtons[index] = button
            nav.addView(button, LayoutParams(0, -2, 1f))
        }
        addView(nav, LayoutParams(-1, -2))
        select(HOME)
    }

    private fun buildTopBar() {
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(dp(12), dp(10), dp(12), dp(8))
        input.apply {
            hint = "搜索剧名"
            textSize = 15f
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(TvStyle.text)
            setHintTextColor(TvStyle.muted)
            background = TvStyle.shape(activity, TvStyle.surface, radius = 20)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnFocusChangeListener { _, focused -> if (focused) enterSearch() }
            setOnClickListener { enterSearch() }
            setOnEditorActionListener { _, action, event ->
                if (
                    action == EditorInfo.IME_ACTION_SEARCH ||
                        event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                ) {
                    submit(text.toString())
                    true
                } else false
            }
        }
        topBar.addView(input, LayoutParams(0, -2, 1f))
        cancel.apply {
            text = "取消"
            textSize = 15f
            setTextColor(TvStyle.text)
            setPadding(dp(14), dp(8), dp(4), dp(8))
            visibility = GONE
            setOnClickListener { leaveSearch() }
        }
        topBar.addView(cancel)
    }

    fun select(index: Int) {
        if (closed) return
        if (search != null) leaveSearch()
        if (index == tab && content.childCount > 0) {
            pages[index]?.top()
            return
        }
        tab = index
        destinations.forEach { (i, _) ->
            val selected = i == index
            navButtons[i]?.apply {
                setTextColor(if (selected) TvStyle.accent else TvStyle.muted)
                setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    TvGlyph(i, dp(22), if (selected) TvStyle.accent else TvStyle.muted),
                    null,
                    null,
                )
            }
        }
        content.removeAllViews()
        val page =
            pages.getOrPut(index) {
                when (index) {
                    DISCOVER -> DiscoverPage()
                    RANK -> RankPage()
                    MINE -> MinePage()
                    else -> HomePage()
                }
            }
        content.addView(page.view, FrameLayout.LayoutParams(-1, -1))
        page.shown()
    }

    /** 重新挂到窗口时调用：刷新本机数据，并补回被页面切换取消的封面加载。 */
    fun reattach() {
        if (closed) return
        search?.grid?.refreshCards()
        pages[tab]?.shown()
    }

    /** 返回键：先退出搜索，再回到首页；已在首页时返回 false，由调用方确认退出。 */
    fun back(): Boolean {
        if (search != null) {
            leaveSearch()
            return true
        }
        if (tab != HOME) {
            select(HOME)
            return true
        }
        return false
    }

    private fun enterSearch() {
        if (search != null) return
        val page = SearchPage()
        search = page
        cancel.visibility = VISIBLE
        content.removeAllViews()
        content.addView(page.view, FrameLayout.LayoutParams(-1, -1))
    }

    private fun leaveSearch() {
        search?.close()
        search = null
        cancel.visibility = GONE
        input.setText("")
        input.clearFocus()
        hideKeyboard()
        content.removeAllViews()
        pages[tab]?.let {
            content.addView(it.view, FrameLayout.LayoutParams(-1, -1))
            it.shown()
        }
    }

    private fun submit(query: String) {
        val keyword = query.trim()
        if (keyword.isEmpty()) return
        enterSearch()
        input.setText(keyword)
        input.setSelection(keyword.length)
        input.clearFocus()
        hideKeyboard()
        library.rememberSearch(keyword, ContentType.COMIC)
        search?.run(keyword)
    }

    private fun hideKeyboard() {
        (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
    }

    override fun close() {
        if (closed) return
        closed = true
        search?.close()
        pages.values.forEach { it.close() }
        worker.shutdownNow()
    }

    private fun <T> task(action: () -> T, done: (T) -> Unit, failed: (Throwable) -> Unit) {
        if (closed) return
        worker.submit {
            val result = runCatching(action)
            main.post {
                if (closed) return@post
                result.fold(done, failed)
            }
        }
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit) =
        TextView(activity).apply {
            text = label
            textSize = 13f
            maxLines = 1
            setTextColor(if (selected) TvStyle.accent else TvStyle.text)
            setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            background =
                TvStyle.shape(
                    activity,
                    if (selected) TvStyle.raised else TvStyle.surface,
                    if (selected) TvStyle.accent else android.graphics.Color.TRANSPARENT,
                    radius = 16,
                )
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { onClick() }
        }

    private fun strip(views: List<View>): HorizontalScrollView =
        HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            val row = LinearLayout(activity).apply { setPadding(0, dp(4), 0, dp(4)) }
            views.forEachIndexed { i, view ->
                row.addView(view, LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(8) })
            }
            addView(row)
        }

    private fun heading(label: String) =
        TextView(activity).apply {
            text = label
            textSize = 17f
            setTextColor(TvStyle.text)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(6))
        }

    /** 总集数取剧目角标里的「全/共 N 集」，收藏过的剧再参考检查更新时记下的集数；都没有时只显示进度。 */
    private fun watchedLabel(progress: WatchProgress): String {
        val total =
            maxOf(
                EpisodeSummary.total(progress.series.badge),
                library.favoriteUpdate(progress.series.id)?.total ?: 0,
            )
        val current = progress.episodeIndex + 1
        return when {
            progress.completed && total > 0 && current >= total -> "已看完 · 共 $total 集"
            total > 0 -> "第 $current / $total 集"
            else -> "看到第 $current 集"
        }
    }

    private fun failure(problem: Throwable): String =
        when (problem) {
            is java.net.UnknownHostException -> "无法连接内容网站，点此重试"
            is java.net.SocketTimeoutException -> "连接超时，点此重试"
            else -> "加载失败，点此重试"
        }

    private abstract inner class Page : Closeable {
        abstract val view: View

        open fun shown() = Unit

        open fun top() = Unit

        override fun close() = Unit
    }

    /** 分页加载到同一网格；token 让切换条件后到达的旧结果直接作废。 */
    private abstract inner class PagedPage : Page() {
        val grid = PhonePosterGrid(activity, cover, { open(it) })
        override val view: View
            get() = grid
        var loading = false
        var hasMore = true
        var token = 0

        init {
            grid.onNearEnd = { more() }
        }

        abstract fun more()

        override fun top() = grid.setSelection(0)

        override fun shown() = grid.refreshCards()

        /** reset 时先显示 cacheKey 对应的上次结果；首页请求成功后写回缓存。 */
        fun <T> fetch(reset: Boolean, action: () -> T, cacheKey: String? = null, done: (T) -> Unit) {
            val ticket = if (reset) ++token else token
            loading = true
            if (reset) {
                grid.items = cacheKey?.let { cache.get(it)?.items }.orEmpty()
                grid.setSelection(0)
            }
            grid.status(if (grid.items.isEmpty()) "正在加载…" else "正在更新…")
            task(
                action,
                { result ->
                    if (ticket != token) return@task
                    loading = false
                    done(result)
                    if (reset && cacheKey != null) cache.put(cacheKey, grid.items, hasMore)
                    grid.status(
                        if (grid.items.isEmpty()) "没有找到内容"
                        else if (hasMore) "" else "已经到底了"
                    )
                },
                { problem ->
                    if (ticket != token) return@task
                    loading = false
                    grid.status(failure(problem)) { if (reset) restart() else more() }
                },
            )
        }

        open fun restart() = Unit
    }

    private inner class HomePage : PagedPage() {
        private val resumeBlock = LinearLayout(activity).apply { orientation = VERTICAL }
        private var page = 0

        init {
            val header = LinearLayout(activity).apply { orientation = VERTICAL }
            header.addView(resumeBlock)
            header.addView(heading("热门推荐"))
            grid.header(header)
            restart()
        }

        override fun restart() {
            val type = ContentType.COMIC
            hasMore = true
            page = 0
            fetch(true, { repository.home(1, type) }, "home-${type.storedValue}") { result ->
                page = 1
                hasMore = result.hasMore
                grid.items = result.items
            }
        }

        override fun more() {
            if (loading || !hasMore || page == 0) return
            val type = ContentType.COMIC
            val next = page + 1
            fetch(false, { repository.home(next, type) }) { result ->
                page = next
                hasMore = result.hasMore
                grid.items = (grid.items + result.items).distinctBy { it.id }
            }
        }

        override fun shown() {
            super.shown()
            renderResume()
        }

        private fun renderResume() {
            resumeBlock.removeAllViews()
            val rows = library.history().filter { !it.completed }.take(20)
            if (rows.isEmpty()) return
            resumeBlock.addView(heading("接着看"))
            resumeBlock.addView(
                strip(
                    rows.map { progress ->
                        LinearLayout(activity).apply {
                            orientation = VERTICAL
                            val image = PosterImage(activity)
                            addView(image, LayoutParams(dp(96), -2))
                            addView(
                                TextView(activity).apply {
                                    text = progress.series.title
                                    textSize = 12f
                                    setTextColor(TvStyle.text)
                                    maxLines = 1
                                    ellipsize = TextUtils.TruncateAt.END
                                    setPadding(0, dp(4), 0, 0)
                                },
                                LayoutParams(dp(96), -2),
                            )
                            addView(
                                TextView(activity).apply {
                                    text = watchedLabel(progress)
                                    textSize = 11f
                                    setTextColor(TvStyle.muted)
                                },
                            )
                            if (progress.series.cover.isNotEmpty()) cover(image, progress.series.cover)
                            setOnClickListener { resume(progress.series) }
                            setOnLongClickListener {
                                confirm("移除接着看", "从观看记录中移除「${progress.series.title}」？") {
                                    library.removeHistory(progress.series.id)
                                    renderResume()
                                }
                                true
                            }
                        }
                    }
                )
            )
        }
    }

    private inner class DiscoverPage : PagedPage() {
        private val filters = LinearLayout(activity).apply { orientation = VERTICAL }
        // 不提供真人剧：接口结果里没有可区分真人剧的字段，「全部类型」会混入真人剧，所以类型只留漫剧 / AI 剧，默认漫剧。
        private val initial = DiscoverySelection(mapOf("genre" to "comic_series"))
        private var selection = initial
        private var groups = cache.discoveryGroups()
        private var next: DiscoveryCursor? = null

        init {
            grid.header(filters)
            restart()
        }

        override fun restart() {
            renderFilters()
            val chosen = selection
            val key = "discover-" + chosen.values.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
            fetch(true, { repository.filteredDiscovery(chosen, null) }, key) { result ->
                grid.items = result.items
                next = result.next
                hasMore = next != null
                if (result.groups.isNotEmpty() && result.groups != groups) {
                    groups = result.groups
                    if (selection == initial) cache.putDiscoveryGroups(groups)
                }
                renderFilters()
            }
        }

        override fun more() {
            val cursor = next ?: return
            if (loading) return
            val chosen = selection
            fetch(false, { repository.filteredDiscovery(chosen, cursor) }) { result ->
                grid.items = (grid.items + result.items).distinctBy { it.id }
                next = result.next
                hasMore = next != null
            }
        }

        private fun renderFilters() {
            filters.removeAllViews()
            filters.setPadding(0, 0, 0, dp(6))
            groups.forEach { group ->
                val chosen = selection.values[group.type].orEmpty()
                val options =
                    if (group.type == "genre")
                        group.options.filter { it.id != "real_series" && it.label != "真人剧" }
                    else listOf(DiscoveryFilter("", "全部")) + group.options
                val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                if (group.type != "genre")
                    row.addView(
                        TextView(activity).apply {
                            text = group.label
                            textSize = 12f
                            setTextColor(TvStyle.muted)
                        },
                        LayoutParams(dp(40), -2),
                    )
                row.addView(
                    strip(
                        options.map { option ->
                            chip(option.label, option.id == chosen) {
                                if (option.id != chosen) {
                                    selection = selection.choose(group.type, option.id)
                                    restart()
                                }
                            }
                        }
                    ),
                    LayoutParams(0, -2, 1f),
                )
                filters.addView(row)
            }
        }
    }

    private inner class RankPage : PagedPage() {
        private val boards = LinearLayout(activity).apply { orientation = VERTICAL }
        private val updated =
            TextView(activity).apply {
                textSize = 12f
                setTextColor(TvStyle.muted)
                setPadding(0, dp(2), 0, dp(6))
            }
        private var board = RankBoard.ALL
        private var page = 0
        private val positions = mutableMapOf<String, RankPosition>()

        init {
            val header = LinearLayout(activity).apply { orientation = VERTICAL }
            header.addView(boards)
            header.addView(updated)
            grid.header(header)
            // 缓存只有剧目顺序，没有名次数据；首页按顺序补名次。
            grid.corner = { item ->
                (positions[item.id]?.rank ?: grid.items.indexOf(item).takeIf { it >= 0 }?.plus(1))?.toString()
            }
            grid.subtitle = { item -> positions[item.id]?.heat?.takeIf { it.isNotBlank() } ?: item.badge }
            restart()
        }

        override fun restart() {
            renderBoards()
            val target = board
            positions.clear()
            page = 0
            fetch(true, { repository.rankPage(target, 1) }, "rank-${target.name}") { result ->
                page = 1
                result.ranking?.let {
                    positions.putAll(it.positions)
                    updated.text = it.updatedText
                }
                hasMore = result.hasMore || (result.ranking?.totalPages ?: 1) > 1
                grid.items = result.items
            }
        }

        override fun more() {
            if (loading || !hasMore || page == 0) return
            val target = board
            val nextPage = page + 1
            fetch(false, { repository.rankPage(target, nextPage) }) { result ->
                page = nextPage
                result.ranking?.let { positions.putAll(it.positions) }
                hasMore = nextPage < (result.ranking?.totalPages ?: 0) || result.hasMore
                grid.items = (grid.items + result.items).distinctBy { it.id }
            }
        }

        private fun renderBoards() {
            boards.removeAllViews()
            boards.addView(
                strip(
                    RankBoard.entries.filter { it != RankBoard.REAL }.map { entry ->
                        chip(entry.label, entry == board) {
                            if (entry != board) {
                                board = entry
                                restart()
                            }
                        }
                    }
                )
            )
        }
    }

    private inner class MinePage : Page() {
        private var showHistory = false
        private val grid =
            PhonePosterGrid(activity, cover, { open(it) }, { series -> remove(series) })
        private val tabs = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        override val view: View
            get() = grid

        init {
            grid.header(tabs)
        }

        override fun top() = grid.setSelection(0)

        override fun shown() = render()

        private fun render() {
            tabs.removeAllViews()
            tabs.setPadding(0, dp(4), 0, dp(6))
            tabs.addView(chip("收藏", !showHistory) { showHistory = false; render() })
            tabs.addView(
                chip("观看历史", showHistory) { showHistory = true; render() },
                LayoutParams(-2, -2).apply { leftMargin = dp(8) },
            )
            tabs.addView(View(activity), LayoutParams(0, 1, 1f))
            tabs.addView(
                TextView(activity).apply {
                    text = "检查更新 · ${BuildConfig.VERSION_NAME}"
                    textSize = 12f
                    setTextColor(TvStyle.muted)
                    setPadding(dp(8), dp(6), 0, dp(6))
                    setOnClickListener { checkUpdates(this) }
                }
            )
            if (showHistory) {
                val history = library.history()
                val episodes = history.associate { it.series.id to it }
                grid.subtitle = { item ->
                    episodes[item.id]?.let(::watchedLabel)
                        .orEmpty()
                }
                grid.items = history.map { it.series }
                grid.status(if (history.isEmpty()) "还没有观看记录" else "长按可删除记录")
            } else {
                val favorites = library.favorites()
                grid.subtitle = { it.badge }
                grid.items = favorites
                grid.status(if (favorites.isEmpty()) "还没有收藏，在剧集详情页点「收藏」即可加入" else "长按可取消收藏")
            }
        }

        private fun remove(series: Series) {
            if (showHistory)
                confirm("删除观看记录", "删除「${series.title}」的观看进度？") {
                    library.removeHistory(series.id)
                    render()
                }
            else
                confirm("取消收藏", "从收藏中移除「${series.title}」？") {
                    if (library.favorite(series.id)) library.toggle(series)
                    render()
                }
        }
    }

    private inner class SearchPage : PagedPage() {
        private val recent = LinearLayout(activity).apply { orientation = VERTICAL }
        private var keyword = ""
        private var page = 0

        init {
            grid.header(recent)
            renderRecent()
            grid.status("")
        }

        private fun renderRecent() {
            recent.removeAllViews()
            if (keyword.isNotEmpty()) return
            val rows = library.searches().map { it.query }.distinct()
            if (rows.isEmpty()) return
            recent.addView(heading("最近搜索"))
            recent.addView(strip(rows.map { query -> chip(query, false) { submit(query) } }))
        }

        fun run(query: String) {
            keyword = query
            renderRecent()
            restart()
        }

        override fun restart() {
            val query = keyword
            if (query.isEmpty()) return
            page = 0
            hasMore = true
            fetch(true, { repository.searchAll(query, 1) }) { result ->
                page = 1
                hasMore = result.hasMore
                grid.items = result.items
            }
        }

        override fun more() {
            if (loading || !hasMore || page == 0) return
            val query = keyword
            val nextPage = page + 1
            fetch(false, { repository.searchAll(query, nextPage) }) { result ->
                page = nextPage
                hasMore = result.hasMore
                grid.items = (grid.items + result.items).distinctBy { it.id }
            }
        }

        override fun close() {
            token++
        }
    }
}
