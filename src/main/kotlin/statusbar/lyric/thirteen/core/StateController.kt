package statusbar.lyric.thirteen.core

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.highcapable.yukihookapi.hook.log.YLog

// ══════════════════════════════════════════════════════════════
// 枚举定义
// ══════════════════════════════════════════════════════════════

enum class LyricState {
    NORMAL,         // 无音乐，完全不干预系统
    LYRIC,          // 有音乐，无岛
    LYRIC_SHORT,    // 有音乐，短岛（≤30%屏宽），歌词限宽，信号隐藏
    SYSTEM_RESTORE, // 有音乐，中/长/并列岛，完全交还系统
    MANUAL_CLOCK,   // 用户切回原始时钟（永久，直到点原始时钟退出）
    MANUAL_SIGNAL,  // 用户切回信号区（永久，左侧歌词保留）
}

enum class IslandHandling {
    SYSTEM_RESTORE, // 正常：小岛→LYRIC_SHORT，大岛→SYSTEM_RESTORE
    BLOCK,          // 完全拦截，状态不变
    HIDE_TO_CUTOUT  // 视觉收缩到挖孔，歌词继续，islandHiddenForLyric=true
}

enum class IslandRule(val displayName: String, val description: String) {
    DEFAULT("默认规则", "遵循系统默认行为"),
    ALWAYS_ALLOW("始终允许", "始终允许应用在超级岛显示"),
    ALWAYS_BLOCK("始终禁止", "完全阻止应用在超级岛显示"),
    HIDE_TO_CUTOUT("隐藏到挖孔", "自动隐藏至屏幕挖孔区域")
}

fun IslandRule.toHandling(): IslandHandling = when (this) {
    IslandRule.ALWAYS_ALLOW   -> IslandHandling.SYSTEM_RESTORE
    IslandRule.ALWAYS_BLOCK   -> IslandHandling.BLOCK
    IslandRule.HIDE_TO_CUTOUT -> IslandHandling.HIDE_TO_CUTOUT
    IslandRule.DEFAULT        -> IslandHandling.SYSTEM_RESTORE
}

// ══════════════════════════════════════════════════════════════
// StateController
// 铁律：
//   ① 本类不操作任何 View
//   ② Hook 层只调用 on*() 方法，不调用 transitionTo()
//   ③ 所有标志位只由本类内部修改
// ══════════════════════════════════════════════════════════════

object StateController {

    private const val TAG = "StateController"

    // ── 状态数据 ──────────────────────────────────────────────

    var currentState: LyricState = LyricState.NORMAL
        private set

    /** 只在首次进入 SYSTEM_RESTORE 或 LYRIC_SHORT 时更新，连续大岛时不覆盖 */
    var previousState: LyricState = LyricState.NORMAL
        private set

    var userForcedClock: Boolean = false
        private set

    var userForcedSignal: Boolean = false
        private set

    /** 冗余岛已隐藏到挖孔，持久保持，仅 onMusicStopped 清除 */
    var islandHiddenForLyric: Boolean = false
        private set

    var isLandscape: Boolean = false
        private set

    // 当前活跃 publisher（多源仲裁用）
    var currentPublisher: String = ""
        private set

    var currentLyricPackage: String? = null
        private set

    var currentLyric: String? = null
        private set

    var currentSecondary: String? = null
        private set

    var currentTitle: String? = null
        private set

    var currentIconBitmap: Bitmap? = null
        private set

    var currentTextColor: Int = Color.WHITE
        private set

    /** 当前歌词持续时长（ms），感知层写入，执行层通过 onLyricUpdatedCb 读取 */
    var currentDelay: Long = 0L

    /** 逐字数据，执行层通过 onLyricUpdatedCb 读取 */
    var currentWords: Array<*>? = null

    // 多源仲裁：最后收到歌词的时间戳
    private var lastLyricTimestamp: Long = 0L

