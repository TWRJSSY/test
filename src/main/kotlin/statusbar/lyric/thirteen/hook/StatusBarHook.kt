package statusbar.lyric.thirteen.hook

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.highcapable.yukihookapi.hook.log.YLog
import statusbar.lyric.thirteen.hook.extra.IconFilterHook
import statusbar.lyric.thirteen.core.LyricState
import statusbar.lyric.thirteen.core.StateController
import statusbar.lyric.thirteen.tools.ReflectTools.getObjectFieldIfExist
import statusbar.lyric.thirteen.view.LyricSwitchView
import statusbar.lyric.thirteen.view.LyricTextView
import statusbar.lyric.thirteen.view.TitleDialog
import statusbar.lyric.thirteen.hook.lockscreen.LockscreenLyricHook
import statusbar.lyric.thirteen.manager.NotificationTracker

/**
 * StatusBarHook — 执行层（唯一渲染执行者）
 *
 * 职责分工（已整改，消除 isUserHiding）：
 *   StateController 回调驱动：所有状态渲染，包括用户点击切换
 *   StatusBarHook 自治（UI层，不影响状态机）：10秒超时计时器
 *
 * 注意：isUserHiding 已废弃。用户点击歌词区域 → onLyricClicked() → 状态机切 MANUAL_CLOCK。
 * 原始时钟点击 → onOriginalClockClicked() → 状态机切回 LYRIC。统一走状态机。
 *
 * 新时钟（customClockView）：
 *   - showCustomClock 开关默认 false，用户手动开启
 *   - 开启后在状态栏挖孔右侧显示自定义时钟
 *   - MANUAL_CLOCK 时必须同步隐藏，防双时钟
 */
object StatusBarHook : BaseHook() {

    // 供 IconFilterHook 查询（派生自 StateController.currentState，保持同步）
    val isLyricShowing: Boolean
        get() = isLyricActive()

    // 模块自己的 Resources，供 TitleDialog 在 SystemUI 进程里加载 ic_song 等资源
    var moduleResources: android.content.res.Resources? = null
        private set

    private data class LyricContainer(
        val clock: TextView,
        val target: ViewGroup,
        val layout: LinearLayout,
        val lyricView: LyricSwitchView,
        val iconView: ImageView,
        val customClockView: TextView? = null   // 挖孔右侧注入的自定义时钟（showCustomClock=true 时可见）
    )

    private val containers = mutableListOf<LyricContainer>()
    private var titleDialog: TitleDialog? = null
    private var lastColor: Int = Color.WHITE

    // 信号 View 集合（LYRIC_SHORT 时隐藏，仅保留电池）
    // 通过 Hook PhoneStatusBarView 的 onAttachedToWindow 后查找 system_icons 子 View 填充
    private val signalViews = mutableListOf<View>()
    private var batteryView: TextView? = null
    // notification_icon_area = AlphaOptimizedFrameLayout，子View只有 NotificationIconContainer(notificationIcons)
    // 探针确认：不含岛宿主 MiuiStatusIconContainer，可以安全拦截 setVisibility
    private var notificationIconArea: View? = null
    private var collapsedStatusBarFragment: Any? = null

