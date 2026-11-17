// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// TV 主页面入口：协调栏目切换、详情和正式播放；具体界面组件及数据请求由协作对象负责。

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import kotlin.math.roundToInt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : Activity() {
    private val surface get() = TvStyle.surface
    private val accent get() = TvStyle.accent
    private val white get() = TvStyle.text
    private val muted get() = TvStyle.muted
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val repository = ContentRepository(playbackSession = PlaybackDeviceSession.shared)

    // 榜单专用并行加载通道：最多四榜各自在途、互不取消，与全局单飞 work() 隔离。
    private val rankWorker = Executors.newFixedThreadPool(3)
    private val networkCleanup = Executors.newSingleThreadExecutor()
    private val prefetchWorker = Executors.newSingleThreadExecutor()
    private var prefetchHttp: okhttp3.OkHttpClient? = null
    private var playbackHttp: okhttp3.OkHttpClient? = null

    // 伴机兜底（bytevc2 锁定集）：会话与尝试标记随每次 playEpisode 重置。
    private var relayAttempted = false
    private var relaySessionId: String? = null
    private var relaySlowCount = 0
    private var relaySlowHintShown = false
    private var relaySpeedWatch: Runnable? = null
    private val relayStore by lazy { RelayStore(this) }
    private val relayHttp: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
    private val preparedNext =
        PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() })
    private var prefetchJob: java.util.concurrent.Future<*>? = null
    private var prefetchScope: RequestScope? = null

    private data class PendingEpisodePreparation(
        val key: String,
        val job: java.util.concurrent.Future<*>,
        val scope: RequestScope,
        val http: okhttp3.OkHttpClient,
    )

    private var promotedPreparation: PendingEpisodePreparation? = null
    private var lastPlaybackFailure = ""
    private var lastPlaybackDetail: String? = null
    private var autoRecoveredPlay = false
    private var queuedNext: RemoteVideo? = null
    private var queuedHttp: okhttp3.OkHttpClient? = null
    private var queuedEpisode = -1
    private var episodeDuration = 0L
    private var prefetchedFor = ""
    private var readySince = 0L
    private lateinit var artwork: ArtworkLoader

    private data class DeferredUi(val apply: () -> Unit, val discard: () -> Unit = {})

    private var deferredWork: DeferredUi? = null
    private var initialCatalogPending = true
    private var restoreStoppedPage = false
    private var pendingDetail: Series? = null
    // 仅在当前详情中保留云端续播信息，并按账号会话隔离，不预先导入本机历史。
    private var seasonGeneration = 0
    private var seasonBanner: TextView? = null
    private var nextSeasonWork: RelatedWork? = null
    private var seasonSuppressed = false
    private var seasonPending = false
    private var detailAutoplay = true
    private var libraryLoadError: Throwable? = null
    private var workScope: RequestScope? = null
    private var workJob: java.util.concurrent.Future<*>? = null
    private var playerListener: Player.Listener? = null
    private lateinit var playbackTitle: TextView
    private var directPlayback = false
    private var prefetchAttemptAt = 0L
    private var prefetchAttempts = 0
    private var phoneShell: PhoneCatalog? = null
    private val seasonCache by lazy { SeasonCache(this) }
    private var phoneOverlay: PhonePlayerOverlay? = null
    private lateinit var library: Library
    private lateinit var tvTools: TvTools
    private lateinit var updater: AppUpdater

    private lateinit var mediaSession: TvMediaSession
    private val sleepTimer = SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepStopped = false
    private var playerView: PlayerView? = null
    private val social by lazy {
        TvSocial(
            this,
            { player },
            {
                if (screen == "player" || screen == "detail")
                    detail?.let { data ->
                        data.episodes.getOrNull(episodeIndex)?.let { data.series.id to it }
                    }
                else null
            },
        )
    }
    private var episodePanel: EpisodePanel? = null
    private val recovery = RecoveryBudget()
    private var retryPending = false
    private var autoRecovery = false
    private var recoveryScheduledAt = 0L
    private var recoveryDelayMillis = 0L

    /**
     * 恢复等待期的逐秒倒计时（issue #41 后续）：慢段单次等待最长 105 秒，静态文案看起来像死机。
     * 只按剩余毫秒刷新文本，调度权仍在 scheduleRecovery/retryRunnable；remaining 归零即停，
     * 由 retryRunnable 接管。
     */
    private val recoveryCountdown =
        object : Runnable {
            override fun run() {
                if (!retryPending) return
                val remaining =
                    recoveryScheduledAt + recoveryDelayMillis - android.os.SystemClock.uptimeMillis()
                if (remaining <= 0) return
                showRecoveryStatus(remaining)
                main.postDelayed(this, 1000)
            }
        }
    private var startPosition = 0L
    private var retryPosition = 0L
    private var requestedAutoplay = true
    private var waitingForFirstFrame = false
    private var hideAutoplayOverlay = false
    private var recoveryDisplayActive = false
    private var allowSlowRecovery = false
    private var playbackPreparationUntil = 0L
    private var recoveryAction: TextView? = null
    private var retryAutoplay = true
    private var foreground = false
    private lateinit var connectivity: ConnectivityManager
    private var networkRegistered = false
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            private fun changed() {
                main.post {
                    if (connectivity.isActiveNetworkMetered || !networkAvailable()) cancelPrefetch()
                    if (foreground && screen == "player" && playError && autoRecovery)
                        scheduleRecovery()
                }
            }

            override fun onAvailable(network: Network) {
                changed()
            }

            override fun onLost(network: Network) {
                changed()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                changed()
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                changed()
            }
        }
    private val retryRunnable = Runnable {
        retryPending = false
        if (foreground && screen == "player" && playError && autoRecovery) {
            if (!networkAvailable()) scheduleRecovery()
            else if (!allowSlowRecovery && android.os.SystemClock.elapsedRealtime() >= playbackPreparationUntil) scheduleRecovery()
            else if (recovery.consume()) {
                android.util.Log.i(
                    "HongguoTV",
                    "Automatic playback recovery ${recovery.attempts}/${recovery.total} at ${retryPosition}ms",
                )
                autoRecoveredPlay = true
                playEpisode(episodeIndex, retryPosition, retryAutoplay, recovering = true)
            }
        }
    }
    private lateinit var root: FrameLayout
    private var generation = 0
    private var screen = "catalog"
    private var catalog = emptyList<Series>()

    private var detail: Detail? = null
    private var episodeIndex = 0
    private var group = 0
    private var synopsisExpanded = false
    private var player: ExoPlayer? = null
    private var video: RemoteVideo? = null
    private var playbackReady = false
    private var pausedForLifecycle = false
    private lateinit var hud: LinearLayout
    private var persistentProgress: LinearLayout? = null
    private var persistentTime: TextView? = null
    private var persistentBar: ProgressBar? = null
    private lateinit var playbackText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var controls: LinearLayout
    private var panel = false
    private var playbackClock: TextClock? = null
    private var transportPlay: TextView? = null
    private var sleepButton: TextView? = null
    private var relayDialog: AlertDialog? = null
    private var pendingSeek: Long? = null
    private var playError = false
    private var lastSaved = 0L
    private var quality = ""
    private var attemptedQuality = 0
    private var currentTrack: VideoTracks.Track? = null
    private var currentRendition: Rendition? = null
    private var currentTiers: List<Rendition> = emptyList()
    private var autoDowngrades = 0
    private val stallGuard = PlaybackStallGuard()
    private var lastPlaybackDiagnosticAt = 0L
    private val tick =
        object : Runnable {
            override fun run() {
                if (!foreground || isDestroyed) return
                if (screen == "player") {
                    if (sleepTimer.poll()) stopForSleep()
                    updateClock()
                    if (hud.visibility == View.VISIBLE || phoneOverlay?.showing == true || library.alwaysShowProgress) updatePlaybackText()
                    maybePrefetch()
                    maybePrepareSeason()
                    updateSeasonBanner()
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (android.util.Log.isLoggable("HGPlayback", android.util.Log.DEBUG) && now - lastPlaybackDiagnosticAt >= 5000) {
                        player?.let { p ->
                            android.util.Log.d("HGPlayback", "state=${p.playbackState} playing=${p.isPlaying} suppressed=${p.playbackSuppressionReason} position=${p.currentPosition} buffered=${p.totalBufferedDuration} audio=${p.audioDecoderCounters?.renderedOutputBufferCount} audioQueued=${p.audioDecoderCounters?.queuedInputBufferCount} audioMime=${p.audioFormat?.sampleMimeType} audioRate=${p.audioFormat?.sampleRate} audioChannels=${p.audioFormat?.channelCount} video=${p.videoDecoderCounters?.renderedOutputBufferCount}")
                        }
                        lastPlaybackDiagnosticAt = now
                    }
                    player?.let { p ->
                        val frames = p.videoDecoderCounters?.renderedOutputBufferCount
                        if (stallGuard.offer(
                            episodeId = detail?.episodes?.getOrNull(episodeIndex).orEmpty(),
                            now = now,
                            frames = frames,
                            eligible = playbackReady && !waitingForFirstFrame && !playError &&
                                pendingSeek == null && p.isPlaying && root.hasWindowFocus() &&
                                p.playbackState == Player.STATE_READY && p.totalBufferedDuration >= 5000,
                        )) {
                            playerFailure(PlaybackException(
                                "视频解码停滞，正在尝试兼容恢复", null,
                                PlaybackException.ERROR_CODE_DECODING_FAILED,
                            ))
                        }
                    }
                    if (player?.isPlaying == true && now - lastSaved > 5000) {
                        saveProgress()
                        lastSaved = now
                    }
                }
                if (screen != "player") stallGuard.suspendObservation()
                main.postDelayed(this, 1000)
            }
        }
    private val hideHud = Runnable {
        if (screen == "player" && player?.isPlaying == true && !playError && !waitingForFirstFrame && root.hasWindowFocus())
            hidePlaybackOverlay()
    }
    private val seekRunnable = Runnable {
        pendingSeek?.let { player?.seekTo(it) }
        pendingSeek = null
    }

    private fun dp(value: Number) = (value.toFloat() * resources.displayMetrics.density).toInt()

    private fun widthDp() = resources.displayMetrics.widthPixels / resources.displayMetrics.density

    private fun lp(
        w: Int = LinearLayout.LayoutParams.MATCH_PARENT,
        h: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
    ) = LinearLayout.LayoutParams(w, h)

    private fun rounded(color: Int, border: Int = Color.TRANSPARENT) =
        TvStyle.shape(this, color, border)

    private fun text(value: String, size: Float = 16f, color: Int = white) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
        }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row() =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun focusStyle(view: View, selected: Boolean = false) {
        view.id = View.generateViewId()
        view.isSelected = selected
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        fun paint(focused: Boolean) {
            view.background = TvStyle.card(this, focused, selected)
        }
        paint(false)
        view.setOnFocusChangeListener { _, focused -> paint(focused) }
    }

    // 统一创建可点击的文字按钮；将界面样式、焦点效果与业务回调连接起来。
    private fun button(label: String, selected: Boolean = false, onClick: () -> Unit): TextView =
        text(label, 15f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minHeight = dp(42)
            if (selected) setTextColor(accent)
            focusStyle(this, selected)
            setOnClickListener { onClick() }
        }

    private fun addButton(
        parent: LinearLayout,
        label: String,
        selected: Boolean = false,
        onClick: () -> Unit,
    ): TextView {
        val view = button(label, selected, onClick)
        parent.addView(
            view,
            lp().apply {
                width = LinearLayout.LayoutParams.WRAP_CONTENT
                rightMargin = dp(8)
            },
        )
        return view
    }

    // 启动入口：创建根容器，初始化账户、更新和媒体控制，再异步读取本机记录。
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        TouchUi.install(window)
        TvStyle.load(this)
        ContentRepository.deviceStore = PreferencesPlayerDeviceStore(
            getSharedPreferences("native-player-device", MODE_PRIVATE)
        )
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        library = Library(this)
        artwork = ArtworkLoader(this)
        tvTools = TvTools(this)
        root = FrameLayout(this).apply { background = TvVisual.backdrop(this@MainActivity) }
        setContentView(root)
        CodecCapabilities.init(this)
        CodecCapabilities.logDeviceCapabilities()
        updater =
            AppUpdater(this) {
                foreground &&
                    library.isLoaded &&
                    screen == "catalog" &&
                    !tvTools.showing
            }
        connectivity = NetworkCompat.manager(this)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 24)
                connectivity.registerDefaultNetworkCallback(networkCallback)
            else
                connectivity.registerNetworkCallback(
                    android.net.NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    networkCallback,
                )
            networkRegistered = true
        }
        mediaSession =
            TvMediaSession(
                this,
                { if (foreground && screen == "player") requestPlayback(true) },
                { if (foreground && screen == "player") requestPlayback(false) },
                { position -> if (foreground && screen == "player") seekTo(position) },
                { direction -> skipEpisode(direction) },
                { if (foreground && screen == "player") returnToDetail() },
            )
        message(base(), "正在读取本机记录…")
        loadLibrary()
    }

    private fun showLibraryError(problem: Throwable) {
        val body = base()
        error(body, problem) {
            message(base(), "正在读取本机记录…")
            loadLibrary()
        }
    }

    // 本机记录加载成功后才展示内容；失败时保留错误并在前台提供重试入口。
    private fun loadLibrary() {
        libraryLoadError = null
        library.loadAsync(
            onFailure = { problem ->
                if (!isDestroyed) {
                    libraryLoadError = problem
                    if (foreground) showLibraryError(problem)
                }
            }
        ) {
            if (!isDestroyed && foreground) showCatalog()
        }
    }

    // 创建页面公共容器；清除旧视图后设置边距，可按需添加标题和返回按钮。
    private fun base(title: String? = null): LinearLayout {
        root.removeAllViews()
        root.background = TvVisual.backdrop(this)
        val container = column()
        val horizontal = dp(widthDp() * .05f)
        val vertical = (resources.displayMetrics.heightPixels * .05f).toInt()
        container.setPadding(horizontal, vertical, horizontal, vertical)
        root.addView(container, FrameLayout.LayoutParams(-1, -1))
        if (title != null) {
            val heading = row()
            addButton(heading, "‹ 返回") { goBack() }
            heading.addView(
                text(title, 22f).apply { setTypeface(null, Typeface.BOLD) },
                lp(0, dp(44)).apply { weight = 1f },
            )
            container.addView(heading)
        }
        return container
    }

    private fun loadCover(view: ImageView, url: String) {
        var ancestor: View? = view
        var focused = false
        while (ancestor != null && ancestor !== root) {
            if (ancestor.isFocused) {
                focused = true
                break
            }
            ancestor = ancestor.parent as? View
        }
        artwork.load(
            view,
            url,
            if (focused) ArtworkLoader.PRIORITY_FOCUSED else ArtworkLoader.PRIORITY_VISIBLE,
        )
    }

    private fun cancelWork() {
        deferredWork?.discard?.invoke()
        deferredWork = null
        workScope?.cancel()
        workScope = null
        workJob?.cancel(true)
        workJob = null
        cancelPromotedPreparation()
        (io as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }

    private fun invalidatePage() {
        cancelNextSeason()
        generation++
        cancelWork()
        artwork.cancelPage()
    }

    // 后台执行耗时任务；回到主线程前校验页面代次，避免旧请求覆盖新页面。
    private fun <T> work(action: () -> T, done: (T) -> Unit, failed: (Throwable) -> Unit) {
        cancelWork()
        val ticket = generation
        val scope = RequestScope()
        workScope = scope
        workJob =
            io.submit {
                try {
                    val result = scope.run(action)
                    main.post {
                        val discard = {
                            if (result is java.io.Closeable) result.close()
                            Unit
                        }
                        if (isDestroyed || generation != ticket) discard()
                        else if (foreground) done(result)
                        else {
                            deferredWork?.discard?.invoke()
                            deferredWork = DeferredUi({ done(result) }, discard)
                        }
                    }
                } catch (e: Exception) {
                    main.post {
                        if (!isDestroyed && generation == ticket) {
                            if (foreground) failed(e) else deferredWork = DeferredUi({ failed(e) })
                        }
                    }
                }
            }
    }

    private fun message(parent: LinearLayout, label: String) {
        parent.addView(text(label, 17f, muted).apply { setPadding(0, dp(28), 0, dp(18)) })
    }

    private fun error(parent: LinearLayout, problem: Throwable, retry: () -> Unit) {
        message(parent, "暂时无法加载，请检查网络后重试。")
        val detail =
            if (problem is java.net.UnknownHostException) "无法连接内容网站"
            else if (problem is java.net.SocketTimeoutException) "连接超时"
            else problem.message.orEmpty().replace(Regex("https?://\\S+"), "[地址]").take(160)
        parent.addView(text(detail, 13f, muted))
        addButton(parent, "重试", onClick = retry).requestFocus()
    }

    /** resume 为真时取到详情后直接按本机进度续播，返回时回列表页；否则展示详情页。 */
    private fun openDetail(series: Series, resume: Boolean = false, autoplay: Boolean = true) {
        pendingDetail = series
        detailAutoplay = autoplay
        invalidatePage()
        screen = "detail"
        phoneOrientation(player = false)
        directPlayback = resume
        detail = null
        synopsisExpanded = false
        val body = base(series.title)
        message(body, "正在加载剧集…")
        work(
            { repository.detail(series.id) },
            { result ->
                detail = result
                library.observeFavorite(
                    series.id,
                    result.episodes.size,
                    System.currentTimeMillis(),
                    acknowledge = true,
                )
                val progress = detailProgress(result)
                val target =
                    ResumePlayback.target(
                        result.episodes,
                        progress?.episodeId,
                        progress?.position ?: 0,
                        progress?.completed == true,
                    )
                episodeIndex = target.index
                group = episodeIndex / 20
                if (resume) playEpisode(target.index, target.position, autoplay = detailAutoplay)
                else showDetail()
            },
            { problem -> error(body, problem) { openDetail(series, resume, detailAutoplay) } },
        )
    }

    private fun detailProgress(data: Detail): WatchProgress? =
        ResumePlayback.progress(data, library.progress(data.series.id), null, false)

    // 正式播放入口：准备指定剧集的播放资源与播放器状态。
    private fun playEpisode(
        index: Int,
        position: Long = 0,
        autoplay: Boolean = true,
        recovering: Boolean = false,
        autoDowngrade: Boolean = false,
        automaticNext: Boolean = false,
        recreateView: Boolean = false,
    ) {
        cancelNextSeason()
        seasonSuppressed = false
        updater.interrupt()
        social.reset()
        val data = detail ?: return
        var playbackStarted = android.os.SystemClock.elapsedRealtime()
        val keepRecoveryStatus = recovering || (screen == "player" && playError && index == episodeIndex)
        val reuse =
            screen == "player" && player != null && playerView != null && (!playError || recovering) && !recreateView
                && ((playerView?.videoSurfaceView is TextureView) == VideoRotation.usesTexture(library.videoRotation))
        library.setWatched(data.series.id, false)
        val selectedIndex = index.coerceIn(0, data.episodes.lastIndex)
        if (
            position == 0L &&
                autoplay &&
                !recovering &&
                !playError &&
                queuedEpisode == selectedIndex &&
                queuedNext != null &&
                player?.hasNextMediaItem() == true
        ) {
            requestedAutoplay = true
            pausedForLifecycle = false
            player?.seekToNextMediaItem()
            player?.play()
            return
        }
        val prepareKey = "${data.episodes[selectedIndex]}:${library.maxQuality}"
        // 切到正在准备的下一集时接管原任务，不能先 take/clear 再取消，否则会重新请求同一集。
        val pending =
            if (position == 0L && !recovering) claimPendingPreparation(prepareKey) else null
        val prefetched =
            if (position == 0L && !recovering && pending == null) preparedNext.take(prepareKey)
            else null
        val claimedHttp =
            if (prefetched != null) prefetchHttp.also { prefetchHttp = null } else null
        android.util.Log.i(
            "HongguoTV",
            "Episode transition target=${selectedIndex+1} cached=${prefetched!=null} warmupAttempts=$prefetchAttempts warmupRunning=${prefetchJob?.isDone==false}",
        )
        saveProgress()
        library.flush()
        invalidatePage()
        relayStopAsync() // 换集即结束上一会话；伴机兜底会另起新会话
        if (reuse) {
            cancelRecovery()
            cancelPrefetchState(keepPrepared = pending != null)
            tvTools.close()
            episodePanel?.dismiss()
            episodePanel = null
            playerListener?.let { player?.removeListener(it) }
            playerListener = null
            player?.stop()
            player?.clearMediaItems()
            video?.close()
            video = null
            closeTransport(playbackHttp)
            playbackHttp = null
            main.removeCallbacks(hideHud)
            main.removeCallbacks(seekRunnable)
            pendingSeek = null
        } else releasePlayer(keepPrepared = pending != null)
        artwork.cancelPage(clearMemory = true)
        playbackHttp = claimedHttp
        video = prefetched
        var firstReady = true
        var firstFrame = true
        if (!recovering) {
            recovery.reset()
            allowSlowRecovery = false
            playbackPreparationUntil = android.os.SystemClock.elapsedRealtime() + 20_000
            autoRecoveredPlay = false
        }
        autoRecovery = false
        readySince = 0
        startPosition = position.coerceAtLeast(0)
        requestedAutoplay = autoplay
        if (autoplay) sleepStopped = false
        screen = "player"
        phoneOrientation(player = true)
        if (screenOffPlayback) ScreenOffPlaybackService.start(this, playingLabel())
        episodeIndex = selectedIndex
        group = episodeIndex / 20
        panel = false
        playError = false
        waitingForFirstFrame = true
        hideAutoplayOverlay = automaticNext && !keepRecoveryStatus
        recoveryDisplayActive = keepRecoveryStatus
        relayAttempted = false
        relaySlowHintShown = false
        stopRelaySpeedWatch()
        playbackReady = false
        episodeDuration = 0
        quality = ""
        attemptedQuality = 0
        currentTrack = null
        currentRendition = null
        currentTiers = emptyList()
        if (!autoDowngrade) autoDowngrades = 0
        pausedForLifecycle = !autoplay
        player?.playWhenReady = requestedAutoplay
        prefetchAttempts = 0
        prefetchAttemptAt = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!reuse) {
            root.removeAllViews()
            root.setBackgroundColor(Color.BLACK)
            val view =
                (if (VideoRotation.usesTexture(library.videoRotation))
                    layoutInflater.inflate(R.layout.video_player, root, false) as PlayerView else PlayerView(this)).apply {
                    useController = false
                    resizeMode = frameResizeMode()
                    isFocusable = false
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(Color.BLACK)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                }
            playerView = view
            view.setOnTouchListener(
                TouchUi.gestures(
                    this,
                    onTap = { if (phoneOverlay?.showing == true) hidePlaybackOverlay() else showHud() },
                    onDoubleTap = { x ->
                        when {
                            x < .33f -> seek(-1, 0)
                            x > .67f -> seek(1, 0)
                            else -> togglePlayback()
                        }
                    },
                )
            )
            root.addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyVideoRotation() }
            view.post { if (playerView === view) applyVideoRotation() }
            persistentTime = text("", 12f, Color.WHITE)
            persistentBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(accent) }
            persistentProgress = column().apply {
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                setPadding(dp(12), dp(4), dp(12), dp(6))
                setBackgroundColor(Color.argb(160, 0, 0, 0))
                addView(persistentTime); addView(persistentBar, lp(-1, dp(3)))
                visibility = View.GONE
            }
            root.addView(persistentProgress, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            social.attach(root)
            playbackClock = TextClock(this).apply {
                format12Hour = "HH:mm"
                format24Hour = "HH:mm"
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = rounded(Color.argb(150, 11, 15, 21))
                isFocusable = false
            }.also { clock ->
                root.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                    topMargin = (resources.displayMetrics.heightPixels * .05f).toInt()
                    rightMargin = dp(widthDp() * .05f)
                })
            }
            hud =
                column().apply {
                    setPadding(
                        dp(widthDp() * .05f),
                        dp(20),
                        dp(widthDp() * .05f),
                        (resources.displayMetrics.heightPixels * .05f).toInt(),
                    )
                    background =
                        GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.TRANSPARENT, Color.argb(240, 11, 15, 21)),
                        )
                }
            playbackTitle =
                text("", 23f, Color.WHITE).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTypeface(null, Typeface.BOLD)
                }
            hud.addView(playbackTitle)
            playbackText = text("", 15f, Color.LTGRAY).apply { setPadding(0, dp(10), 0, dp(9)) }
            hud.addView(playbackText)
            progressBar =
                ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progressTintList = android.content.res.ColorStateList.valueOf(accent)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(surface)
                }
            hud.addView(progressBar, lp(-1, dp(4)))
            controls =
                column().apply {
                    setPadding(0, dp(12), 0, 0)
                    visibility = View.GONE
                }
            hud.addView(controls)
            root.addView(
                ScrollView(this).apply {
                    isVerticalScrollBarEnabled = false
                    addView(hud)
                },
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM),
            )
            phoneOverlay =
                PhonePlayerOverlay(
                        this,
                        back = ::goBack,
                        toggle = ::togglePlayback,
                        seek = ::seekTo,
                        interacted = ::showHud,
                        previous = { skipEpisode(-1) },
                        next = { skipEpisode(1) },
                        episodes = { showEpisodePanel(it) },
                        speed = { showSpeedPicker(it) },
                        quality = { showQualityPicker(it, true) },
                        danmaku = { social.settings(it) },
                        comments = { social.showComments(it) },
                        favorite = {
                            detail?.series?.let {
                                library.toggle(it)
                                library.favorite(it.id)
                            } ?: false
                        },
                        settings = { showPlaybackSettings(it) },
                        rotate = {
                            portraitPlayer = !portraitPlayer
                            phoneOrientation(player = true)
                        },
                    )
                    .also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        }
        playbackTitle.text = "${data.series.title}  ·  第 ${episodeIndex+1} 集"
        playbackText.text = preparingPlaybackText(recoveryDisplayActive, lastPlaybackFailure, false)
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        main.removeCallbacks(hideHud)
        hud.visibility = if (hideAutoplayOverlay) View.GONE else View.VISIBLE
        updateClock()
        controls.removeAllViews()
        controls.visibility = if (recoveryDisplayActive) View.VISIBLE else View.GONE
        if (recoveryDisplayActive) {
            addButton(controls, "取消恢复") { stopPreparingPlayback() }
            addButton(controls, "返回选集") { returnToDetail(true) }
        }
        transportPlay = null
        sleepButton = null
        syncMediaSession()
        val requestedId = data.episodes[episodeIndex]
        val maxQuality = library.maxQuality
        val ticket = generation
        var usedPending = false
        work(
            {
                val remaining = if (allowSlowRecovery) 20_000L else
                    playbackPreparationUntil - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0) throw java.net.SocketTimeoutException("播放准备超时")
                RequestScope.withDeadline(System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(remaining)) {
                    val prepared =
                        prefetched
                            ?: pending?.let {
                                awaitPreparedEpisode(it, remaining).also { ready -> usedPending = ready != null }
                            }
                    prepared?.let { OpenedStream(it, null, emptyList(), it.trackInfo()) }
                        ?: openStream(
                            repository.http,
                            repository,
                            requestedId,
                            maxQuality,
                            CodecCapabilities::supports,
                            metadataAhead = data.episodes.drop(selectedIndex + 1).take(2),
                        ) {
                            it == null || CodecCapabilities.supports(it)
                        }
                        ?: throw java.io.IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
                }
            },
            { opened ->
                if (promotedPreparation === pending) promotedPreparation = null
                if (pending != null) {
                    if (usedPending) playbackHttp = pending.http else closeTransport(pending.http)
                }
                val remote = opened.video
                video = remote
                quality = playedQualityLabel(opened, maxQuality)
                attemptedQuality = opened.rendition?.quality ?: 0
                currentTrack = opened.track
                currentRendition = opened.rendition
                currentTiers = opened.tiers
                val p =
                    ensurePlayer()
                p.setPlaybackSpeed(library.playbackSpeed)
                val listener =
                    object : Player.Listener {
                        private fun active() =
                            player === p &&
                                generation == ticket &&
                                p.currentMediaItem?.mediaId == data.episodes.getOrNull(episodeIndex)

                        override fun onPlaybackStateChanged(state: Int) {
                            if (!active()) return
                            if (state == Player.STATE_READY) {
                                if (!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                                    playerFailure(
                                        PlaybackException(
                                            "资源没有可解码的视频轨道",
                                            null,
                                            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                                        )
                                    )
                                    return
                                }
                                playbackReady = true
                                episodeDuration = p.duration.coerceAtLeast(0)
                                if (firstReady) {
                                    firstReady = false
                                    readySince = android.os.SystemClock.elapsedRealtime()
                                    android.util.Log.i(
                                        "HongguoTV",
                                        "Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-playbackStarted}",
                                    )
                                }
                                if (hud.visibility == View.VISIBLE || phoneOverlay?.showing == true || library.alwaysShowProgress) updatePlaybackText()
                            }
                            syncMediaSession()
                            if (state == Player.STATE_ENDED) {
                                saveProgress(true)
                                if (sleepTimer.episodeEnded()) stopForSleep()
                                else if (
                                    foreground &&
                                        requestedAutoplay &&
                                        !pausedForLifecycle &&
                                        library.autoNext &&
                                        episodeIndex < data.episodes.lastIndex
                                )
                                    playEpisode(episodeIndex + 1, automaticNext = true)
                                else {
                                    val continueSeason = requestedAutoplay
                                    requestedAutoplay = false
                                    p.pause()
                                    playbackText.text = "本集已结束"
                                    showPanel()
                                    if (continueSeason && foreground && !pausedForLifecycle && !sleepStopped && library.autoNext &&
                                        episodeIndex == data.episodes.lastIndex) finishSeason(data)
                                }
                            }
                        }

                        override fun onRenderedFirstFrame() {
                            if (active() && firstFrame) {
                                firstFrame = false
                                playbackFirstFrame()
                                android.util.Log.i(
                                    "HongguoTV",
                                    "Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-playbackStarted}",
                                )
                            }
                        }

                        override fun onIsPlayingChanged(playing: Boolean) {
                            if (active()) {
                                if (hud.visibility == View.VISIBLE) showHud()
                                syncMediaSession()
                            }
                        }

                        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                            if (active()) syncMediaSession()
                        }

                        override fun onPositionDiscontinuity(
                            oldPosition: Player.PositionInfo,
                            newPosition: Player.PositionInfo,
                            reason: Int,
                        ) {
                            if (
                                player === p &&
                                    generation == ticket &&
                                    newPosition.mediaItem?.mediaId ==
                                        queuedNext?.let {
                                            data.episodes.getOrNull(queuedEpisode)
                                        } &&
                                    reason in
                                        listOf(
                                            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
                                            Player.DISCONTINUITY_REASON_SEEK,
                                        )
                            ) {
                                if (
                                    advanceQueuedEpisode(
                                        oldPosition.positionMs,
                                        reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
                                    )
                                ) {
                                    playbackStarted = android.os.SystemClock.elapsedRealtime()
                                    firstReady = true
                                    firstFrame = true
                                }
                            }
                            if (active()) syncMediaSession()
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (active()) playerFailure(error)
                        }
                    }
                playerListener = listener
                p.addListener(listener)
                val source = episodeSource(remote, requestedId)
                p.setMediaSource(source)
                p.seekTo(position.coerceAtLeast(0))
                p.prepare()
                p.playWhenReady =
                    foreground && requestedAutoplay && !pausedForLifecycle && episodePanel == null
            },
            { problem ->
                cancelPromotedPreparation()
                playerFailure(problem)
            },
        )
        // work() 会先取消上一项工作，因此在它建立新任务之后绑定本次接管资源。
        promotedPreparation = pending
    }

    private fun claimPendingPreparation(key: String): PendingEpisodePreparation? {
        if (prefetchedFor != key || preparedNext.isFresh(key)) return null
        val job = prefetchJob?.takeIf { !it.isDone } ?: return null
        val scope = prefetchScope ?: return null
        val http = prefetchHttp ?: return null
        prefetchJob = null
        prefetchScope = null
        prefetchHttp = null
        prefetchedFor = ""
        return PendingEpisodePreparation(key, job, scope, http)
    }

    private fun awaitPreparedEpisode(pending: PendingEpisodePreparation, remainingMillis: Long): RemoteVideo? {
        try {
            pending.job.get(remainingMillis.coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            pending.scope.cancel()
            pending.job.cancel(true)
            closeTransport(pending.http)
            throw java.net.SocketTimeoutException("预加载等待超时")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("播放准备已取消")
        } catch (_: java.util.concurrent.ExecutionException) {
            // 预加载失败只影响本次投机任务；正式播放仍可正常重新获取一次。
        } catch (_: java.util.concurrent.CancellationException) {
            throw java.io.InterruptedIOException("播放准备已取消")
        }
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("播放准备已取消")
        return preparedNext.take(pending.key)
    }

    private fun cancelPromotedPreparation() {
        val pending = promotedPreparation ?: return
        promotedPreparation = null
        pending.scope.cancel()
        pending.job.cancel(true)
        preparedNext.clear()
        closeTransport(pending.http)
    }

    private fun closeTransport(http: okhttp3.OkHttpClient?) {
        if (http == null) return
        // Closing an idle TLS connection can write close_notify; keep it off the UI thread.
        networkCleanup.execute {
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    private fun cancelPrefetch() {
        cancelPrefetchState(keepPrepared = false)
    }

    private fun cancelPrefetchState(keepPrepared: Boolean) {
        clearQueuedEpisode()
        prefetchScope?.cancel()
        prefetchScope = null
        if (!keepPrepared) preparedNext.clear()
        prefetchJob?.cancel(true)
        prefetchJob = null
        closeTransport(prefetchHttp)
        prefetchHttp = null
        prefetchedFor = ""
    }

    private fun maybePrefetch() {
        val p = player ?: return
        val data = detail ?: return
        if (
            !foreground ||
                !p.isPlaying ||
                !library.autoNext ||
                playError ||
                sleepStopped ||
                sleepTimer.episodesLeft == 1 ||
                queuedNext != null ||
                episodeIndex >= data.episodes.lastIndex
        )
            return
        val id = data.episodes[episodeIndex + 1]
        val metadataAhead = data.episodes.drop(episodeIndex + 2).take(2)
        val quality = library.maxQuality
        val key = "$id:$quality"
        if (preparedNext.isFresh(key) || prefetchJob?.isDone == false) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (
            readySince == 0L ||
                !PrefetchPolicy.canStart(
                    p.currentPosition,
                    p.duration,
                    p.playbackParameters.speed,
                    now - readySince,
                    p.bufferedPosition - p.currentPosition,
                )
        )
            return
        if (
            prefetchAttempts >= 3 ||
                (prefetchAttempts > 0 && now - prefetchAttemptAt < 15_000) ||
                connectivity.isActiveNetworkMetered ||
                !networkAvailable()
        )
            return
        cancelPrefetch()
        prefetchedFor = key
        prefetchAttemptAt = now
        prefetchAttempts++
        val ticket = preparedNext.begin(key)
        val http = newPrefetchTransport()
        prefetchHttp = http
        val scope = RequestScope()
        prefetchScope = scope
        prefetchJob =
            prefetchWorker.submit {
                var remote: RemoteVideo? = null
                try {
                    val prepared =
                        scope.run {
                            val opened =
                                // 播放地址复用当前 Repository 的设备身份和会话；仅视频传输独立取消。
                                openStream(
                                    http,
                                    repository,
                                    id,
                                    quality,
                                    CodecCapabilities::supports,
                                    initialBytes = 32 * 1024,
                                    metadataAhead = metadataAhead,
                                    speculative = true,
                                ) {
                                    it == null || CodecCapabilities.supports(it)
                                } ?: throw java.io.IOException("没有电视可播放的清晰度")
                            opened.video.also { remote = it }
                        }
                    if (preparedNext.complete(ticket, key, prepared)) {
                        android.util.Log.i(
                            "HongguoTV",
                            "Next episode header ready; handing media buffering to playback queue",
                        )
                        main.post { queuePreparedEpisode(key) }
                    }
                } catch (problem: Exception) {
                    remote?.close()
                    android.util.Log.i(
                        "HongguoTV",
                        "Next episode preparation unavailable: ${problem.javaClass.simpleName}",
                    )
                }
            }
    }

    private fun newPrefetchTransport(): okhttp3.OkHttpClient =
        repository.http
            .newBuilder()
            .dispatcher(okhttp3.Dispatcher())
            .connectionPool(okhttp3.ConnectionPool())
            .build()

    private fun episodeSource(remote: RemoteVideo, id: String) =
        ProgressiveMediaSource.Factory { VideoDataSource(remote) }
            .createMediaSource(
                MediaItem.Builder()
                    .setMediaId(id)
                    .setUri("hongguotv://episode/$id")
                    .setMimeType(MimeTypes.VIDEO_MP4)
                    .build()
            )

    private fun queuePreparedEpisode(key: String) {
        val p = player ?: return
        val data = detail ?: return
        val next = episodeIndex + 1
        if (
            !foreground ||
                screen != "player" ||
                playError ||
                !library.autoNext ||
                sleepStopped ||
                sleepTimer.episodesLeft == 1 ||
                queuedNext != null ||
                next !in data.episodes.indices ||
                key != "${data.episodes[next]}:${library.maxQuality}" ||
                p.mediaItemCount != 1 ||
                p.currentMediaItem?.mediaId != data.episodes[episodeIndex]
        )
            return
        val remote = preparedNext.take(key) ?: return
        queuedNext = remote
        queuedHttp = prefetchHttp
        prefetchHttp = null
        queuedEpisode = next
        p.addMediaSource(episodeSource(remote, data.episodes[next]))
        android.util.Log.i("HongguoTV", "Next episode added to playback queue target=${next+1}")
    }

    private fun clearQueuedEpisode() {
        val p = player
        if (queuedNext != null && p != null && p.currentMediaItemIndex + 1 < p.mediaItemCount)
            p.removeMediaItems(p.currentMediaItemIndex + 1, p.mediaItemCount)
        queuedNext?.close()
        queuedNext = null
        queuedEpisode = -1
        closeTransport(queuedHttp)
        queuedHttp = null
    }

    /** Queue transition only: no stop, clearMediaItems, setMediaSource or prepare. */
    private fun advanceQueuedEpisode(oldPosition: Long, completed: Boolean): Boolean {
        val p = player ?: return false
        val data = detail ?: return false
        val next = queuedNext ?: return false
        if (
            queuedEpisode != episodeIndex + 1 ||
                p.currentMediaItem?.mediaId != data.episodes.getOrNull(queuedEpisode)
        )
            return false
        val old =
            WatchProgress(
                data.series,
                data.episodes[episodeIndex],
                episodeIndex,
                oldPosition.coerceAtLeast(0),
                maxOf(episodeDuration, oldPosition),
                completed,
                System.currentTimeMillis(),
            )
        library.save(old)
        val previous = video
        val previousHttp = playbackHttp
        social.reset()
        video = next
        playbackHttp = queuedHttp
        episodeIndex = queuedEpisode
        group = episodeIndex / 20
        queuedNext = null
        queuedHttp = null
        queuedEpisode = -1
        if (p.currentMediaItemIndex > 0) p.removeMediaItems(0, p.currentMediaItemIndex)
        previous?.close()
        closeTransport(previousHttp)
        quality = next.info.quality
        pendingSeek = null
        startPosition = 0
        playbackReady = p.playbackState == Player.STATE_READY
        episodeDuration = p.duration.coerceAtLeast(0)
        readySince = android.os.SystemClock.elapsedRealtime()
        prefetchAttempts = 0
        prefetchAttemptAt = 0
        cancelRecovery()
        recovery.reset()
        playbackTitle.text = "${data.series.title}  ·  第 ${episodeIndex+1} 集"
        controls.removeAllViews()
        controls.visibility = View.GONE
        panel = false
        // 自动队列续播保持画面干净，手动切集仍沿用原有提示。
        hideAutoplayOverlay = completed
        if (completed) hidePlaybackOverlay()
        transportPlay = null
        saveProgress()
        syncMediaSession()
        android.util.Log.i("HongguoTV", "Playback queue transition episode=${episodeIndex+1}")
        if (completed && sleepTimer.episodeEnded()) stopForSleep()
        return true
    }

    private fun networkAvailable(): Boolean = NetworkCompat.available(connectivity)

    private fun currentPosition(): Long =
        pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition

    private fun cancelRecovery() {
        main.removeCallbacks(retryRunnable)
        main.removeCallbacks(recoveryCountdown)
        retryPending = false
        autoRecovery = false
    }

    private fun showRecoveryStatus(remainingMillis: Long) {
        playbackText.text =
            recoveryStatusText(
                failure = lastPlaybackFailure,
                autoplay = retryAutoplay,
                slow = recovery.slow,
                attempt = recovery.attempts + 1,
                total = if (allowSlowRecovery) recovery.total else RecoveryBudget.FAST_ATTEMPTS,
                remainingMillis = remainingMillis,
                positionText = formatTime(retryPosition),
            )
    }

    private fun scheduleRecovery() {
        if (!foreground || !playError || !autoRecovery || retryPending) return
        if (!recovery.remaining || (!allowSlowRecovery &&
            (recovery.slow || android.os.SystemClock.elapsedRealtime() >= playbackPreparationUntil))) {
            autoRecovery = false
            playbackText.text = (if (recovery.remaining)
                "$lastPlaybackFailure\n快速恢复暂未成功，可立即重试、换集或选择继续等待。"
                else recoveryExhaustedText(lastPlaybackFailure))
            recoveryAction?.apply {
                text = if (recovery.remaining) "继续等待恢复" else "恢复次数已用尽"
                isEnabled = recovery.remaining
            }
            return
        }
        if (!networkAvailable()) {
            playbackText.text =
                "$lastPlaybackFailure\n网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"
            return
        }
        val plannedDelay = recovery.delayMillis ?: return
        val delay = if (allowSlowRecovery) plannedDelay else minOf(plannedDelay,
            (playbackPreparationUntil - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
        recoveryScheduledAt = android.os.SystemClock.uptimeMillis()
        recoveryDelayMillis = delay
        showRecoveryStatus(delay)
        retryPending = true
        main.postDelayed(retryRunnable, delay)
        main.postDelayed(recoveryCountdown, 1000)
    }

    /** 按需展示的脱敏诊断详情（issue #38）：机型/系统/版本/失败阶段/恢复计数，便于截图定位。 */
    private fun playbackDiagnostics(): String =
        try {
            "\n" +
                diagnosticLine(
                    model = Build.MODEL,
                    androidVersion = Build.VERSION.RELEASE,
                    appVersion = BuildConfig.VERSION_NAME,
                    failure = lastPlaybackFailure,
                    recoveryAttempts = recovery.attempts,
                    total = recovery.total,
                    detail = lastPlaybackDetail,
                )
        } catch (_: Exception) {
            ""
        }

    /**
     * Detects growing audio/video drift (video frame processing offset stays signed) and re-seeks
     * to the current position once per cooldown window: a seek flushes the audio processors and
     * clock basis, which users confirmed restores sync; a pause does not.
     */
    private fun playbackDriftListener(p: ExoPlayer): AnalyticsListener {
        val guard = PlaybackSyncGuard()
        return object : AnalyticsListener {
            private var bufferingStartedAt: Long? = null

            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                if (player !== p) return
                val now = android.os.SystemClock.elapsedRealtime()
                if (state == Player.STATE_BUFFERING && bufferingStartedAt == null) {
                    bufferingStartedAt = now
                    val runtime = Runtime.getRuntime()
                    // No media URLs or account data: retain enough evidence for long-session stalls.
                    android.util.Log.i("HGPlayback", "Buffering start episode=${episodeIndex + 1} positionMs=${p.currentPosition} bufferedMs=${p.totalBufferedDuration} queue=${p.mediaItemCount} heapMb=${(runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)}")
                } else if (state != Player.STATE_BUFFERING) {
                    bufferingStartedAt?.let { started ->
                        android.util.Log.i("HGPlayback", "Buffering end state=$state elapsedMs=${now - started} bufferedMs=${p.totalBufferedDuration}")
                    }
                    bufferingStartedAt = null
                }
            }

            override fun onVideoFrameProcessingOffset(
                eventTime: AnalyticsListener.EventTime,
                totalProcessingOffsetUs: Long,
                frameCount: Int,
            ) {
                val window = guard.offer(totalProcessingOffsetUs, frameCount, p.isPlaying) ?: return
                android.util.Log.i("HongguoTV", "Video frame offset ${window.averageUs/1000}ms")
                if (window.resync)
                    main.post {
                        if (
                            player === p &&
                                playbackReady &&
                                p.isPlaying &&
                                pendingSeek == null &&
                                !playError
                        ) {
                            android.util.Log.w(
                                "HongguoTV",
                                "Resync after audio/video drift ${window.averageUs/1000}ms at ${formatTime(p.currentPosition)}",
                            )
                            p.seekTo(p.currentPosition)
                        }
                    }
            }
        }
    }

    private fun playedQualityLabel(opened: OpenedStream, maxQuality: Int): String {
        val rendition = opened.rendition ?: return opened.video.info.quality
        val label = if (rendition.quality > 0) "${rendition.quality}P" else "自动"
        if (rendition.quality > maxQuality) return label + "（兼容资源）"
        val ideal = opened.tiers.filter { it.quality in 1..maxQuality }.maxOfOrNull { it.quality }
        return if (ideal != null && rendition.quality < ideal) label + "（自动降清）" else label
    }

    /** 懒建播放器（正式路径与伴机路径共用）；已存在则原样返回。 */
    private fun ensurePlayer(): ExoPlayer =
        player
            ?: run {
                val load =
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15000, 30000, 1000, 2000)
                        .setTargetBufferBytes(12 * 1024 * 1024)
                        .build()
                val renderers =
                    playbackRenderers(this)
                ExoPlayer.Builder(this, renderers).setLoadControl(load).build().also {
                    player = it
                    playerView?.player = it
                    it.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        true,
                    )
                    it.setHandleAudioBecomingNoisy(true)
                    // 息屏播放时保持 CPU 与 Wi-Fi 唤醒，否则缓冲会在锁屏后停住。
                    it.setWakeMode(C.WAKE_MODE_NETWORK)
                    it.addAnalyticsListener(playbackDriftListener(it))
                }
            }

    /** 解析 bytevc2 档并让伴机起转码会话；失败抛 RelayException。 */
    private fun relayOpen(episodeId: String): RelayPlayback {
        val host = relayStore.host
        if (host.isBlank()) throw RelayException("伴机地址未配置，请在播放设置里填写")
        val client = RelayClient(relayHttp, host, relayStore.token)
        val health = client.health() ?: throw RelayException("伴机不可达（$host），请确认其电脑已启动伴机")
        if (!health.optBoolean("ffmpegOk", false))
            throw RelayException("伴机缺少 VVC 解码器，请在其电脑上安装 ffmpeg 7+")
        health.optJSONObject("session")?.takeIf { it.optString("state") == "RUNNING" }
            ?.let { throw RelayException("伴机正被其他播放占用") }
        val pick =
            repository.rawVideoModel(episodeId)
                .filter { it.codec in setOf("bytevc2", "bvc2") }
                .maxByOrNull { it.quality }
                ?: throw RelayException("本集没有 bytevc2 档位，伴机无用武之地")
        val keyB64 =
            pick.key?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        val (sessionId, hlsUrl) =
            client.createSession(
                pick.url,
                keyB64,
                if (pick.quality > 0) "${pick.quality}P" else "自动",
            ) ?: throw RelayException("伴机会话创建失败（可能正被占用）")
        val label =
            buildString {
                append(if (pick.quality > 0) "${pick.quality}P" else "自动")
                if ((pick.width ?: 0) > 0 && (pick.height ?: 0) > 0)
                    append(" · ${pick.width}×${pick.height}")
            }
        return RelayPlayback(sessionId, hlsUrl, label)
    }

    /** 把当前播放器切到伴机 HLS（复用既有进度/界面机制）；须在主线程调用。 */
    private fun startRelaySource(
        p: ExoPlayer,
        hlsUrl: String,
        id: String,
        position: Long,
        qualityLabel: String,
    ) {
        p.stop()
        p.clearMediaItems()
        playbackReady = false
        playError = false
        panel = false
        quality = "伴机转码 $qualityLabel"
        progressBar.visibility = View.VISIBLE
        playbackText.text = "正在由伴机转码…"
        controls.removeAllViews()
        controls.visibility = View.GONE
        val source =
            androidx.media3.exoplayer.hls.HlsMediaSource.Factory(
                androidx.media3.datasource.DefaultDataSource.Factory(this),
            )
                .createMediaSource(
                    MediaItem.Builder()
                        .setMediaId(id)
                        .setUri(hlsUrl)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build(),
                )
        p.setMediaSource(source)
        p.seekTo(position.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = foreground && requestedAutoplay && !pausedForLifecycle
    }

    /** 伴机兜底入口（playerFailure 面板前调用）：起会话成功则切 HLS，失败回落错误面板。 */
    private fun attemptRelayPlayback(position: Long, autoplay: Boolean) {
        val data = detail
        if (data == null) {
            playerFailure(RelayException("伴机不可用"))
            return
        }
        playbackText.text = "正在由伴机转码…"
        progressBar.visibility = View.VISIBLE
        val requestedId = data.episodes[episodeIndex]
        val ticket = generation
        work(
            { relayOpen(requestedId) },
            { result ->
                relaySessionId = result.sessionId
                // 正式路径监听器可能仍挂着（硬解失败路径），换上伴机专用监听避免双重处理。
                playerListener?.let { player?.removeListener(it) }
                playerListener = null
                val p = ensurePlayer()
                val relayListener =
                    object : Player.Listener {
                        private fun active() =
                            player === p &&
                                generation == ticket &&
                                p.currentMediaItem?.mediaId == requestedId

                        override fun onRenderedFirstFrame() {
                            if (active() && waitingForFirstFrame) playbackFirstFrame()
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (!active()) return
                            if (state == Player.STATE_READY) {
                                playbackReady = true
                                episodeDuration = p.duration.coerceAtLeast(0)
                                if (hud.visibility == View.VISIBLE || phoneOverlay?.showing == true || library.alwaysShowProgress) updatePlaybackText()
                            }
                            syncMediaSession()
                            if (state == Player.STATE_ENDED) {
                                saveProgress(true)
                                if (sleepTimer.episodeEnded()) stopForSleep()
                                else if (
                                    foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext &&
                                        !sleepStopped &&
                                        episodeIndex + 1 < data.episodes.size
                                ) {
                                    playEpisode(episodeIndex + 1, automaticNext = true)
                                } else if (foreground && requestedAutoplay && !pausedForLifecycle &&
                                    !sleepStopped && library.autoNext && episodeIndex == data.episodes.lastIndex) {
                                    finishSeason(data)
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (active()) playerFailure(error)
                        }
                    }
                playerListener = relayListener
                p.addListener(relayListener)
                startRelaySource(p, result.hlsUrl, requestedId, position, result.qualityLabel)
                startRelaySpeedWatch(ticket)
                android.util.Log.i(
                    "HongguoTV",
                    "Relay playback started episode=${episodeIndex + 1} position=$position quality=${result.qualityLabel}",
                )
            },
            { problem ->
                // 伴机失败：回落既有错误面板（relayAttempted 已置位，不会循环重试伴机）。
                playerFailure(problem)
            },
        )
    }

    /** 结束伴机会话（尽力而为，网络失败静默——伴机侧有空闲超时兜底）。 */
    private fun relayStopAsync() {
        val sessionId = relaySessionId ?: return
        relaySessionId = null
        val host = relayStore.host
        if (host.isBlank()) return
        Thread {
            runCatching { RelayClient(relayHttp, host, relayStore.token).stop(sessionId) }
        }.apply { isDaemon = true }.start()
    }

    /** 轻量性能监控：转码速度持续 <1x 实时提示一次（伴机 /health 的 speed 字段）。 */
    private fun startRelaySpeedWatch(ticket: Int) {
        stopRelaySpeedWatch()
        val poll =
            object : Runnable {
                override fun run() {
                    if (generation != ticket || relaySessionId == null) return
                    val host = relayStore.host
                    Thread {
                            val speed =
                                runCatching {
                                        RelayClient(relayHttp, host, relayStore.token)
                                            .health()
                                            ?.optJSONObject("session")
                                            ?.optDouble("speed")
                                            ?: Double.NaN
                                    }
                                    .getOrDefault(Double.NaN)
                            main.post {
                                if (generation != ticket || relaySessionId == null) return@post
                                if (!speed.isNaN() && speed < 1.0) {
                                    relaySlowCount++
                                    if (relaySlowCount >= 2 && !relaySlowHintShown) {
                                        relaySlowHintShown = true
                                        playbackText.text =
                                            "伴机性能不足（转码 ${"%.1f".format(speed)}x 实时），建议降低清晰度或稍后重试"
                                    }
                                } else relaySlowCount = 0
                                main.postDelayed(this, 10_000)
                            }
                        }.apply { isDaemon = true }.start()
                }
            }
        relaySpeedWatch = poll
        main.postDelayed(poll, 8_000)
    }

    private fun stopRelaySpeedWatch() {
        relaySpeedWatch?.let { main.removeCallbacks(it) }
        relaySpeedWatch = null
        relaySlowCount = 0
    }

    /** 伴机设置：开关 + 局域网地址 + 连接测试。 */
    private fun showRelaySettings(anchor: TextView) {
        if (relayDialog != null) return
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), 0)
            }
        val toggle =
            Switch(this).apply {
                isChecked = relayStore.enabled
                text = "启用伴机代解码（仅 bytevc2 兜底）"
            }
        val hostInput =
            EditText(this).apply {
                hint = "伴机地址，如 192.168.1.100:46521"
                setText(relayStore.host)
                setSingleLine(true)
            }
        val tokenInput =
            EditText(this).apply {
                hint = "伴机令牌（伴机电脑启动日志可查）"
                setText(relayStore.token)
                setSingleLine(true)
            }
        container.addView(toggle)
        container.addView(hostInput)
        container.addView(tokenInput)
        val searchStatus =
            TextView(this).apply {
                setTextColor(muted)
                textSize = 13f
                setPadding(0, dp(6), 0, 0)
            }
        val toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val searchButton = Button(this).apply { text = "搜索伴机" }
        searchButton.setOnClickListener {
            it.isEnabled = false
            searchStatus.text = "正在搜索局域网伴机（约 5 秒）…"
            val started =
                RelayDiscovery.discover(this@MainActivity, 5_000) { hosts ->
                    searchButton.isEnabled = true
                    when {
                        hosts.isEmpty() ->
                            searchStatus.text = "未发现伴机：请确认其电脑已启动伴机，或改用扫码/手动输入"
                        hosts.size == 1 -> {
                            hostInput.setText(hosts.first())
                            searchStatus.text = "已找到伴机 ${hosts.first()}"
                        }
                        else -> {
                            searchStatus.text = "发现 ${hosts.size} 台伴机，请选择"
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("发现多台伴机")
                                .setItems(hosts.toTypedArray()) { _, picked ->
                                    hostInput.setText(hosts[picked])
                                    searchStatus.text = "已选用 ${hosts[picked]}"
                                }
                                .show()
                        }
                    }
                }
            if (!started) {
                searchButton.isEnabled = true
                searchStatus.text = "此设备不支持自动发现，请改用扫码或手动输入"
            }
        }
        toolsRow.addView(searchButton, LinearLayout.LayoutParams(0, -2, 1f))
        container.addView(toolsRow, LinearLayout.LayoutParams(-1, -2))
        container.addView(searchStatus)
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("伴机代解码")
                .setView(container)
                .setPositiveButton("保存", null)
                .setNeutralButton("测试连接", null)
                .setNegativeButton("取消", null)
                .create()
        TouchUi.install(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = RelayClient.normalizeHost(hostInput.text.toString())
                if (toggle.isChecked && (normalized == null || tokenInput.text.toString().isBlank())) {
                    val reason = if (normalized == null) "地址格式不对（示例 192.168.1.100:46521）" else "启用伴机需填写令牌（见伴机电脑启动日志）"
                    Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                relayStore.enabled = toggle.isChecked
                if (normalized != null) relayStore.host = normalized
                relayStore.token = tokenInput.text.toString().trim()
                relayDialog?.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val normalized = RelayClient.normalizeHost(hostInput.text.toString())
                if (normalized == null) {
                    Toast.makeText(this, "地址格式不对（示例 192.168.1.100:46521）", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Toast.makeText(this, "正在连接 $normalized …", Toast.LENGTH_SHORT).show()
                val token = tokenInput.text.toString().trim()
                Thread {
                    val health = RelayClient(relayHttp, normalized, token).health()
                    val message =
                        when {
                            health == null -> "伴机不可达，请确认其电脑已启动伴机"
                            !health.optBoolean("ffmpegOk", false) -> "已连接，但伴机缺少 VVC 解码器（需 ffmpeg 7+）"
                            else -> "连接成功：果映伴机 ${health.optString("version")}"
                        }
                    runOnUiThread {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }.apply { isDaemon = true }.start()
            }
        }
        dialog.setOnDismissListener {
            relayDialog = null
            if (anchor.isAttachedToWindow) anchor.requestFocus()
        }
        relayDialog = dialog
        dialog.show()
    }

    private fun playerFailure(problem: Throwable) {
        // 已稳定出画面后的新故障开启新的快速恢复窗口，不能沿用几分钟前的启动截止时间。
        if (!waitingForFirstFrame && !playError) {
            playbackPreparationUntil = android.os.SystemClock.elapsedRealtime() + 20_000
            allowSlowRecovery = false
            recovery.reset()
        }
        lastPlaybackFailure = playbackFailureSummary(problem)
        lastPlaybackDetail = diagnosticDetail(problem)
        retryPosition = currentPosition().coerceAtLeast(0)
        retryAutoplay = requestedAutoplay && !sleepStopped
        saveProgress()
        cancelRecovery()
        cancelPrefetch()
        // 确定性失败（本集无任何可解码档，bytevc2 锁定集）：重试与降档都只会得到同一结果，
        // 不再进入自动恢复预算，不给降档按钮；手动重试保留（供给可能随服务端转码翻转）。
        val noPlayableQuality =
            generateSequence(problem) { it.cause }.take(8).any { it is NoPlayableQualityException }
        // The device claimed support it doesn't have (MediaCodecList over-reporting): learn the
        // runtime verdict and retry one tier lower automatically instead of surfacing the panel.
        if (
            !noPlayableQuality &&
                problem is PlaybackException &&
                problem.errorCode in
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException
                            .ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        ) {
            val track = currentTrack
            val rendition = currentRendition
            when {
                track != null ->
                    CodecCapabilities.learnFailure(track.fourcc, track.width, track.height)
                rendition != null ->
                    CodecCapabilities.learnFailure(
                        rendition.codec,
                        rendition.width,
                        rendition.height,
                    )
            }
            if (shouldAutoDowngrade(autoDowngrades, 2, currentTiers, attemptedQuality)) {
                autoDowngrades++
                android.util.Log.w(
                    "HongguoTV",
                    "Auto downgrading after decoder failure (attempt $autoDowngrades, was ${attemptedQuality}P)",
                )
                playEpisode(episodeIndex, retryPosition, retryAutoplay, autoDowngrade = true)
                return
            }
        }
        // 伴机兜底：bytevc2 锁定集（阶梯耗尽）或硬解失败时，面板展示前先试一次局域网伴机
        // 转码；成功则把当前播放器切到伴机 HLS，失败原样落入下方错误面板，行为向后兼容。
        val relayEligible =
            noPlayableQuality ||
                (problem is PlaybackException && problem.errorCode in 3000..4999)
        if (!relayAttempted && relayStore.enabled && relayEligible) {
            relayAttempted = true
            attemptRelayPlayback(retryPosition, retryAutoplay)
            return
        }
        playError = true
        player?.pause()
        progressBar.visibility = View.GONE
        hud.visibility = View.VISIBLE
        controls.removeAllViews()
        controls.visibility = View.VISIBLE
        panel = true
        val decoding = problem is PlaybackException && problem.errorCode in 3000..4999
        val retryable =
            if (problem is PlaybackException)
                problem.errorCode in 2000..2999 ||
                    problem.errorCode == PlaybackException.ERROR_CODE_TIMEOUT
            else problem is java.io.IOException && !noPlayableQuality
        // 按钮直接指向阶梯里真实存在的更低档：没有就明说，不再让用户循环点一个必然重放
        // 同一流（如本集仅剩电视解不了的 1080P）的按钮。
        val lowerTier =
            if (noPlayableQuality) null else nextLowerQuality(attemptedQuality, currentTiers)
        playbackText.text =
            when {
                noPlayableQuality -> lastPlaybackFailure
                decoding && lowerTier != null -> "电视无法解码当前视频，可尝试更低清晰度。"
                decoding -> "电视无法解码当前视频，本集没有更低清晰度可用。"
                else -> "$lastPlaybackFailure · 可手动重试"
            }
        val actions = row()
        controls.addView(actions)
        val retryButton = addButton(actions, "立即重试") { playEpisode(episodeIndex, retryPosition, retryAutoplay) }
        if (recovery.attempts == 0) retryButton.requestFocus()
        lowerTier?.let { lower ->
            addButton(actions, "尝试 ${lower}P") {
                library.maxQuality = lower
                playEpisode(episodeIndex, retryPosition, retryAutoplay)
            }
        }
        addButton(actions, "返回选集") { returnToDetail(true) }
        addButton(actions, "查看错误详情") { tvTools.info("播放诊断", playbackDiagnostics().trim(), currentFocus) }
        if (retryable && !sleepStopped) {
            recoveryAction = addButton(controls, "停止自动重试") {
                if (autoRecovery) {
                    cancelRecovery()
                    playbackText.text = "$lastPlaybackFailure\n自动恢复已停止，观看位置已保留。"
                    recoveryAction?.text = "继续等待恢复"
                } else {
                    allowSlowRecovery = true
                    autoRecovery = true
                    recoveryAction?.text = "停止自动重试"
                    scheduleRecovery()
                }
            }
            autoRecovery = true
            scheduleRecovery()
        }
        android.util.Log.w(
            "HongguoTV",
            "Playback failure: ${problem.javaClass.simpleName}; reason=$lastPlaybackFailure" +
                (if (problem is PlaybackException) " code=${problem.errorCodeName}" else ""),
        )
    }

    private fun hidePlaybackOverlay() {
        main.removeCallbacks(hideHud)
        phoneOverlay?.visibility = View.GONE
        if (waitingForFirstFrame && !playError && !hideAutoplayOverlay) {
            hud.visibility = View.VISIBLE
            return
        }
        panel = false
        controls.visibility = View.GONE
        controls.clearFocus()
        hud.visibility = View.GONE
        updatePersistentProgress()
        updateClock()
    }

    // 只有真正输出首帧才清除恢复原因，READY 不代表屏幕已经出画面。
    private fun playbackFirstFrame() {
        waitingForFirstFrame = false
        recoveryDisplayActive = false
        allowSlowRecovery = false
        controls.removeAllViews()
        controls.visibility = View.GONE
        panel = false
        if (autoRecoveredPlay) {
            autoRecoveredPlay = false
            Toast.makeText(this, "已恢复播放", Toast.LENGTH_SHORT).show()
        }
        if (hideAutoplayOverlay) {
            hideAutoplayOverlay = false
            hidePlaybackOverlay()
        } else showHud()
    }

    private fun stopPreparingPlayback() {
        invalidatePage()
        cancelRecovery()
        player?.stop()
        video?.close()
        video = null
        waitingForFirstFrame = false
        recoveryDisplayActive = false
        playError = true
        progressBar.visibility = View.GONE
        playbackText.text = "$lastPlaybackFailure\n恢复已取消，观看位置已保留。"
        controls.removeAllViews()
        addButton(controls, "立即重试") { playEpisode(episodeIndex, retryPosition, retryAutoplay) }
        addButton(controls, "返回选集") { returnToDetail(true) }
    }

    private fun updateClock() {
        playbackClock?.visibility = if (screen == "player" && library.clockMode != 0 &&
            (library.clockMode == 2 || hud.visibility == View.VISIBLE)) View.VISIBLE else View.GONE
    }

    private fun showHud() {
        if (screen != "player") return
        hideAutoplayOverlay = false
        hud.visibility = View.VISIBLE
        phoneOverlay?.let {
            it.visibility = View.VISIBLE
            // 手机控制层负责正常播放时的操作；原底部信息区只在加载和出错时显示状态与恢复按钮。
            if (!waitingForFirstFrame && !playError && controls.visibility != View.VISIBLE) hud.visibility = View.GONE
        }
        updateClock()
        updatePlaybackText()
        main.removeCallbacks(hideHud)
        if (player?.isPlaying == true) main.postDelayed(hideHud, if (panel) 8000 else 4500)
    }

    private fun updatePlaybackText() {
        updatePersistentProgress()
        syncPhoneOverlay()
        transportPlay?.text = if (player?.playWhenReady == true) "暂停" else "播放"
        sleepButton?.text = "定时 ${sleepTimer.label()}"
        if (screen != "player" || playError) return
        if (waitingForFirstFrame && !sleepStopped) {
            playbackText.text = if (!requestedAutoplay && playbackReady)
                (if (recoveryDisplayActive) "$lastPlaybackFailure\n" else "") + "已准备好 · 保持暂停，按播放开始"
                else preparingPlaybackText(recoveryDisplayActive, lastPlaybackFailure, video != null)
            return
        }
        if (sleepStopped) {
            playbackText.text = "定时停止已生效 · 观看位置已保存 · 按播放可继续"
            return
        }
        if (!playbackReady) {
            playbackText.text =
                (if (!requestedAutoplay) "准备后保持暂停 · " else "") +
                    if (video == null) "正在获取播放地址…" else "正在缓冲…"
            return
        }
        val p = player ?: return
        val duration = p.duration.coerceAtLeast(0)
        val position = pendingSeek ?: p.currentPosition
        val state =
            when {
                p.playbackState == Player.STATE_BUFFERING -> "缓冲中"
                p.playbackState == Player.STATE_ENDED -> "本集已结束"
                !p.playWhenReady -> "已暂停"
                else -> "正在播放"
            }
        playbackText.text =
            "$state  ·  ${formatTime(position)} / ${formatTime(duration)}  ·  $quality  ·  ${PlaybackSpeed.label(p.playbackParameters.speed)}" +
                (if (sleepTimer.active) "  ·  定时 ${sleepTimer.label()}" else "")
        progressBar.progress =
            if (duration > 0) (position * 1000 / duration).toInt().coerceIn(0, 1000) else 0
    }

    private fun syncPhoneOverlay() {
        val overlay = phoneOverlay ?: return
        if (screen != "player") return
        val p = player
        val count = detail?.episodes?.size ?: 0
        overlay.update(
            "${detail?.series?.title.orEmpty()}  ·  第 ${episodeIndex + 1} 集",
            pendingSeek ?: p?.currentPosition ?: 0,
            p?.duration?.takeIf { it > 0 } ?: 0,
            p?.playWhenReady == true,
            PlaybackSpeed.label(p?.playbackParameters?.speed ?: library.playbackSpeed),
            quality.ifEmpty { "清晰度" },
            detail?.series?.id?.let(library::favorite) == true,
            episodeIndex > 0,
            episodeIndex < count - 1,
        )
    }

    private fun requestPlayback(play: Boolean) {
        requestedAutoplay = play
        pausedForLifecycle = !play
        if (play) sleepStopped = false
        if (!play) {
            player?.pause()
            retryAutoplay = false
            saveProgress()
        } else if (playError) {
            playEpisode(episodeIndex, retryPosition, true)
            return
        } else if (episodePanel == null) {
            if (player?.playbackState == Player.STATE_ENDED) player?.seekTo(0)
            player?.play()
        }
        if (!play) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showHud()
        updatePlaybackText()
        syncMediaSession()
        if (panel && episodePanel == null) showPanel()
    }

    private fun togglePlayback() =
        requestPlayback(
            !(if (playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay)
        )

    private fun seekTo(position: Long) {
        val p = player ?: return
        if (!playbackReady || p.duration <= 0) return
        main.removeCallbacks(seekRunnable)
        pendingSeek = null
        p.seekTo(position.coerceIn(0, (p.duration - 500).coerceAtLeast(0)))
        updatePlaybackText()
        syncMediaSession()
        showHud()
    }

    private fun skipEpisode(direction: Int) {
        if (!foreground || screen != "player") return
        val count = detail?.episodes?.size ?: return
        val target = episodeIndex + direction
        if (target in 0 until count) playEpisode(target)
    }

    private fun syncMediaSession() {
        if (!foreground || screen != "player") {
            mediaSession.deactivate()
            return
        }
        val data = detail ?: return
        val p = player
        val state =
            when {
                playError -> android.media.session.PlaybackState.STATE_ERROR
                sleepStopped || !requestedAutoplay || (p != null && !p.playWhenReady) ->
                    android.media.session.PlaybackState.STATE_PAUSED
                p == null || !playbackReady || p.playbackState == Player.STATE_BUFFERING ->
                    android.media.session.PlaybackState.STATE_BUFFERING
                p.playbackState == Player.STATE_ENDED ->
                    android.media.session.PlaybackState.STATE_STOPPED
                p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
                else -> android.media.session.PlaybackState.STATE_PAUSED
            }
        mediaSession.update(
            data.series.title,
            episodeIndex,
            p?.duration?.coerceAtLeast(0) ?: 0,
            currentPosition(),
            library.playbackSpeed,
            state,
            episodeIndex > 0,
            episodeIndex < data.episodes.lastIndex,
        )
    }

    private fun stopForSleep() {
        cancelNextSeason()
        sleepStopped = true
        requestedAutoplay = false
        retryAutoplay = false
        pausedForLifecycle = true
        cancelRecovery()
        cancelPrefetch()
        player?.pause()
        saveProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackText.text = "定时停止已生效 · 观看位置已保存 · 按播放可继续"
        showHud()
        if (!playError && episodePanel == null) showPanel()
        syncMediaSession()
        Toast.makeText(this, "定时停止已生效", Toast.LENGTH_LONG).show()
    }

    private fun showSleepPicker(anchor: TextView) {
        val labels =
            listOf("关闭定时停止", "15 分钟后", "30 分钟后", "60 分钟后", "90 分钟后", "本集播完", "再播完 3 集", "再播完 5 集")
        tvTools.choose(
            "定时停止 · ${sleepTimer.label()}",
            labels,
            anchor,
            { choice ->
                when (choice) {
                    0 -> sleepTimer.cancel()
                    in 1..4 -> sleepTimer.afterMinutes(listOf(15, 30, 60, 90)[choice - 1])
                    else -> sleepTimer.afterEpisodes(listOf(1, 3, 5)[choice - 5])
                }
                if (sleepTimer.episodesLeft == 1) cancelPrefetch()
                if (anchor.tag != "playback-settings") anchor.text = "定时 ${sleepTimer.label()}"
                updatePlaybackText()
                showHud()
            },
        )
    }

    private fun applyVideoRotation() {
        val view=playerView ?: return
        if(root.width<=0 || root.height<=0) return
        val canvas=VideoRotation.canvas(root.width,root.height,library.videoRotation)
        val params=view.layoutParams as FrameLayout.LayoutParams
        if(params.width!=canvas.width || params.height!=canvas.height) {
            params.width=canvas.width; params.height=canvas.height; params.gravity=Gravity.CENTER; view.layoutParams=params
        }
        view.rotation=library.videoRotation.toFloat()
    }
    private fun showRotationPicker(anchor: TextView) {
        tvTools.choose("画面旋转 · 仅旋转视频",VideoRotation.options.map { "${it}°" },anchor,{ index ->
            val previous=library.videoRotation; val next=VideoRotation.options[index]
            if(previous==next) return@choose
            val rebuild=screen=="player" && playerView!=null && VideoRotation.requiresSurfaceRebuild(previous,next)
            val selectedEpisode=episodeIndex; val position=currentPosition()
            val autoplay=if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay
            val ticket=generation
            library.videoRotation=next
            if(rebuild) {
                // Let the picker finish dismissal before replacing its anchor and the video view.
                // Rebuild only for a SurfaceView/TextureView transition, never on a layout callback.
                main.post {
                    if(screen=="player" && generation==ticket)
                        playEpisode(selectedEpisode,position,autoplay,recreateView=true)
                }
            } else applyVideoRotation()
            if(anchor.tag!="playback-settings") anchor.text="画面旋转  ·  ${library.videoRotation}°"
        })
    }
    private fun updatePersistentProgress() {
        if (persistentProgress == null || !library.isLoaded) return
        val visible=PlaybackProgressDisplay.visible(library.alwaysShowProgress,screen=="player",hud.visibility==View.VISIBLE,playError)
        persistentProgress?.visibility=if(visible) View.VISIBLE else View.GONE
        if(!visible) return
        val duration=player?.duration?.coerceAtLeast(0) ?: 0L
        val position=currentPosition().coerceAtLeast(0)
        persistentTime?.text=if(playbackReady) "${formatTime(position)} / ${if(duration>0) formatTime(duration) else "--:--"}" else "正在准备…"
        persistentBar?.progress=PlaybackProgressDisplay.value(position,duration)
    }
    private fun frameResizeMode() =
        when (library.frameMode) {
            VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        }

    private fun showFramePicker(anchor: TextView, inPlayer: Boolean) {
        tvTools.choose(
            "全局画面模式",
            listOf("完整画面 · 保留比例与全部内容", "等比铺满 · 会裁掉部分画面与字幕", "拉伸铺满 · 画面比例会改变"),
            anchor,
            { index ->
                library.frameMode = VideoFrameMode.entries[index]
                playerView?.resizeMode = frameResizeMode()
                if (anchor.tag != "playback-settings")
                    anchor.text = (if (inPlayer) "画面 " else "画面模式  ·  ") + library.frameMode.label
            },
            selected = library.frameMode.ordinal,
        )
    }

    private fun seek(direction: Int, repeat: Int) {
        val p = player ?: return
        if (!playbackReady) return
        val duration = p.duration
        if (duration <= 0) return
        val step = if (repeat > 4) 30000 else 10000
        pendingSeek =
            ((pendingSeek ?: p.currentPosition) + direction * step).coerceIn(
                0,
                (duration - 500).coerceAtLeast(0),
            )
        main.removeCallbacks(seekRunnable)
        main.postDelayed(seekRunnable, 250)
        updatePlaybackText()
        showHud()
    }

    private fun showPanel() {
        seasonBanner?.visibility = View.GONE
        if (playError) return
        panel = true
        showHud()
        controls.visibility = View.VISIBLE
        controls.removeAllViews()
        if (nextSeasonWork != null) addButton(controls, "取消本次跨季连播") {
            seasonSuppressed = true
            cancelNextSeason()
            showPanel()
        }

        val transport = row()
        controls.addView(transport)
        val play =
            addButton(transport, if (player?.playWhenReady == true) "暂停" else "播放") {
                    togglePlayback()
                }
                .also { transportPlay = it }
        addButton(transport, "上一集") { if (episodeIndex > 0) playEpisode(episodeIndex - 1) }
            .apply {
                isEnabled = episodeIndex > 0
                isFocusable = episodeIndex > 0
                alpha = if (episodeIndex > 0) 1f else .4f
            }
        addButton(transport, "下一集") {
                if (episodeIndex < (detail?.episodes?.lastIndex ?: 0)) playEpisode(episodeIndex + 1)
            }
            .apply {
                val enabled = episodeIndex < (detail?.episodes?.lastIndex ?: 0)
                isEnabled = enabled
                isFocusable = enabled
                alpha = if (enabled) 1f else .4f
            }
        val options = row().apply { setPadding(0, dp(6), 0, 0) }
        controls.addView(options)
        lateinit var episodes: TextView
        episodes = addButton(options, "选集") { showEpisodePanel(episodes) }
        lateinit var commentsButton: TextView
        commentsButton = addButton(options, "评论") { social.showComments(commentsButton) }
        lateinit var danmakuButton: TextView
        danmakuButton = addButton(options, "弹幕设置") { social.settings(danmakuButton) }
        lateinit var more: TextView
        more =
            addButton(options, "播放设置") { showPlaybackSettings(more) }
                .apply { tag = "playback-settings" }
        play.requestFocus()
        showHud()
    }

    private fun showPlaybackSettings(more: TextView) {
        tvTools.choose(
            "播放设置",
            listOf(
                "倍速 ${PlaybackSpeed.label(library.playbackSpeed)}",
                "清晰度 ${library.maxQuality}P",
                "画面 ${library.frameMode.label}",
                "定时 ${sleepTimer.label()}",
                "伴机代解码 ${if (relayStore.enabled) "开" else "关"}",
                "从头播放",
                "重置设备能力记录",
                "画面旋转 ${library.videoRotation}°",
                "常驻播放进度 ${if (library.alwaysShowProgress) "开启" else "关闭"}",
                "息屏播放 ${if (screenOffEnabled) "开启" else "关闭"}",
            ),
            more,
            { choice ->
                main.post {
                    if (screen == "player" && more.isAttachedToWindow)
                        when (choice) {
                            0 -> showSpeedPicker(more)
                            1 -> showQualityPicker(more, true)
                            2 -> showFramePicker(more, true)
                            3 -> showSleepPicker(more)
                            4 -> showRelaySettings(more)
                            5 -> {
                                seekTo(0)
                                hidePanel()
                            }
                            7 -> showRotationPicker(more)
                            8 -> { library.alwaysShowProgress = !library.alwaysShowProgress; updatePersistentProgress() }
                            9 -> {
                                screenOffEnabled = !screenOffEnabled
                                Toast.makeText(this, if (screenOffEnabled) "息屏后继续播放" else "息屏后暂停播放", Toast.LENGTH_SHORT).show()
                            }
                            6 -> {
                                val count = CodecCapabilities.learnedCount()
                                CodecCapabilities.resetLearned()
                                android.util.Log.i(
                                    "HongguoTV",
                                    "Decoder capability lessons reset ($count entries)",
                                )
                                playbackText.text =
                                    if (count > 0) "已清除 $count 条设备能力记录，重新按解码器实际能力选流"
                                    else "暂无设备能力记录"
                            }
                        }
                }
            },
        )
    }

    private fun showSpeedPicker(anchor: TextView, inPlayer: Boolean = true) {
        val speeds = PlaybackSpeed.options
        tvTools.choose("全局默认倍速", speeds.map(PlaybackSpeed::label), anchor, { index ->
            library.playbackSpeed = speeds[index]
            if (inPlayer) player?.setPlaybackSpeed(library.playbackSpeed)
            if (anchor.tag != "playback-settings")
                anchor.text = (if (inPlayer) "倍速 " else "默认倍速  ·  ") + PlaybackSpeed.label(library.playbackSpeed)
            if (inPlayer) { updatePlaybackText(); showHud() }
        }, selected = speeds.indexOf(library.playbackSpeed))
    }

    private fun showQualityPicker(anchor: TextView, inPlayer: Boolean) {
        val options = PlaybackQuality.options
        tvTools.choose("全局默认清晰度", options.map { "${it}P" }, anchor, { index ->
            val target = options[index]
            val changed = library.maxQuality != target
            library.maxQuality = target
            if (anchor.tag != "playback-settings")
                anchor.text = (if (inPlayer) "清晰度 " else "默认清晰度  ·  ") + "${target}P"
            if (inPlayer && changed)
                playEpisode(episodeIndex, currentPosition(), player?.playWhenReady ?: requestedAutoplay)
        }, selected = options.indexOf(library.maxQuality))
    }

    private fun showEpisodePanel(anchor: View) {
        val data = detail ?: return
        if (episodePanel != null) return
        val ticket = generation
        val overlayWasVisible = hud.visibility == View.VISIBLE
        requestedAutoplay = player?.playWhenReady ?: requestedAutoplay
        player?.pause()
        saveProgress()
        var resumeOnClose = true
        val next =
            EpisodePanel(
                this,
                data.episodes.size,
                episodeIndex,
                { selected ->
                    if (selected == episodeIndex) episodePanel?.dismiss()
                    else {
                        resumeOnClose = false
                        episodePanel?.dismiss()
                        playEpisode(selected)
                    }
                },
                {
                    episodePanel = null
                    if (resumeOnClose && foreground && generation == ticket && screen == "player") {
                        if (requestedAutoplay && !sleepStopped && !pausedForLifecycle)
                            player?.play()
                        if (anchor.isAttachedToWindow) anchor.requestFocus()
                        if (overlayWasVisible) showHud() else hidePlaybackOverlay()
                    }
                },
                lastWatched =
                    library.progress(data.series.id)?.let {
                        data.episodes.indexOf(it.episodeId).takeIf { n -> n >= 0 }
                    },
                lastCompleted = library.progress(data.series.id)?.completed == true,
            )
        episodePanel = next
        next.show()
    }

    private fun hidePanel() {
        if (playError) return
        panel = false
        controls.visibility = View.GONE
        controls.clearFocus()
        showHud()
    }

    private fun formatTime(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0) / 1000)
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    // 正式播放器与首页均写入同一本机片库，并复用账户上传节流。
    private fun saveProgress(completed: Boolean = false) {
        val p = player ?: return
        val data = detail ?: return
        if (!playbackReady || episodeIndex !in data.episodes.indices) return
        val progress =
            WatchProgress(
                data.series,
                data.episodes[episodeIndex],
                episodeIndex,
                p.currentPosition.coerceAtLeast(0),
                p.duration.coerceAtLeast(0),
                completed || p.playbackState == Player.STATE_ENDED,
                System.currentTimeMillis(),
            )
        library.save(progress)
    }

    private fun cancelNextSeason() {
        seasonGeneration++
        seasonPending = false
        seasonBanner?.let { (it.parent as? ViewGroup)?.removeView(it) }
        seasonBanner = null
        nextSeasonWork = null
    }

    private fun maybePrepareSeason() {
        val current = detail ?: return
        val p = player ?: return
        if (episodeIndex == current.episodes.lastIndex && p.duration > 0 &&
            p.currentPosition >= p.duration / 2 && p.isPlaying && !playError) offerNextSeason(current)
    }

    private fun updateSeasonBanner() {
        seasonBanner?.visibility = if (screen == "player" && !panel &&
            episodePanel == null && !tvTools.showing && !playError && !sleepStopped) View.VISIBLE else View.GONE
    }

    private fun offerNextSeason(current: Detail) {
        if (!library.seasonAutoplay || !library.autoNext || seasonSuppressed || seasonPending || !foreground) return
        seasonPending = true
        val token = ++seasonGeneration
        work({ RelatedWorks.nextSeason(current.series, repository.related(current.series.id)) }, { next ->
            if (token != seasonGeneration || !foreground || detail?.series?.id != current.series.id) return@work
            nextSeasonWork = next
            if (next != null) {
                val banner = text("本季结束后播放《${next.series.title}》 · 取消请按 ↑ 操作菜单", 13f).apply {
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    background = TvStyle.shape(this@MainActivity, Color.argb(175, 12, 18, 28))
                }
                seasonBanner = banner
                root.addView(banner, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                    setMargins(dp(24), 0, dp(24), dp(20))
                })
                updateSeasonBanner()
            }
        }, { if (token == seasonGeneration) nextSeasonWork = null })
    }

    private fun finishSeason(current: Detail) {
        val next = nextSeasonWork ?: return
        if (!foreground || seasonSuppressed || !library.seasonAutoplay || !library.autoNext ||
            sleepStopped || pausedForLifecycle || detail?.series?.id != current.series.id) return
        val token = seasonGeneration
        playbackText.text = "正在加载下一季…"
        work({ repository.detail(next.series.id) }, { result ->
            if (token == seasonGeneration && foreground && !seasonSuppressed) {
                detail = result
                episodeIndex = 0
                group = 0
                playEpisode(0, 0, true)
            }
        }, { if (token == seasonGeneration) {
            cancelNextSeason()
            playbackText.text = "下一季暂时无法加载，请稍后手动选择"
            showPanel()
        } })
    }

    private fun releasePlayer(keepPrepared: Boolean = false) {
        cancelNextSeason()
        social.reset()
        cancelRecovery()
        cancelPrefetchState(keepPrepared)
        relayStopAsync()
        mediaSession.deactivate()
        tvTools.close()
        val wasForeground = foreground
        foreground = false
        val oldPanel = episodePanel
        episodePanel = null
        oldPanel?.dismiss()
        foreground = wasForeground
        main.removeCallbacks(hideHud)
        main.removeCallbacks(seekRunnable)
        pendingSeek = null
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        video?.close()
        video = null
        playerView?.player = null
        playerView = null
        phoneOverlay = null
        persistentProgress = null
        persistentTime = null
        persistentBar = null
        player?.release()
        player = null
        playbackReady = false
        transportPlay = null
        sleepButton = null
        closeTransport(playbackHttp)
        playbackHttp = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun returnToDetail(forceDetail: Boolean = false) {
        sleepTimer.cancel()
        sleepStopped = false
        saveProgress()
        library.flush()
        invalidatePage()
        releasePlayer()
        if (directPlayback && !forceDetail) showCatalog()
        else {
            directPlayback = false
            showDetail()
        }
    }

    private fun showCatalog() = showPhoneCatalog()

    private fun showDetail() = showPhoneDetail()

    private fun goBack() {
        if (!library.isLoaded) {
            finish()
            return
        }
        when (screen) {
            "catalog" ->
                if (phoneShell?.back() != true) tvTools.confirm("退出红果", "确定退出？", currentFocus) { finish() }
            "player" -> returnToDetail()
            else -> showCatalog()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val media =
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_STOP,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_REWIND -> true
                else -> false
            }
        if (screen != "player" || !media) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                KeyEvent.KEYCODE_MEDIA_NEXT -> skipEpisode(1)
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skipEpisode(-1)
                KeyEvent.KEYCODE_MEDIA_STOP -> returnToDetail()
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1, 0)
                KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1, 0)
                else -> togglePlayback()
            }
        return true
    }

    /** 播放页方向由用户在控制层切换并记住；其余页面固定竖屏。 */
    private var portraitPlayer: Boolean
        get() = getSharedPreferences("phone-ui", MODE_PRIVATE).getBoolean("portraitPlayer", false)
        set(value) = getSharedPreferences("phone-ui", MODE_PRIVATE).edit().putBoolean("portraitPlayer", value).apply()

    private var screenOffEnabled: Boolean
        get() = getSharedPreferences("phone-ui", MODE_PRIVATE).getBoolean("screenOffPlayback", true)
        set(value) = getSharedPreferences("phone-ui", MODE_PRIVATE).edit().putBoolean("screenOffPlayback", value).apply()

    /** 息屏后继续播放中：生命周期回调跳过暂停与释放，由前台服务保活。 */
    private var screenOffPlayback = false

    private fun playingLabel() = "${detail?.series?.title.orEmpty()} · 第 ${episodeIndex + 1} 集"

    private fun phoneOrientation(player: Boolean) {
        val want =
            if (player && !portraitPlayer) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (requestedOrientation != want) requestedOrientation = want
    }

    private fun showPhoneCatalog() {
        initialCatalogPending = false
        restoreStoppedPage = false
        pendingDetail = null
        invalidatePage()
        screen = "catalog"
        phoneOrientation(player = false)
        root.removeAllViews()
        root.setBackgroundColor(TvStyle.background)
        val created = phoneShell == null
        val catalog =
            phoneShell
                ?: PhoneCatalog(
                        this,
                        repository,
                        library,
                        ::loadCover,
                        open = { openDetail(it) },
                        resume = { openDetail(it, resume = true) },
                        checkUpdates = { updater.show(it) },
                        confirm = { title, message, action ->
                            tvTools.confirm(title, message, currentFocus) { action() }
                        },
                    )
                    .also { phoneShell = it }
        (catalog.parent as? ViewGroup)?.removeView(catalog)
        root.addView(catalog, FrameLayout.LayoutParams(-1, -1))
        if (!created) catalog.reattach()
    }

    private fun showPhoneDetail() {
        invalidatePage()
        screen = "detail"
        phoneOrientation(player = false)
        val data = detail ?: return showCatalog()
        val progress = detailProgress(data)
        val watched = library.watched(data.series.id)
        val target =
            ResumePlayback.target(
                data.episodes,
                progress?.episodeId,
                progress?.position ?: 0,
                progress?.completed == true,
            )
        val label =
            if (watched) "重新观看" else if (progress != null) "继续第 ${target.index + 1} 集" else "开始观看"
        root.removeAllViews()
        root.setBackgroundColor(TvStyle.background)
        val view =
            PhoneDetail(
                this,
                data,
                if (progress != null) target.index else episodeIndex,
                label,
                library.favorite(data.series.id),
                ::loadCover,
                back = ::goBack,
                play = {
                    val current = detailProgress(data)
                    val next =
                        ResumePlayback.target(
                            data.episodes,
                            current?.episodeId,
                            current?.position ?: 0,
                            current?.completed == true,
                        )
                    playEpisode(if (watched) 0 else next.index, if (watched) 0 else next.position)
                },
                playEpisode = { playEpisode(it) },
                toggleFavorite = {
                    library.toggle(data.series)
                    val saved = library.favorite(data.series.id)
                    if (saved)
                        library.observeFavorite(
                            data.series.id,
                            data.episodes.size,
                            System.currentTimeMillis(),
                            acknowledge = true,
                        )
                    saved
                },
                openSeason = { work ->
                    if (work.upcoming) Toast.makeText(this, "「${work.season.ifBlank { work.series.title }}」待上线，暂不能播放", Toast.LENGTH_SHORT).show()
                    else openDetail(work.series)
                },
            )
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        // 系列各季来自相关作品接口：先显示本机缓存，后台刷新后有变化再替换；失败时保留缓存或不显示。
        val cached = seasonCache.get(data.series.id)
        cached?.let { view.showSeasons(it) }
        val ticket = generation
        io.execute {
            val rows =
                runCatching {
                        val related = repository.related(data.series.id)
                        val self = RelatedWork(data.series, "", false)
                        RelatedWorks.ordered(data.series, related + self)
                            .filter {
                                it.series.id == data.series.id || it.seasonIndex > 0 || it.season.isNotBlank() ||
                                    RelatedWorks.sameSeries(data.series, it.series)
                            }
                            .map { if (it.series.id == data.series.id) it.copy(season = seasonLabel(data.series.title)) else it }
                    }
                    .getOrNull()
            main.post {
                if (rows == null) return@post
                seasonCache.put(data.series.id, rows)
                // 缓存不存简介，比较时同样去掉。
                val changed = rows.map { it.copy(series = it.series.copy(description = "")) } != cached
                if (generation == ticket && screen == "detail" && view.isAttachedToWindow && changed)
                    view.showSeasons(rows)
            }
        }
    }

    private fun seasonLabel(title: String) = "第${RelatedWorks.seasonOf(title) ?: 1}季"

    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed() = goBack()

    override fun onUserInteraction() {
        super.onUserInteraction()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!library.isLoaded) return
        when (screen) {
            // 进出播放页时由本应用切换横竖屏；只需按新尺寸重排画面，不重新起播。
            "player" -> {
                phoneOverlay?.arrange(newConfig.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                playerView?.post { applyVideoRotation() }
            }
            "detail" -> if (detail != null) showDetail()
            else -> showCatalog()
        }
    }

    override fun onResume() {
        super.onResume()
        if (screenOffPlayback) {
            screenOffPlayback = false
            ScreenOffPlaybackService.stop(this)
        }
        foreground = true
        social.foreground(true)
        main.removeCallbacks(tick)
        main.post(tick)
        updater.resume()
        if (library.isLoaded) {
            if (initialCatalogPending) showCatalog()
            else if (restoreStoppedPage) {
                restoreStoppedPage = false
                when (screen) {
                    "player" -> playEpisode(episodeIndex, startPosition, autoplay = false)
                    "detail" ->
                        if (detail != null) showDetail()
                        // 后台期间详情请求可能被取消，回到前台时重新读取。
                        else pendingDetail?.let { openDetail(it, directPlayback, autoplay = false) } ?: showCatalog()
                    else ->
                        showCatalog()
                }
            } else {
                val waiting = deferredWork
                deferredWork = null
                waiting?.apply?.invoke()
            }
            if (screen == "player") syncMediaSession()
        } else libraryLoadError?.let(::showLibraryError)
    }

    override fun onPause() {
        // 只在息屏时继续播放；按 Home 离开应用仍按原逻辑暂停。
        if (
            screen == "player" &&
                screenOffEnabled &&
                player?.playWhenReady == true &&
                !playError &&
                !(getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
        ) {
            screenOffPlayback = true
            saveProgress()
            library.flush()
            ScreenOffPlaybackService.start(this, playingLabel())
            super.onPause()
            return
        }
        stallGuard.suspendObservation()
        cancelNextSeason()
        social.foreground(false)
        updater.pause()
        foreground = false
        main.removeCallbacks(tick)
        cancelRecovery()
        cancelPrefetch()
        mediaSession.deactivate()
        sleepTimer.cancel()
        detailAutoplay = false
        requestedAutoplay = false
        pausedForLifecycle = true
        player?.pause()
        saveProgress()
        library.flush()
        super.onPause()
    }

    // 应用离开前台时暂停播放及后台活动，防止页面不可见时继续消耗资源。
    override fun onStop() {
        if (screenOffPlayback) {
            library.flush()
            super.onStop()
            return
        }
        restoreStoppedPage = true
        generation++
        cancelWork()
        tvTools.close()
        if (screen == "player") {
            startPosition = currentPosition()
            saveProgress()
            releasePlayer()
        }
        artwork.cancelPage(clearMemory = true)
        library.flush()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::artwork.isInitialized) artwork.trimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cancelPrefetch()
    }

    // 页面销毁时释放播放器、请求和工作线程，避免资源继续持有旧 Activity。
    override fun onDestroy() {
        ScreenOffPlaybackService.stop(this)
        phoneShell?.close()
        phoneShell = null
        generation++
        cancelWork()
        if (networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        social.destroy()
        tvTools.destroy()
        updater.destroy()
        saveProgress()
        releasePlayer()
        library.close()
        artwork.close()
        mediaSession.release()
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        rankWorker.shutdownNow()
        prefetchWorker.shutdownNow()
        networkCleanup.shutdown()
        Thread(
                {
                    repository.http.dispatcher.cancelAll()
                    repository.http.connectionPool.evictAll()
                    repository.http.dispatcher.executorService.shutdown()
                },
                "hongguotv-network-cleanup",
            )
            .start()
        super.onDestroy()
    }
}