    // 岛坐标（IslandHook 写入，StatusBarHook 通过回调读取）
    var currentIslandWidth: Int = 0
    var currentIslandLeft: Int = 0
    private var screenWidth: Int = 1080

    // ── 功能开关 ──────────────────────────────────────────────

    var showCustomClock: Boolean = false  // 右侧新时钟，默认关闭，用户手动开启
    var showLyricIcon: Boolean = true
    var lyricRedundancyHideEnabled: Boolean = true
    var videoBlacklistEnabled: Boolean = true
    var titleSwitch: Boolean = true
    var hideLyricWhenLockScreen: Boolean = false
    var extraKillMediaCard: Boolean = true
    var extraKillMusicHeadsUp: Boolean = false
    var extraKillLockscreenMedia: Boolean = false
    var extraIconRule: Boolean = false
    var extraLockscreenLyric: Boolean = false

    private val defaultVideoBlacklist = setOf(
        "tv.danmaku.bili", "com.bilibili.app.in",
        "com.ss.android.ugc.aweme", "com.kuaishou.nebula",
        "com.kuaishou.story", "com.zhiliaoapp.musically"
    )

    // ── 渲染回调 ──────────────────────────────────────────────

    private var onStateChangedCb: ((LyricState) -> Unit)? = null
    private var onIconUpdatedCb: ((Bitmap?) -> Unit)? = null
    private var onLyricUpdatedCb: ((String?) -> Unit)? = null
    private var onSecondaryUpdatedCb: ((String?) -> Unit)? = null
    private var onClockColorChangedCb: ((Int) -> Unit)? = null
    private var onTitleChangedCb: ((String?) -> Unit)? = null
    private var onAppIconUpdatedCb: ((Bitmap?) -> Unit)? = null
    private var onIslandHideToCutoutCb: (() -> Unit)? = null
    private var onIslandShownCb: (() -> Unit)? = null
    fun setOnIslandShownCallback(cb: (() -> Unit)?) { onIslandShownCb = cb }

    fun setOnStateChangedCallback(cb: ((LyricState) -> Unit)?) { onStateChangedCb = cb }
    fun setOnIconUpdatedCallback(cb: ((Bitmap?) -> Unit)?) { onIconUpdatedCb = cb }
    fun setOnLyricUpdatedCallback(cb: ((String?) -> Unit)?) { onLyricUpdatedCb = cb }
    fun setOnSecondaryUpdatedCallback(cb: ((String?) -> Unit)?) { onSecondaryUpdatedCb = cb }
    fun setOnClockColorChangedCallback(cb: ((Int) -> Unit)?) { onClockColorChangedCb = cb }
    fun setOnTitleChangedCallback(cb: ((String?) -> Unit)?) { onTitleChangedCb = cb }
    fun setOnAppIconUpdatedCallback(cb: ((Bitmap?) -> Unit)?) { onAppIconUpdatedCb = cb }
    fun setOnIslandHideToCutoutCallback(cb: (() -> Unit)?) { onIslandHideToCutoutCb = cb }

    // ── 5秒计时器 ─────────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { onMusicStopped() }

    fun cancelHideTimer() {
        mainHandler.removeCallbacks(hideRunnable)
        YLog.debug(msg = "$TAG cancelHideTimer state=$currentState")
    }
    private fun scheduleHide() {
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.postDelayed(hideRunnable, 5000L)
        YLog.debug(msg = "$TAG scheduleHide(5000) state=$currentState")
    }

    // ── 多源仲裁 ──────────────────────────────────────────────

    private fun isCurrentPublisherStale(): Boolean {
        if (lastLyricTimestamp == 0L) return true
        return System.currentTimeMillis() - lastLyricTimestamp > 8000L
    }

    // ── SharedPreferences ─────────────────────────────────────

    private var appContext: Context? = null
    private var islandRulePrefs: SharedPreferences? = null
    private var recommendedRulesApplied = false

