package statusbar.lyric.thirteen.hook.extra

import com.highcapable.yukihookapi.hook.log.YLog
import statusbar.lyric.thirteen.hook.BaseExtraHook
import statusbar.lyric.thirteen.hook.StatusBarHook

object IconFilterHook : BaseExtraHook() {

    override val key: String = "extra_kill_media_card"

    private val defaultHideSlots = mutableSetOf(
        "alarm_clock", "vpn", "location", "mute", "nfc", "zen", "bluetooth"
    )
    private val activeHideSlots = defaultHideSlots.toMutableSet()

    fun removeFromHideSlots(slot: String) {
        if (activeHideSlots.remove(slot)) YLog.debug("Slot [$slot] removed from hide list")
    }

    fun resetHideSlots() {
        activeHideSlots.clear()
        activeHideSlots.addAll(defaultHideSlots)
    }

    /**
     * 解除拦截，让系统自己决定 slot 可见性。
     *
     * 修复 BUG-D：原实现无条件 setIconVisibility(slot, true)，会把系统本身
     * 应隐藏的图标（zen/勿扰、mute/静音等）强制显示出来。
     *
     * 正确做法：退出歌词时只清除 isIntercepting 标志，系统下次调用
     * setIconVisibility 时会自然通过（不再被我们拦截），从而恢复正确状态。
     */
    fun restoreAllSlots() {
        isIntercepting = false
        YLog.debug("IconFilterHook: restoreAllSlots – interception cleared, system will self-correct")
    }

    /** 是否处于拦截模式（歌词显示期间为 true） */
    @Volatile
    private var isIntercepting = false

    override fun register() {
        // 运行时读 prefs，确保用户设置生效（Hook 注册时 SharedPreferences 尚未可用）
        val isEnabled = runCatching { prefs.getBoolean(key, true) }.getOrDefault(true)
        if (!isEnabled) {
            YLog.debug("IconFilterHook disabled by user, skipping")
            return
        }
        with(packageParam) {
            // 反编译确认：实现类是 StatusBarIconControllerImpl，不是接口 StatusBarIconController
            "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl".hook {
                injectMember {
                    method {
                        name = "setIconVisibility"
                        param(String::class.java, Boolean::class.java)
                    }
                    beforeHook {
                        val slot = args[0] as? String ?: return@beforeHook
                        // 歌词显示时拦截：启动拦截标志
                        if (isLyricShowing()) {
                            isIntercepting = true
                            if (activeHideSlots.contains(slot)) {
                                args[1] = false
                            }
                        } else if (isIntercepting) {
                            // 歌词刚结束（isIntercepting 还未被 restoreAllSlots 清除时的过渡帧）
                            // 不拦截，让系统正常设值
                        }
                    }
                }
            }
        }
        YLog.debug("IconFilterHook registered")
    }

    private fun isLyricShowing(): Boolean = StatusBarHook.isLyricShowing
}
