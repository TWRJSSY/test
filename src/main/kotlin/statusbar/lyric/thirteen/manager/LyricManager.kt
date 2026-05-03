package statusbar.lyric.thirteen.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.android.ContextClass
import com.hchen.superlyricapi.ISuperLyricReceiver
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import statusbar.lyric.thirteen.core.StateController

/**
 * LyricManager — SuperLyric Binder 感知器
 *
 * 职责：只接收数据，格式化，上报 StateController。不做任何过滤判断（过滤在 StateController）。
 * 遵循 F-01：不直接调用 StatusBarHook 的任何方法。
 *
 * SuperLyric 关键接入：
 *   - 注册后立即调用 setSystemPlayStateListenerEnabled(false)，防止 stop 双重触发
 *   - playbackState 字段在 3.4 中永远为 null，不能用于 BUFFERING 过滤
 *   - onStop 只检查 publisher 匹配，不做 BUFFERING 判断
 */
object LyricManager {

    private const val TAG = "LyricManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    // SuperLyric 服务端暂停时推送的提示帧文本，需过滤不显示
    private const val SUPERLYRIC_PAUSE_HINT = "歌曲已暂停，即将隐藏歌词"

    // 当前活跃的 publisher（感知层用于 onStop 过滤，调度层也有仲裁）
    private var currentPublisher = ""
    // 上次推送的 title，用于判断是否切歌
    private var lastTitle: String? = null

    // 标题防抖（切歌 800ms 后显示）
    private var showTitleRunnable: Runnable? = null
    private var lastArtist = ""

    // pending 变量：800ms 到期时用最新数据上报，而非切歌瞬间快照
    private var pendingTitle:  String? = null
    private var pendingArtist: String? = null
    private var pendingLyric:  String  = ""
    private var pendingPkg:    String  = ""
    private var pendingDelay:  Long    = 0L
    private var pendingWords:  Array<*>? = null
    private var pendingSec:    String? = null

    private var stateLoaded = false

    fun init(packageParam: PackageParam) {
        packageParam.apply {
            "android.app.Application".hook {
                injectMember {
                    method { name = "attach"; param(ContextClass) }
                    afterHook {
                        val context = args[0] as? Context ?: return@afterHook
                        if (!stateLoaded) {
                            runCatching {
                                StateController.loadSwitches(context)
                                stateLoaded = true
                                YLog.debug(msg = "✓ StateController switches loaded")
                            }.onFailure {
                                YLog.debug(msg = "✗ StateController init: ${it.message}")
                            }
                        }
                        initSuperLyric(context)
                    }
                }
            }.ignoredHookClassNotFoundFailure()
        }
    }