    fun loadSwitches(context: Context) {
        appContext = context.applicationContext ?: context
        val sp = appContext!!.getSharedPreferences("COMPOSE_CONFIG", Context.MODE_PRIVATE)
        showCustomClock            = sp.getBoolean("show_custom_clock",           false) // 默认关闭
        showLyricIcon              = sp.getBoolean("show_lyric_icon",             true)
        lyricRedundancyHideEnabled = sp.getBoolean("lyric_redundancy_hide",       true)
        videoBlacklistEnabled      = sp.getBoolean("video_blacklist_enabled",     true)
        titleSwitch                = sp.getBoolean("titleSwitch",                 true)
        hideLyricWhenLockScreen    = sp.getBoolean("hideLyricWhenLockScreen",     false)
        extraKillMediaCard         = sp.getBoolean("extra_kill_media_card",       true)
        extraKillMusicHeadsUp      = sp.getBoolean("extra_kill_music_headsup",    false)
        extraKillLockscreenMedia   = sp.getBoolean("extra_kill_lockscreen_media", false)
        extraIconRule              = sp.getBoolean("extra_icon_rule",             false)
        extraLockscreenLyric       = sp.getBoolean("extra_lockscreen_lyric",      false)
        islandRulePrefs = appContext!!.getSharedPreferences("lyric_island_rules", Context.MODE_PRIVATE)
        if (!recommendedRulesApplied) {
            applyRecommendedIslandRules()
            recommendedRulesApplied = true
        }
        YLog.debug(msg = "$TAG switches loaded, showCustomClock=$showCustomClock")
    }

    /**
     * 预置推荐默认规则到 SP（仅在用户未设置时写入，不覆盖用户已有配置）。
     * 规则语义：
     *   视频类 APP → ALWAYS_BLOCK（避免岛打断歌词显示）
     *   音乐类歌词来源 APP → HIDE_TO_CUTOUT（岛收缩到挖孔，歌词继续）
     * 用户可在 IslandRulePage 随时修改。
     */
    private fun applyRecommendedIslandRules() {
        val prefs = islandRulePrefs ?: return
        val videoApps = setOf(
            "tv.danmaku.bili", "com.bilibili.app.in",
            "com.ss.android.ugc.aweme", "com.kuaishou.nebula",
            "com.kuaishou.story", "com.zhiliaoapp.musically"
        )
        val musicApps = setOf(
            "com.tencent.qqmusic", "com.netease.cloudmusic",
            "com.kugou.android", "com.kugou.android.lite",
            "cn.kuwo.player", "com.miui.player"
        )
        prefs.edit().apply {
            videoApps.forEach { pkg ->
                val key = "rule_$pkg"
                if (!prefs.contains(key)) putString(key, IslandRule.ALWAYS_BLOCK.name)
            }
            musicApps.forEach { pkg ->
                val key = "rule_$pkg"
                if (!prefs.contains(key)) putString(key, IslandRule.HIDE_TO_CUTOUT.name)
            }
            apply()
        }
    }

    // ══════════════════════════════════════════════════════════
    // 事件上报接口（感知层唯一调用入口）
    // ══════════════════════════════════════════════════════════