    // UI 自治：10秒超时（不影响状态机）
    private val TIMEOUT_RESTORE = 1
    private val mainHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: android.os.Message) {
            when (msg.what) {
                TIMEOUT_RESTORE -> {
                    // UI自治：10秒无新歌词帧。
                    // ⚠️ V6 铁律：状态机仍是 LYRIC/LYRIC_SHORT 时，说明音乐仍在播放，
                    //   只是处于间奏（歌词暂停），必须保留最后一句歌词，绝不隐藏 layout。
                    //   "间奏 > 10 秒 → 最后一句保留，不消失" (验收 V6)
                    // ⚠️ Bug4修复：不调 restoreNotificationArea()！
                    //   状态机未转为NORMAL/SYSTEM_RESTORE，通知图标区必须保持INVISIBLE。
                    val state = StateController.currentState
                    if (state == LyricState.LYRIC || state == LyricState.LYRIC_SHORT) {
                        // 音乐仍在播放（间奏），保留歌词，不做任何 UI 变更
                        YLog.debug(msg = "StatusBarHook: TIMEOUT_RESTORE suppressed – still in $state (interlude protection V6)")
                        return
                    }
                    // 状态机已不是歌词状态（异常残留），执行 UI 清理
                    containers.forEach { c ->
                        c.layout.post {
                            c.layout.visibility = View.GONE
                            c.clock.visibility = View.VISIBLE
                            c.customClockView?.visibility = View.GONE
                            // notificationIconArea 保持 INVISIBLE，等状态机真正转 NORMAL 再恢复
                        }
                    }
                }
            }
        }
    }

    override fun init() {
        moduleResources = packageParam.moduleAppResources
        hookClocks()
        hookDarkIconDispatcher()
        hookSignalViews()
        hookCollapsedStatusBarFragment()
        hookNotificationIconArea()
        registerStateControllerCallbacks()
        YLog.debug(msg = "StatusBarHook loaded")
    }

    // ══════════════════════════════════════════════════════════
    // Hook 注册
    // ══════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════
    // 显示决策拦截
    // ══════════════════════════════════════════════════════════

    /**
     * Hook MiuiCollapsedStatusBarFragment 的显示决策方法，实现状态机驱动的显示策略。
     *
     * 核心思路：不再与系统竞态写 visibility，而是在系统的显示决策入口处拦截，
     * 在歌词显示状态下阻止系统恢复时钟和通知图标区。
     *
     * 当前处理（左侧）：
     *   showClock()                → 不拦截（会影响超级岛渲染）
     *   TextView.setVisibility()      → clock View VISIBLE 요청 차단으로 대체
     *   updateStatusBarVisibilities() → 不拦截（会影响超级岛渲染），通知区由 applyLyric 直接设置
     *
     * 未来扩展（右侧，7-E）：
     *   如法炮制 hook 对应的右侧图标显示决策方法即可，架构一致。
     *
     * 适用状态：LYRIC / LYRIC_SHORT / MANUAL_SIGNAL
     * 不拦截：NORMAL / SYSTEM_RESTORE / MANUAL_CLOCK
     */
    private fun hookCollapsedStatusBarFragment() {
        runCatching {
            packageParam.run {
                "com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment".hook {
                    injectMember {
                        method { name = "onViewCreated" }
                        afterHook { collapsedStatusBarFragment = instance }
                    }
                    // showClock 拦截 금지：showClock는 슈퍼 아일랜드 UI 업데이트도 담당하므로
                    // 통째로 막으면 아일랜드가 투명해짐. clock View.setVisibility로 대체 처리.
                    // animateShow 拦截：系统通过 animateShow(View, Z, Z) 以动画方式显示通知图标区，
                    // 不走 setVisibility，所以 View.setVisibility hook 拦不住。
                    // 时钟 GONE 时拦截对 notificationIconArea 的 animateShow 调用。
                    injectMember {
                        method {
                            name = "animateShow"
                            param(View::class.java, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
                        }
                        beforeHook {
                            val target = args[0] as? View ?: return@beforeHook
                            if (target !== notificationIconArea) return@beforeHook
                            val clockGone = containers.firstOrNull()?.clock?.visibility == View.GONE
                            if (clockGone) {
                                resultNull()
                                YLog.debug(msg = "StatusBarHook: blocked animateShow for notifArea (clock GONE)")
                            }
                        }
                    }
                }
            }
        }.onFailure {
            YLog.warn(msg = "StatusBarHook: hookCollapsedStatusBarFragment failed: ${it.message}")
        }
    }

    /** 当前是否处于歌词显示状态（时钟和通知图标由我们接管） */
    private fun isLyricActive(): Boolean = when (StateController.currentState) {
        LyricState.LYRIC,
        LyricState.LYRIC_SHORT,
        LyricState.MANUAL_SIGNAL -> true
        else -> false
    }

    /**
     * 拦截通知图标区（notification_icon_area）的 setVisibility 调用。
     * 在 isLyricActive() 状态下，系统调 setVisibility(VISIBLE) 时改为 INVISIBLE，
     * 阻止通知图标在歌词后面重新出现。
     *
     * 与时钟 setVisibility 拦截原理相同，但针对的是 View 实例而非 id。
     */
    private fun hookNotificationIconArea() {
        runCatching {
            packageParam.run {
                "android.view.View".hook {
                    injectMember {
                        method {
                            name = "setVisibility"
                            param(Int::class.javaPrimitiveType!!)
                        }
                        beforeHook {
                            if (instance !== notificationIconArea) return@beforeHook
                            val req = args[0] as? Int ?: return@beforeHook
                            if (req != View.VISIBLE) return@beforeHook
                            // 时钟隐藏时（歌词显示中）拦截通知图标区恢复为 VISIBLE
                            // 只看时钟状态，不依赖状态机，逻辑最简单
                            val clockGone = containers.firstOrNull()?.clock?.visibility == View.GONE
                            if (clockGone) {
                                args[0] = View.INVISIBLE
                                YLog.debug(msg = "StatusBarHook: blocked notifArea VISIBLE → INVISIBLE (clock GONE)")
                            }
                        }
                    }
                }
            }
        }.onFailure {
            YLog.warn(msg = "StatusBarHook: hookNotificationIconArea failed: ${it.message}")
        }
    }

    private fun hookClocks() {
        with(packageParam) {
            listOf(
                "com.android.systemui.statusbar.views.MiuiClock",
                "com.android.systemui.statusbar.views.MiuiNotificationHeaderClock"
            ).forEach { className ->
                runCatching {
                    className.hook {
                        injectMember {
                            constructor { paramCount(1..4) }
                            afterHook {
                                val view = instance as? TextView ?: return@afterHook
                                view.post { runCatching { initClockView(view) } }
                            }
                        }
                    }
                }
            }
            // Bug1修复（反色）：MiuiClock 没有自己的 setTextColor 实现（继承自 TextView），
            // 直接 hook MiuiClock.setTextColor 会因方法不在该类而报 NoSuchMethod。
            // 改为 hook TextView 基类的 setTextColor(int)，在 afterHook 里按资源名过滤
            // 只处理 id=clock/pad_clock 的时钟 View，与词幕 ClockColorMonitor 原理一致。
            runCatching {
                "android.widget.TextView".hook {
                    injectMember {
                        method {
                            name = "setTextColor"
                            param(Int::class.javaPrimitiveType!!)
                        }
                        afterHook {
                            val view = instance as? TextView ?: return@afterHook
                            val idName = runCatching {
                                view.resources.getResourceEntryName(view.id)
                            }.getOrNull() ?: return@afterHook
                            YLog.debug(msg = "StatusBarHook: TextView.setTextColor idName=$idName")
                            if (idName != "clock" && idName != "pad_clock") return@afterHook
                            val color = view.currentTextColor
                            if (color == 0) return@afterHook
                            YLog.debug(msg = "StatusBarHook: clock setTextColor color=#${Integer.toHexString(color)} containers=${containers.size}")
                            lastColor = color
                            containers.forEach { updateColor(it, color) }
                            StateController.onTextColorChanged(color)
                            NotificationTracker.rerenderCurrentIcon(color)
                        }
                    }
                }
            }
            // setVisibility 定义在 View 基类，必须单独 hook android.view.View
            // 不能放在 TextView.hook{} 里，否则 YukiHookAPI 找不到该方法
            runCatching {
                "android.view.View".hook {
                    injectMember {
                        method {
                            name = "setVisibility"
                            param(Int::class.javaPrimitiveType!!)
                        }
                        beforeHook {
                            val view = instance as? android.view.View ?: return@beforeHook
                            val idName = runCatching {
                                view.resources.getResourceEntryName(view.id)
                            }.getOrNull() ?: return@beforeHook
                            if (idName != "clock" && idName != "pad_clock") return@beforeHook
                            val req = args[0] as? Int ?: return@beforeHook
                            if (req == android.view.View.VISIBLE && isLyricActive()) {
                                args[0] = android.view.View.GONE
                                YLog.debug(msg = "StatusBarHook: blocked clock VISIBLE → GONE (${StateController.currentState})")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hookDarkIconDispatcher() {
        runCatching {
            packageParam.run {
                "com.android.systemui.statusbar.phone.DarkIconDispatcherImpl".hook {
                    injectMember {
                        method { name = "applyDarkIntensity" }
                        afterHook {
                            // 直接从 batteryView 读取颜色，与右侧信号图标保持一致
                            val color = batteryView?.currentTextColor
                                ?: (getObjectFieldIfExist(instance, "mIconTint") as? Int)
                                ?: return@afterHook
                            if (color == 0) return@afterHook
                            YLog.debug(msg = "StatusBarHook: applyDarkIntensity color=#${Integer.toHexString(color)} containers=${containers.size}")
                            lastColor = color
                            containers.forEach { updateColor(it, color) }
                            StateController.onTextColorChanged(color)
                            NotificationTracker.rerenderCurrentIcon(color)
                        }
                    }
                }
            }
        }.onFailure {
            YLog.warn(msg = "StatusBarHook: hookDarkIconDispatcher failed: ${it.message}")
        }
    }

    // ══════════════════════════════════════════════════════════
    // 信号 View 查找（PhoneStatusBarView.onAttachedToWindow）
    // ══════════════════════════════════════════════════════════

    /**
     * Hook PhoneStatusBarView：
     *   1. onAttachedToWindow → 查找信号 View 并缓存
     *   2. onConfigurationChanged → 横竖屏上报 StateController（不可在 beforeHook 设 result=true！）
     *
     * ⚠️ 所有 PhoneStatusBarView injectMember 必须合并到同一个 .hook{} 块，
     *    否则 YukiHookAPI 对同一个类名的多次 .hook{} 调用会产生重复注册。
     */
    private fun hookSignalViews() {
        runCatching {
            packageParam.run {
                "com.android.systemui.statusbar.phone.PhoneStatusBarView".hook {
                    injectMember {
                        method { name = "onAttachedToWindow"; emptyParam() }
                        afterHook {
                            val root = instance as? ViewGroup ?: return@afterHook
                            root.post { runCatching { findAndCacheSignalViews(root) } }
                        }
                    }
                    injectMember {
                        method { name = "onConfigurationChanged" }
                        afterHook {
                            val config = args.getOrNull(0) as? android.content.res.Configuration
                                ?: return@afterHook
                            val landscape = config.orientation ==
                                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                            StateController.onOrientationChanged(landscape)
                        }
                    }
                }
            }
        }.onFailure { YLog.debug(msg = "StatusBarHook: hookSignalViews failed: ${it.message}") }
    }

    private fun findAndCacheSignalViews(root: ViewGroup) {
        // 遍历整棵 View 树，按资源名找目标
        fun ViewGroup.findByEntryName(name: String): View? {
            for (i in 0 until childCount) {
                val child = getChildAt(i) ?: continue
                val entryName = runCatching {
                    child.resources.getResourceEntryName(child.id)
                }.getOrNull()
                if (entryName == name) return child
                if (child is ViewGroup) child.findByEntryName(name)?.let { return it }
            }
            return null
        }

        val systemIcons = root.findByEntryName("system_icons") as? ViewGroup
        if (systemIcons != null) {
            signalViews.clear()
            batteryView = null

            // 探针C（手册附录D）：枚举 system_icons 全部子 View，采集后补全 signalViews
            // 关键：递归收集，HyperOS 3 可能有一层额外 ViewGroup 包装
            fun collectSignalViews(container: ViewGroup, depth: Int = 0) {
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i) ?: continue
                    val entryName = runCatching {
                        child.resources.getResourceEntryName(child.id)
                    }.getOrNull() ?: ""
                    val clsName = child.javaClass.name

                    // 探针C 日志：供真机采集后补全 signalViews（手册附录D）
                    YLog.debug(msg = "[signal][d$depth][$i] cls=${child.javaClass.simpleName} entry=$entryName id=${child.id}")

                    when {
                        // 手册附录C 精确匹配"battery_meter_view"，模糊匹配"battery"作为兜底
                        entryName == "battery_meter_view"
                        || ("battery" in entryName.lowercase() && entryName != "")
                        || ("battery" in clsName.lowercase() && entryName.isEmpty()) -> {
                            batteryView = child as? TextView
                            YLog.debug(msg = "StatusBarHook: batteryView=$entryName cls=${child.javaClass.simpleName}")
                        }
                        // 如果是空白的 ViewGroup 容器（信号图标的父容器），递归进入
                        child is ViewGroup && entryName.isEmpty() && child.childCount > 0 && depth == 0 -> {
                            collectSignalViews(child, depth + 1)
                        }
                        // 非电池 View 均视为信号类 View（mobile/wifi/volte 等）
                        // ⚠️ 排除 MiuiStatusIconContainer（statusIcons）和 MiuiHomePrivacyView：
                        // 这两个容器是超级岛的宿主，GONE 掉会导致岛消失/透明。
                        // 信号图标应该隐藏的是容器内的子 View，而不是容器本身。
                        // 下一步（7-B）：深入 statusIcons 取子 View 精确隐藏移动信号/WiFi 图标。
                        "MiuiStatusIconContainer" in clsName
                        || "MiuiHomePrivacyView" in clsName -> {
                            YLog.debug(msg = "StatusBarHook: signalView SKIPPED (island host): $entryName cls=${child.javaClass.simpleName}")
                        }
                        else -> {
                            signalViews.add(child)
                            YLog.debug(msg = "StatusBarHook: signalView added: $entryName cls=${child.javaClass.simpleName}")
                        }
                    }
                }
            }

            collectSignalViews(systemIcons)
            YLog.debug(msg = "StatusBarHook: signalViews total=${signalViews.size}, batteryView=${batteryView != null}")
        } else {
            YLog.debug(msg = "StatusBarHook: system_icons not found in PhoneStatusBarView")
        }

        // 收集 notification_icon_area（AlphaOptimizedFrameLayout）
        // 探针已确认：子View只有 NotificationIconContainer(notificationIcons)，不含岛宿主，可安全拦截
        if (notificationIconArea == null) {
            notificationIconArea = root.findByEntryName("notification_icon_area")
            YLog.debug(msg = "StatusBarHook: notificationIconArea=${if (notificationIconArea != null) "found" else "not found"}")
        }
    }

    private fun registerStateControllerCallbacks() {
        StateController.setOnStateChangedCallback { state ->
            mainHandler.post { applyState(state) }
        }

        StateController.setOnLyricUpdatedCallback { lyric ->
            mainHandler.post {
                if (lyric.isNullOrEmpty()) return@post
                updateLyricText(lyric, StateController.currentDelay)
                // 每次歌词刷新同步执行互斥绑定原则：
                // 1. 刷新颜色（从 batteryView 读取最新值）
                batteryView?.currentTextColor?.takeIf { it != 0 }?.let { color ->
                    if (color != lastColor) {
                        lastColor = color
                        containers.forEach { updateColor(it, color) }
                        StateController.onTextColorChanged(color)
                    }
                }
                // 2. LYRIC_SHORT 状态下刷新限宽
                if (StateController.currentState == statusbar.lyric.thirteen.core.LyricState.LYRIC_SHORT) {
                    containers.forEach { applyLimitedWidth(it) }
                }
                // 3. 互斥绑定：时钟GONE则通知图标INVISIBLE（防系统恢复）
                val clockGone = containers.firstOrNull()?.clock?.visibility == android.view.View.GONE
                if (clockGone) {
                    notificationIconArea?.let {
                        if (it.visibility == android.view.View.VISIBLE) {
                            it.visibility = android.view.View.INVISIBLE
                        }
                    }
                }
                // 10秒超时（UI自治）
                mainHandler.removeMessages(TIMEOUT_RESTORE)
                mainHandler.sendEmptyMessageDelayed(TIMEOUT_RESTORE, 10000L)
            }
            // 锁屏歌词同步更新（不另注册回调，防止覆盖本回调）
            LockscreenLyricHook.updateLyric(lyric)
        }

        StateController.setOnSecondaryUpdatedCallback { secondary ->
            mainHandler.post { updateSecondaryText(secondary) }
        }

        StateController.setOnIconUpdatedCallback { icon ->
            mainHandler.post {
                updateIcon(icon)
                // 锁屏图标同步更新（goMainThread 内部保护，此处已在主线程无碍）
                LockscreenLyricHook.updateAppIcon(icon)
            }
        }

        StateController.setOnClockColorChangedCallback { color ->
            mainHandler.post {
                lastColor = color
                containers.forEach { updateColor(it, color) }
            }
        }

        // HIDE_TO_CUTOUT 임시방안 비활성화：
        // tryHideIslandView()가 island container width=0으로 설정하여 투명하게 만듦.
        // 7-F에서 올바른 구현 전까지 island는 시스템이 자연스럽게 렌더링하도록 둠.
        // StateController.setOnIslandHideToCutoutCallback { ... } // 7-F 구현 시 활성화

        StateController.setOnTitleChangedCallback { title ->
            mainHandler.post { showTitle(title) }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 状态渲染（由 StateController 回调驱动，本类不做状态判断）
    // ══════════════════════════════════════════════════════════

    private fun applyState(state: LyricState) {
        YLog.debug(msg = "StatusBarHook: applyState($state)")
        when (state) {
            LyricState.NORMAL         -> applyNormal()
            LyricState.LYRIC          -> applyLyric()
            LyricState.LYRIC_SHORT    -> applyLyricShort()
            LyricState.SYSTEM_RESTORE -> applySystemRestore()
            LyricState.MANUAL_CLOCK   -> applyManualClock()
            LyricState.MANUAL_SIGNAL  -> applyManualSignal()
        }
    }

    private fun applyNormal() {
        mainHandler.removeMessages(TIMEOUT_RESTORE)
        containers.forEach { c ->
            c.layout.post {
                restoreSlots()
                restoreSignalViews()
                restoreNotificationArea()   // 退出歌词主显示，恢复通知图标区
                c.layout.visibility = View.GONE
                c.clock.visibility = View.VISIBLE
                c.clock.setOnClickListener(null)
                c.lyricView.setOnClickListener(null)
                updateCustomClockVisibility(c, false)
            }
        }
    }

    private fun applyLyric() {
        // 歌词与 logo 俱生俱亡：进入歌词状态时恢复 icon（若有缓存）
        updateIcon(StateController.currentIconBitmap)
        containers.forEach { c ->
            c.layout.post {
                // Bug4修复：从 LYRIC_SHORT 切回 LYRIC 时，signalViews 可能仍是 GONE，
                // LYRIC 状态不应隐藏信号，这里显式恢复，确保状态正确。
                restoreSignalViews()

                val lp = c.lyricView.layoutParams as? LinearLayout.LayoutParams
                lp?.width = LinearLayout.LayoutParams.WRAP_CONTENT
                c.lyricView.layoutParams = lp

                c.layout.visibility = View.VISIBLE
                c.clock.visibility = View.GONE
                c.clock.setOnClickListener(null)   // 清除 MANUAL_CLOCK 残留的 listener

                // LYRIC 状态：notification_icon_area = INVISIBLE，防短歌词溢出保留占位
                // hook 拦截保证系统不会把它恢复为 VISIBLE
                notificationIconArea?.visibility = View.INVISIBLE

                c.lyricView.setOnClickListener { StateController.onLyricClicked() }
                updateCustomClockVisibility(c, true)
            }
        }
    }

    private fun applyLyricShort() {
        // 歌词与 logo 俱生俱亡：LYRIC_SHORT 也是歌词状态，恢复 icon
        updateIcon(StateController.currentIconBitmap)
        containers.forEach { c ->
            c.layout.post {
                hideSignalViews()
                // LYRIC_SHORT：与 LYRIC 保持一致，通知图标区 INVISIBLE
                notificationIconArea?.visibility = View.INVISIBLE
                c.clock.setOnClickListener(null)   // 清除 MANUAL_CLOCK 残留的 listener
                applyLimitedWidth(c)
                c.layout.visibility = View.VISIBLE
                c.clock.visibility = View.GONE
                c.lyricView.setOnClickListener { StateController.onLyricClicked() }
                updateCustomClockVisibility(c, true)
            }
        }
    }

    /**
     * SYSTEM_RESTORE：必须先 restoreAllSlots()
     */
    private fun applySystemRestore() {
        mainHandler.removeMessages(TIMEOUT_RESTORE)
        containers.forEach { c ->
            c.layout.post {
                restoreSlots()              // 必须第一步！
                restoreSignalViews()
                restoreNotificationArea()   // 退出歌词主显示，恢复通知图标区
                c.layout.visibility = View.GONE
                c.clock.visibility = View.VISIBLE
                c.clock.setOnClickListener(null)
                c.lyricView.setOnClickListener(null)
                updateCustomClockVisibility(c, false)
            }
        }
    }

    /**
     * MANUAL_CLOCK：用户切回原始时钟。
     * 关键：customClockView 必须同步隐藏，防双时钟！铁律：无论 showCustomClock 开关状态。
     */
    private fun applyManualClock() {
        mainHandler.removeMessages(TIMEOUT_RESTORE)
        containers.forEach { c ->
            c.layout.post {
                restoreSlots()
                restoreSignalViews()
                restoreNotificationArea()   // 退出歌词主显示，恢复通知图标区
                c.layout.visibility = View.GONE
                c.clock.visibility = View.VISIBLE
                c.clock.setOnClickListener { StateController.onOriginalClockClicked() }
                c.lyricView.setOnClickListener(null)
                // 必须隐藏新时钟！防双时钟（无论 showCustomClock 开关，铁律）
                updateCustomClockVisibility(c, false)
            }
        }
    }

    /**
     * MANUAL_SIGNAL：用户切回信号区，左侧歌词保留。
     * 手册布局：[图标 歌词←→] ● [系统图标（可点击退出）] [信号] [电池]
     * ⚠️ notificationIconArea 保持 INVISIBLE：
     *    手册第4条「INVISIBLE」只列在 LYRIC，MANUAL_SIGNAL 布局里无通知图标区占位符。
     *    信号恢复已提供足够视觉分割，不需要通知图标区。
     * ⚠️ 不调用 restoreSlots()：仍是歌词显示状态，slot 过滤应保持。
     */
    private fun applyManualSignal() {
        containers.forEach { c ->
            c.layout.post {
                restoreSignalViews()    // 信号恢复，不含 notificationIconArea
                c.layout.visibility = View.VISIBLE
                c.clock.visibility = View.GONE
                c.lyricView.setOnClickListener { StateController.onLyricClicked() }
                updateCustomClockVisibility(c, false)
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 新时钟联动（showCustomClock 开关控制）
    // ══════════════════════════════════════════════════════════

    /**
     * 新时钟可见性控制。
     * showCustomClock 为 false（默认）时始终不显示。
     * 为 true 时根据 wantVisible 决定：仅 LYRIC/LYRIC_SHORT/MANUAL_SIGNAL 状态可见。
     */
    private fun updateCustomClockVisibility(container: LyricContainer, wantVisible: Boolean) {
        val show = StateController.showCustomClock && wantVisible
        container.customClockView?.post {
            container.customClockView.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    // ══════════════════════════════════════════════════════════
    // 限宽计算（LYRIC_SHORT 专用）
    // ══════════════════════════════════════════════════════════

    private fun applyLimitedWidth(container: LyricContainer) {
        val islandLeft = StateController.currentIslandLeft
        if (islandLeft <= 0) return
        val density = container.lyricView.resources.displayMetrics.density
        val gap = (20 * density).toInt()  // 加大间距，确保歌词不戳入岛区域

        // V11修复：HyperOS3 的 islandLeft 是屏幕绝对坐标（从屏幕左边缘起）。
        // layout 的 getLocationOnScreen 返回的也是屏幕绝对坐标，两者在同一坐标系下直接相减即可。
        // 原先用 loc[0]（layout 在屏幕上的 X）作为起点是对的，但 islandLeft 本身已经是屏幕坐标，
        // 所以 maxWidth = islandLeft - loc[0] - gap。
        // 问题在于 layout.post 里 layout 尚未完成布局时 loc[0] 可能为 0，导致 maxWidth 过大。
        // 改为直接用 layout 父容器（target）的屏幕 X 坐标，target 是 LinearLayout（时钟所在行），
        // 它在 onAttachedToWindow 后就已完成测量，坐标稳定。
        val loc = IntArray(2)
        container.target.getLocationOnScreen(loc)
        val containerScreenLeft = loc[0]
        val maxWidth = (islandLeft - containerScreenLeft - gap).coerceAtLeast(StateController.getScreenWidth() / 4)

        // applyLimitedWidth 在 layout.post{} 里被调用，此时 View 已 attach，直接同步设置。
        // 不再额外 post，否则变成 post 套 post，限宽仍然延迟生效。
        val lp = (container.lyricView.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(maxWidth, LinearLayout.LayoutParams.MATCH_PARENT)
        lp.width = maxWidth
        container.lyricView.layoutParams = lp
        YLog.debug(msg = "StatusBarHook: applyLimitedWidth maxWidth=$maxWidth islandLeft=$islandLeft containerScreenLeft=$containerScreenLeft")
    }

    // ══════════════════════════════════════════════════════════
    // 数据更新
    // ══════════════════════════════════════════════════════════

    private fun updateLyricText(lyric: String, delay: Long) {
        containers.forEach { c ->
            c.layout.post {
                // BUG-4 修复：c.lyricView.width 在布局完成前为 0，
                // 回退到父容器宽度（layout.width），再不行取屏幕宽度的 1/3 作为保底。
                val viewWidth = when {
                    c.lyricView.width > 0 -> c.lyricView.width.toFloat()
                    c.layout.width > 0    -> c.layout.width.toFloat()
                    else                  -> (StateController.getScreenWidth() / 3).toFloat()
                }
                // 用 currentView 的 paint 量取文本宽度（字号与 lyricView 一致）
                val textLen = (c.lyricView.currentView as? LyricTextView)
                    ?.paint?.measureText(lyric) ?: 0f
                // 严格顺序：measureText → calcScrollSpeed → setScrollSpeed → setText
                c.lyricView.setScrollSpeed(calcScrollSpeed(textLen, viewWidth, delay))
                c.lyricView.setText(lyric)
            }
        }
    }

    private fun updateSecondaryText(secondary: String?) {
        // 7-B 双排实现后填充
    }

    /**
     * 悬浮歌名弹窗。
     * 由 StateController.setOnTitleChangedCallback 驱动，严格遵循手册 TitleDialog 规范：
     * - 仅在 titleSwitch=true 时显示
     * - title==null 时隐藏（onMusicStopped 会传 null）
     * - 首次调用时懒初始化 TitleDialog（需要 Context，取自第一个 container 的 clock.context）
     */
    private fun showTitle(title: String?) {
        if (!StateController.titleSwitch) return
        val ctx = containers.firstOrNull()?.clock?.context ?: return
        // 调试日志：追踪异常弹窗触发路径
        YLog.debug(msg = "StatusBarHook: showTitle title=${title?.take(20)} state=${StateController.currentState} userForcedClock=${StateController.userForcedClock}")
        if (titleDialog == null) {
            titleDialog = TitleDialog(ctx)
        }
        if (title.isNullOrEmpty()) {
            titleDialog?.hideTitle()
        } else {
            titleDialog?.showTitle(title)
        }
    }

    private fun updateIcon(icon: Bitmap?) {
        containers.forEach { c ->
            c.layout.post {
                if (icon != null && StateController.showLyricIcon) {
                    c.iconView.setImageBitmap(icon)
                    c.iconView.clearColorFilter()  // Bug6修复：保留图标原始颜色，不强制染色
                    c.iconView.visibility = View.VISIBLE
                } else {
                    c.iconView.visibility = View.GONE
                }
            }
        }
    }

    private fun updateColor(container: LyricContainer, color: Int) {
        container.layout.post {
            container.lyricView.setTextColor(color)
            // Bug6修复：图标不跟随文字颜色染色，保留原始彩色图标
            // container.iconView.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            container.customClockView?.setTextColor(color)
        }
    }

    // ══════════════════════════════════════════════════════════
    // 容器初始化
    // ══════════════════════════════════════════════════════════

    private fun initClockView(view: TextView) {
        val idName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() ?: return
        if (idName != "clock" && idName != "pad_clock") return
        val parent = view.parent as? LinearLayout ?: return
        parent.gravity = Gravity.CENTER_VERTICAL
        // 反色修复：直接从 batteryView 读取颜色，batteryView 颜色与右侧信号图标一致，
        // 永远是正确的状态栏颜色，避免 DarkIconDispatcher 时机问题导致颜色不同步。
        val batteryColor = batteryView?.currentTextColor ?: 0
        if (batteryColor != 0) lastColor = batteryColor
        addContainerIfAbsent(view, parent)
    }

    private fun addContainerIfAbsent(clock: TextView, target: ViewGroup) {
        if (containers.any { it.target == target }) return
        val ctx = clock.context

        val iconView = ImageView(ctx).apply {
            visibility = View.GONE
            // Bug6修复：不初始化染色，保留图标原始颜色
        }
        val lyricView = LyricSwitchView(ctx).apply {
            setTypeface(clock.typeface)
            setTextColor(lastColor)
            setSingleLine(true)
            setMaxLines(1)
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            addView(iconView)
            addView(lyricView)
            visibility = View.GONE
        }

        // customClockView：挖孔右侧自定义时钟
        // 格式从 MiuiClock 反射读取：mFormat → mDescFormat → mPattern → 兜底 HH:mm
        // ⚠️ 时机：MiuiClock 构造函数 afterHook 时格式字段可能未初始化，
        //    因此用 GONE+空文字先占位，真正格式在首次 clockUpdateRunnable.run() 时实时读。
        val customClockView = android.widget.TextView(ctx).apply {
            typeface = clock.typeface
            textSize = clock.textSize / ctx.resources.displayMetrics.scaledDensity
            setTextColor(lastColor)
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            // 点击新时钟 → MANUAL_SIGNAL（仅 LYRIC/LYRIC_SHORT 状态下生效，SC 内部判断）
            setOnClickListener { StateController.onNewClockClicked() }
        }

        // 每 60 秒更新时钟文字；格式在每次 run() 时实时反射，确保读到已初始化的值
        val clockUpdateRunnable = object : Runnable {
            override fun run() {
                val fmt = listOf("mFormat", "mDescFormat", "mPattern").firstNotNullOfOrNull { name ->
                    runCatching {
                        clock.javaClass.getDeclaredField(name).also { it.isAccessible = true }
                            .get(clock) as? String
                    }.getOrNull()
                } ?: "HH:mm"
                runCatching {
                    val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault())
                    customClockView.text = sdf.format(java.util.Date())
                }
                customClockView.postDelayed(this, 60_000L)
            }
        }
        customClockView.post { clockUpdateRunnable.run() }

        val container = LyricContainer(clock, target, layout, lyricView, iconView, customClockView)

        // detach 时从列表 remove，防内存泄漏
        layout.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                containers.remove(container)
                customClockView.removeCallbacks(clockUpdateRunnable)
                YLog.debug(msg = "StatusBarHook: container detached & removed")
            }
        })

        containers.add(container)
        target.post {
            runCatching {
                (layout.parent as? ViewGroup)?.removeView(layout)
                (customClockView.parent as? ViewGroup)?.removeView(customClockView)
                target.addView(layout, 0)
                // customClockView 插在 layout 旁边（紧随 layout 之后）
                val insertIdx = (target.indexOfChild(layout) + 1).coerceAtMost(target.childCount)
                target.addView(customClockView, insertIdx)
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 工具方法
    // ══════════════════════════════════════════════════════════

    /**
     * 滚动速度计算（7-B/7-D 统一公式）
     * 严格顺序：measureText → calcScrollSpeed → setScrollSpeed → setText
     */
    fun calcScrollSpeed(textLengthPx: Float, viewWidthPx: Float, delayMs: Long): Float {
        val overflow = textLengthPx - viewWidthPx
        if (overflow <= 0f) return 0f
        if (delayMs <= 0L) return 4f
        val frames = (delayMs / 16f).coerceAtLeast(1f)
        return (overflow / frames).coerceIn(0.5f, 20f)
    }

    private fun hideSignalViews() {
        signalViews.forEach { v ->
            v.post { v.visibility = View.GONE }
        }
        batteryView?.post { batteryView?.visibility = View.VISIBLE }
        YLog.debug(msg = "StatusBarHook: hideSignalViews (${signalViews.size} views hidden, battery kept)")
    }

    /**
     * 恢复信号类 View（mobile/wifi/volte 等），不含 notificationIconArea。
     * MANUAL_SIGNAL / NORMAL / SYSTEM_RESTORE / MANUAL_CLOCK 均可调用。
     */
    private fun restoreSignalViews() {
        signalViews.forEach { v ->
            v.post { v.visibility = View.VISIBLE }
        }
        batteryView?.post { batteryView?.visibility = View.VISIBLE }
        YLog.debug(msg = "StatusBarHook: restoreSignalViews")
    }

    /**
     * 恢复 notificationIconArea 为 VISIBLE。
     * 只在真正退出「歌词主显示」状态时调用：
     *   NORMAL / SYSTEM_RESTORE / MANUAL_CLOCK
     * ⚠️ MANUAL_SIGNAL 不调此方法：手册布局里 MANUAL_SIGNAL 无通知图标区。
     *    但手册第4条「INVISIBLE 防短歌词溢出」只针对 LYRIC，
     *    MANUAL_SIGNAL 已恢复信号区，布局已有足够空间，保持 INVISIBLE 即可。
     *    → 实际上手册 MANUAL_SIGNAL 布局里没有通知图标区占位符，应保持 INVISIBLE。
     */
    private fun restoreNotificationArea() {
        notificationIconArea?.post { notificationIconArea?.visibility = View.VISIBLE }
        YLog.debug(msg = "StatusBarHook: restoreNotificationArea")
    }

    /**
     * MiuiCollapsedStatusBarFragment.hideNotificationIconArea() 를 반사(reflection)로 호출.
     * notification_icon_area View를 직접 INVISIBLE하면 그 안의 NotificationIconContainer,
     * 즉 슈퍼 아일랜드 호스트 컨테이너까지 숨겨져 아일랜드가 투명해짐.
     * 시스템 메서드를 통해 호출하면 내부에서 아일랜드 컨테이너를 보존하면서 통지 아이콘만 숨김.
     */
    private fun hideNotificationIconAreaSafe() {
        runCatching {
            val frag = collapsedStatusBarFragment ?: return
            frag.javaClass.getDeclaredMethod("hideNotificationIconArea")
                .also { it.isAccessible = true }
                .invoke(frag)
            YLog.debug(msg = "StatusBarHook: hideNotificationIconArea called via fragment")
        }.onFailure {
            YLog.warn(msg = "StatusBarHook: hideNotificationIconArea failed: ${it.message}")
        }
    }

    private fun restoreSlots() {
        // 进入 SYSTEM_RESTORE / MANUAL_CLOCK / NORMAL 前必须恢复所有 slot，
        // 让系统「从左依次隐藏状态图标」逻辑能正常触发。
        runCatching { IconFilterHook.restoreAllSlots() }
            .onFailure { YLog.debug(msg = "StatusBarHook: restoreSlots failed: ${it.message}") }
    }

    /**
     * HIDE_TO_CUTOUT：将岛容器宽度设为 0，视觉上收缩到挖孔。
     *
     * 由于 miui.systemui.dynamicisland.xxx 类在动态 DEX 加载（非主 ClassLoader），
     * 此处优先通过探针 D 策略（遍历字段找 View）查找岛容器 View。
     * 找不到时降级 GONE，并输出诊断日志（日志用于排查 ClassLoader 问题）。
     *
     * 后续如实现 hookDynamicClassLoaders 方案，只需在回调里赋值 islandContainerView。
     */
    private fun tryHideIslandView() {
        // 如果已缓存过岛容器，直接用
        val cached = islandContainerView
        if (cached != null && cached.isAttachedToWindow) {
            collapseIslandView(cached)
            return
        }
        // 探针 D：遍历 packageParam.appContext 里的根 Window 找岛容器
        runCatching {
            val wm = packageParam.appContext
                ?.getSystemService(android.content.Context.WINDOW_SERVICE)
                as? android.view.WindowManager ?: return
            // 从 WindowManager 全局视图遍历寻找岛容器（反射获取 mViews）
            val wmGlobal = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val views = wmGlobal.javaClass.getDeclaredField("mViews").also {
                it.isAccessible = true
            }.get(wmGlobal) as? ArrayList<View> ?: return

            for (rootView in views) {
                val found = findIslandContainerIn(rootView as? ViewGroup ?: continue)
                if (found != null) {
                    islandContainerView = found
                    collapseIslandView(found)
                    YLog.debug(msg = "StatusBarHook: islandContainerView found & collapsed")
                    return
                }
            }
            YLog.debug(msg = "StatusBarHook: islandContainerView not found – dynamic ClassLoader issue suspected")
        }.onFailure {
            YLog.debug(msg = "StatusBarHook: tryHideIslandView exception: ${it.message}")
        }
    }

    /** 缓存找到的岛容器 View，避免每次遍历 */
    private var islandContainerView: View? = null

    /** 在 ViewGroup 树中按候选字段名查找岛容器 View */
    private fun findIslandContainerIn(root: ViewGroup): View? {
        val candidateNames = listOf(
            "mIslandContainer", "mIslandView", "mDynamicIsland",
            "mIslandContainerView", "islandView", "islandContainer"
        )
        // 先按字段名反射
        for (fieldName in candidateNames) {
            val v = runCatching {
                root.javaClass.getDeclaredField(fieldName).also { it.isAccessible = true }
                    .get(root) as? View
            }.getOrNull()
            if (v != null) return v
        }
        // 递归子 View
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as? ViewGroup ?: continue
            val className = child.javaClass.name.lowercase()
            if ("island" in className || "dynamic" in className) return child
            findIslandContainerIn(child)?.let { return it }
        }
        return null
    }

    private fun collapseIslandView(view: View) {
        view.post {
            runCatching {
                val lp = view.layoutParams
                if (lp != null) {
                    lp.width = 0
                    view.layoutParams = lp
                    view.requestLayout()
                    YLog.debug(msg = "StatusBarHook: island collapsed via layoutParams.width=0")
                } else {
                    // 降级方案
                    view.visibility = View.GONE
                    YLog.debug(msg = "StatusBarHook: island collapsed via GONE (lp null)")
                }
            }.onFailure {
                runCatching { view.visibility = View.GONE }
                YLog.debug(msg = "StatusBarHook: island collapse fallback GONE: ${it.message}")
            }
        }
    }
}