    private fun initSuperLyric(context: Context) {
        // 检查 SuperLyric 服务是否可用
        if (!SuperLyricHelper.isAvailable()) {
            YLog.debug(msg = "$TAG SuperLyric service not available, skipping")
            return
        }

        // 岛出现时清掉 showTitleRunnable，避免正常播放时岛刷新误触发歌名弹窗
        StateController.setOnIslandShownCallback {
            showTitleRunnable?.let { mainHandler.removeCallbacks(it) }
            showTitleRunnable = null
            YLog.debug(msg = "LyricManager: showTitleRunnable cleared (island shown)")
        }

        SuperLyricHelper.registerReceiver(object : ISuperLyricReceiver.Stub() {

            override fun onLyric(publisher: String?, data: SuperLyricData?) {
                if (data == null) return
                val lyricText = data.getLyric()?.getText()?.takeIf { it.isNotEmpty() }

                // V5修复：过滤 SuperLyric 服务端的暂停提示帧。
                // 服务端暂停时会先 sendLyric("歌曲已暂停，即将隐藏歌词") 再 sendStop，
                // 这一帧若不过滤会显示在状态栏 5 秒（直到 scheduleHide 到期）。
                if (lyricText == SUPERLYRIC_PAUSE_HINT) return

                // 纯音乐路径：无歌词但有 title/artist → 单独上报标题弹窗，不走歌词主流程
                if (lyricText == null) {
                    val hasTitle  = data.hasTitle()
                    val hasArtist = data.hasArtist()
                    if (hasTitle || hasArtist) {
                        val title  = if (hasTitle)  data.getTitle()  else null
                        val artist = if (hasArtist) data.getArtist() else null
                        val pkg    = publisher ?: ""
                        mainHandler.post {
                            currentPublisher = pkg
                            // 防抖：artist 改变才触发，逻辑与有歌词时一致
                            val artistChanged = artist != lastArtist
                            if (artistChanged) {
                                lastArtist = artist ?: ""
                                pendingTitle  = title
                                pendingArtist = artist
                                if (showTitleRunnable == null) {
                                    showTitleRunnable = Runnable {
                                        showTitleRunnable = null
                                        StateController.onPureMusicTitleUpdate(
                                            title       = pendingTitle,
                                            artist      = pendingArtist,
                                            packageName = pkg
                                        )
                                    }
                                    mainHandler.postDelayed(showTitleRunnable!!, 800L)
                                }
                            }
                        }
                    }
                    return
                }

                mainHandler.post {
                    currentPublisher = publisher ?: ""

                    val lyric  = lyricText
                    val pkg    = currentPublisher
                    val delay  = data.getLyric()?.let { it.endTime - it.startTime } ?: 0L
                    val words  = data.getLyric()?.getWords()
                    val sec    = resolveSecondary(data)
                    val title  = if (data.hasTitle()) data.getTitle() else null
                    // SuperLyric 3.4 有效字段：hasTitle/hasArtist（无 hasAlbum，手册附录C）
                    val artist = if (data.hasArtist()) data.getArtist() else null

                    // 始终用最新数据覆盖 pending（无论是否切歌，800ms 到期时上报最新一帧）
                    pendingLyric  = lyric
                    pendingPkg    = pkg
                    pendingDelay  = delay
                    pendingWords  = words
                    pendingSec    = sec
                    pendingTitle  = title
                    pendingArtist = artist

                    // 标题防抖：单 Runnable + 数据覆盖模式
                    // 切歌（artist 改变）时，若 Runnable 尚未执行则继续等待剩余时间；
                    // 若已执行（showTitleRunnable==null），则创建新 Runnable。
                    // 效果：无论切歌多快，只要停下来超过 800ms，弹窗必然触发，且用的是最新歌词帧。
                    // ⚠️ 只用 artist 判断切歌（SuperLyric 3.4 无 hasAlbum，手册附录C）
                    if (StateController.titleSwitch) {
                        val artistChanged = artist != lastArtist
                        if (artistChanged) {
                            YLog.debug(msg = "LyricManager: artistChanged old='$lastArtist' new='$artist' pkg=$pkg")
                            lastArtist = artist ?: ""
                            if (showTitleRunnable == null) {
                                // 尚无 Runnable，首次切歌，创建并投递
                                showTitleRunnable = Runnable {
                                    showTitleRunnable = null
                                    // title 与上次推送相同则不弹窗（岛刷新/媒体卡片等非切歌场景）
                                    if (pendingTitle == lastTitle) {
                                        YLog.debug(msg = "LyricManager: showTitleRunnable skipped (title unchanged: $pendingTitle)")
                                        return@Runnable
                                    }
                                    lastTitle = pendingTitle
                                    StateController.onLyricUpdate(
                                        lyric       = pendingLyric,
                                        packageName = pendingPkg,
                                        delay       = pendingDelay,
                                        words       = pendingWords,
                                        secondary   = pendingSec,
                                        title       = pendingTitle,
                                        artist      = pendingArtist
                                    )
                                }
                                mainHandler.postDelayed(showTitleRunnable!!, 800L)
                            }
                            // 若 Runnable 已在队列中，不取消不重建，等它自然到期用最新 pending 数据即可
                        }
                    }

                    // 歌词帧立刻上报（title=null 不触发弹窗）
                    StateController.onLyricUpdate(
                        lyric       = lyric,
                        packageName = pkg,
                        delay       = delay,
                        words       = words,
                        secondary   = sec,
                        title       = null,
                        artist      = null
                    )
                }
            }

            override fun onStop(publisher: String?, data: SuperLyricData?) {
                mainHandler.post {
                    YLog.debug(msg = "LyricManager: onStop publisher=$publisher currentPublisher=$currentPublisher")
                    if (publisher != null && currentPublisher.isNotEmpty() && publisher != currentPublisher) return@post
                    currentPublisher = ""
                    lastArtist = ""
                    // 清理标题 Runnable 并清空所有 pending，防止 Runnable 到期后带旧数据上报
                    showTitleRunnable?.let { mainHandler.removeCallbacks(it) }
                    showTitleRunnable = null
                    pendingTitle  = null
                    pendingArtist = null
                    pendingLyric  = ""
                    pendingPkg    = ""
                    pendingDelay  = 0L
                    pendingWords  = null
                    pendingSec    = null
                    lastTitle = null
                    StateController.onMusicPaused()
                }
            }
        })

        // 关键：注册后立即禁用服务端 MediaSession 监听
        // 防止 stop 双重触发（Publisher.sendStop + 服务端自动触发各一次）
        SuperLyricHelper.setSystemPlayStateListenerEnabled(false)

        // 锁屏广播
        if (StateController.hideLyricWhenLockScreen) {
            val lockFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            val lockReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF   -> StateController.onScreenLocked()
                        Intent.ACTION_USER_PRESENT -> StateController.onScreenUnlocked()
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(lockReceiver, lockFilter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(lockReceiver, lockFilter)
            }
        }

        // 配置更新广播
        val configReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.getStringExtra("type") == "normal") {
                    ctx?.let { StateController.loadSwitches(it) }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(configReceiver, IntentFilter("updateConfig"), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(configReceiver, IntentFilter("updateConfig"))
        }

        YLog.debug(msg = "$TAG SuperLyric 3.4 registered, systemPlayStateListener=disabled")
    }

    /**
     * 次要歌词行：secondary 优先，降级 translation（在感知层统一处理，调度层不区分来源）
     * SuperLyric 规范：secondary 和 translation 不会同时出现
     */
    private fun resolveSecondary(data: SuperLyricData): String? = when {
        data.hasSecondary()   -> data.getSecondary()?.getText()
        data.hasTranslation() -> data.getTranslation()?.getText()
        else                  -> null
    }
}