    /**
     * 来自 LyricManager。
     * 门卫：视频黑名单过滤 + 多源仲裁（在调度层统一处理，感知层不过滤）
     */
    fun onLyricUpdate(
        lyric: String,
        packageName: String,
        delay: Long,
        words: Array<*>?,
        secondary: String?,
        title: String?,
        artist: String?
    ) {
        // 门卫1：视频黑名单
        if (videoBlacklistEnabled && packageName in defaultVideoBlacklist) {
            YLog.debug(msg = "$TAG blocked by video blacklist: $packageName")
            return
        }

        // 门卫2：多源仲裁
        if (currentPublisher.isNotEmpty()
            && currentPublisher != packageName
            && !isCurrentPublisherStale()) {
            YLog.debug(msg = "$TAG rejected from $packageName, active: $currentPublisher")
            return
        }

        // 通过门卫，接受数据
        cancelHideTimer()
        currentPublisher = packageName
        currentLyricPackage = packageName
        currentLyric = lyric
        currentDelay = delay
        currentWords = words
        currentSecondary = secondary
        lastLyricTimestamp = System.currentTimeMillis()

        onLyricUpdatedCb?.invoke(lyric)
        onSecondaryUpdatedCb?.invoke(secondary)

        // V8修复：横屏/强制时钟/SYSTEM_RESTORE 下不触发弹窗
        if (isLandscape) return
        if (userForcedClock) return
        if (currentState == LyricState.SYSTEM_RESTORE) return

        if (title != null || artist != null) {
            val titleText = buildString {
                if (!title.isNullOrEmpty()) append(title)
                if (!artist.isNullOrEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(artist)
                }
            }.ifEmpty { null }
            // 统一走 onTitleReceived，享有冷却保护，避免岛刷新等场景重复弹窗
            onTitleReceived(titleText)
        }

        when (currentState) {
            LyricState.NORMAL -> transitionTo(LyricState.LYRIC)
            // MANUAL_SIGNAL：歌词文字通过 onLyricUpdatedCb 已更新，状态不变（保持用户选择）
            // MANUAL_CLOCK：userForcedClock=true 已在前面 return，不会到这里
            // LYRIC / LYRIC_SHORT：不重复 transition
            else -> { /* 其他状态：歌词文字已更新，状态保持 */ }
        }
    }

    /** 来自 LyricManager.onStop。只启动5秒计时，不立即修改状态（间奏保护） */
    fun onMusicPaused() {
        lastLyricTimestamp = 0L // 清零，下一个 publisher 可以接管
        scheduleHide()
    }

    private fun onMusicStopped() {
        YLog.debug(msg = "$TAG onMusicStopped state=$currentState userForcedClock=$userForcedClock")
        currentPublisher = ""
        currentLyric = null
        currentSecondary = null
        currentLyricPackage = null
        currentIconBitmap = null
        currentTitle = null
        lastTitleShownText = null  // 重置，保证新歌第一次能弹
        islandHiddenForLyric = false
        userForcedClock = false
        userForcedSignal = false
        lastLyricTimestamp = 0L
        onLyricUpdatedCb?.invoke(null)
        onSecondaryUpdatedCb?.invoke(null)
        // icon 은 onNotificationRemoved 에서만 clear.
        // onMusicStopped 에서 clear 하면 岛 刷新 등 假停止 후 재생 시 icon 이 복원되지 않음.
        onTitleChangedCb?.invoke(null)
        transitionTo(LyricState.NORMAL)
    }

    fun onScreenLocked() {
        if (hideLyricWhenLockScreen) transitionTo(LyricState.NORMAL)
    }

    fun onScreenUnlocked() {
        // userForcedClock/Signal 优先：用户在 MANUAL_CLOCK 状态下锁屏后解锁，
        // 不应被强制切回 LYRIC，保持用户选择的状态。
        if (userForcedClock || userForcedSignal) return
        if (!currentLyric.isNullOrEmpty() && !isLandscape) transitionTo(LyricState.LYRIC)
    }

    // ── 来自 NotificationTracker ──────────────────────────────

    fun onIconReceived(bitmap: Bitmap, packageName: String) {
        currentIconBitmap = bitmap
        onIconUpdatedCb?.invoke(bitmap)
    }

    fun onAppIconReceived(bitmap: Bitmap?) { onAppIconUpdatedCb?.invoke(bitmap) }

    /**
     * 来自 NotificationTracker：通知中读到的标题（title only，无 artist）。
     * 仅在 currentLyric != null 时更新（通知标题兜底路径，不用于纯音乐主路径）。
     * 纯音乐主路径请用 onPureMusicTitleUpdate()，由 LyricManager 在无歌词帧时专门调用。
     */
    // 白名单原则：title 变化才弹窗，相同 title 永远不弹，不用时间冷却
    private var lastTitleShownText: String? = null

    fun onTitleReceived(title: String?) {
        if (title.isNullOrEmpty()) return
        // title 没变 → 永远不弹（白名单：只有切歌 title 变化才允许弹窗）
        if (title == lastTitleShownText) return
        lastTitleShownText = title
        currentTitle = title
        onTitleChangedCb?.invoke(title)
    }

    /**
     * 来自 LyricManager：纯音乐（SuperLyric 有 title/artist 但无歌词行）专用路径。
     * 视频黑名单门卫在此处保持一致（避免视频 App 的歌名误弹窗）。
     * 不影响状态机状态，只触发标题弹窗回调。
     */
    fun onPureMusicTitleUpdate(title: String?, artist: String?, packageName: String) {
        // 视频黑名单门卫：与 onLyricUpdate 保持一致
        if (videoBlacklistEnabled && packageName in defaultVideoBlacklist) return
        if (!titleSwitch) return
        val titleText = buildString {
            if (!title.isNullOrEmpty()) append(title)
            if (!artist.isNullOrEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(artist)
            }
        }.ifEmpty { null } ?: return
        YLog.debug(msg = "$TAG onPureMusicTitleUpdate: $titleText from $packageName")
        onTitleReceived(titleText)
    }

    fun onNotificationRemoved(packageName: String) {
        if (packageName == currentLyricPackage) {
            currentIconBitmap = null
            onIconUpdatedCb?.invoke(null)
        }
    }

    // ── 来自 StatusBarHook（用户交互），走状态机，不绕过 ─────

    fun onLyricClicked() {
        if (currentState == LyricState.LYRIC ||
            currentState == LyricState.LYRIC_SHORT ||
            currentState == LyricState.MANUAL_SIGNAL) {
            userForcedClock = true; userForcedSignal = false
            transitionTo(LyricState.MANUAL_CLOCK)
        }
    }

    fun onOriginalClockClicked() {
        if (currentState == LyricState.MANUAL_CLOCK) {
            userForcedClock = false
            transitionTo(LyricState.LYRIC)
        }
    }

    fun onNewClockClicked() {
        if (currentState == LyricState.LYRIC || currentState == LyricState.LYRIC_SHORT) {
            userForcedSignal = true
            transitionTo(LyricState.MANUAL_SIGNAL)
        }
    }

    fun onSystemIconClicked() {
        if (currentState == LyricState.MANUAL_SIGNAL) {
            userForcedSignal = false
            transitionTo(LyricState.LYRIC)
        }
    }

    // ── 来自 IslandHook ────────────────────────────────────────

    fun onIslandShown(islandWidth: Int, islandLeft: Int, packageName: String, screenWidth: Int) {
        updateScreenWidth(screenWidth)
        currentIslandWidth = islandWidth
        currentIslandLeft = islandLeft
        if (isLandscape) return

        val handling = getIslandHandling(packageName)
        YLog.debug(msg = "$TAG onIslandShown pkg=$packageName width=$islandWidth handling=$handling")

        when (handling) {
            IslandHandling.BLOCK -> return
            IslandHandling.HIDE_TO_CUTOUT -> {
                onIslandShownCb?.invoke()  // 岛出现，通知 LyricManager 清掉 showTitleRunnable
                islandHiddenForLyric = true
                // 临时过渡方案（手册 §四 状态机转换规则）：
                //   HIDE_TO_CUTOUT 真正实现（收缩到挖孔动画）已独立立项，主线跑通后合并。
                //   当前按岛宽度分叉处理，与 SYSTEM_RESTORE 路径行为一致。
                val shortThreshold = (screenWidth * 0.30).toInt()
                if (currentState != LyricState.LYRIC_SHORT && currentState != LyricState.SYSTEM_RESTORE) {
                    previousState = currentState
                }
                if (islandWidth in 1..shortThreshold) transitionTo(LyricState.LYRIC_SHORT)
                else transitionTo(LyricState.SYSTEM_RESTORE)
                // 触发缩岛回调（独立立项完成前此回调无实际效果，不影响状态机）
                onIslandHideToCutoutCb?.invoke()
            }
            IslandHandling.SYSTEM_RESTORE -> {
                onIslandShownCb?.invoke()  // 岛出现，通知 LyricManager 清掉 showTitleRunnable
                val shortThreshold = (screenWidth * 0.30).toInt()
                // 关键：只在首次进入时记录 previousState
                if (currentState != LyricState.LYRIC_SHORT && currentState != LyricState.SYSTEM_RESTORE) {
                    previousState = currentState
                }
                if (islandWidth in 1..shortThreshold) transitionTo(LyricState.LYRIC_SHORT)
                else transitionTo(LyricState.SYSTEM_RESTORE)
            }
        }
    }

    fun onIslandHidden() {
        islandHiddenForLyric = false
        currentIslandWidth = 0
        currentIslandLeft = 0
        if (currentState != LyricState.SYSTEM_RESTORE && currentState != LyricState.LYRIC_SHORT) return

        // previousState 直接决定恢复目标，不依赖 currentLyric（onMusicStopped 会清空它）
        // LYRIC/NORMAL 时：看 previousState 是否是 LYRIC，是则回 LYRIC，否则 NORMAL
        val target = when (previousState) {
            LyricState.MANUAL_CLOCK  -> LyricState.MANUAL_CLOCK
            LyricState.MANUAL_SIGNAL -> LyricState.MANUAL_SIGNAL
            LyricState.LYRIC         -> LyricState.LYRIC
            else                     -> LyricState.NORMAL
        }
        YLog.debug(msg = "$TAG onIslandHidden previousState=$previousState → $target")
        transitionTo(target)
    }

    // ── 来自 StatusBarHook ─────────────────────────────────────

    fun onTextColorChanged(color: Int) {
        currentTextColor = color
        onClockColorChangedCb?.invoke(color)
    }

    fun onOrientationChanged(landscape: Boolean) {
        if (landscape == isLandscape) return
        isLandscape = landscape
        if (landscape) transitionTo(LyricState.NORMAL)
        else {
            val target = if (!currentLyric.isNullOrEmpty()) LyricState.LYRIC else LyricState.NORMAL
            transitionTo(target)
        }
    }

    // ══════════════════════════════════════════════════════════
    // 核心状态转换（私有）
    // ══════════════════════════════════════════════════════════

    private fun transitionTo(newState: LyricState) {
        if (currentState == newState) return
        YLog.debug(msg = "$TAG State: $currentState → $newState")
        currentState = newState
        mainHandler.post { onStateChangedCb?.invoke(newState) }
    }

    // ══════════════════════════════════════════════════════════
    // 超级岛规则引擎
    // ══════════════════════════════════════════════════════════

    fun getIslandHandling(islandPackage: String): IslandHandling {
        val userRule = getUserIslandRule(islandPackage)
        if (userRule != IslandRule.DEFAULT) return userRule.toHandling()
        // 兜底：未设置规则的 APP 完全交还系统，我们不干预。
        // 视频黑名单和音乐冗余岛的默认规则通过 applyRecommendedIslandRules() 预置到 SP，
        // 用户可在 IslandRulePage 自由覆盖，不再硬编码到业务逻辑里。
        return IslandHandling.SYSTEM_RESTORE
    }

    fun setUserIslandRule(packageName: String, rule: IslandRule) {
        islandRulePrefs?.edit()?.apply {
            if (rule == IslandRule.DEFAULT) remove("rule_$packageName")
            else putString("rule_$packageName", rule.name)
            apply()
        }
    }

    fun getUserIslandRule(packageName: String): IslandRule {
        val str = islandRulePrefs?.getString("rule_$packageName", null) ?: return IslandRule.DEFAULT
        return runCatching { IslandRule.valueOf(str) }.getOrDefault(IslandRule.DEFAULT)
    }

    fun updateScreenWidth(width: Int) { if (width > 0) screenWidth = width }
    fun getScreenWidth(): Int = screenWidth
}
