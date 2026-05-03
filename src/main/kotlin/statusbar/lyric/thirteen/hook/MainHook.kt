package statusbar.lyric.thirteen.hook

import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import statusbar.lyric.thirteen.hook.extra.FuckLockscreenMediaHook
import statusbar.lyric.thirteen.hook.extra.FuckMusicHeadsUpHook
import statusbar.lyric.thirteen.hook.extra.IconFilterHook
import statusbar.lyric.thirteen.hook.extra.StatusBarIconRuleHook
import statusbar.lyric.thirteen.hook.lockscreen.LockscreenLyricHook
import statusbar.lyric.thirteen.manager.LyricManager
import statusbar.lyric.thirteen.manager.NotificationTracker

@InjectYukiHookWithXposed
object MainHook : IYukiHookXposedInit {

    private const val TAG = "StatusBarLyric"
    private const val TARGET_PACKAGE = "com.android.systemui"
    private const val SELF_PACKAGE = "statusbar.lyric.thirteen"

    override fun onInit() {
        YLog.Configs.tag = TAG
        YLog.Configs.isEnable = true
    }

    override fun onHook() = encase {
        YLog.debug(msg = "===== StatusBarLyric v10 Loading =====")

        // 自身 APP：显示「模块已激活」
        loadApp(SELF_PACKAGE) {
            "statusbar.lyric.thirteen.tools.ActivityTools".hook {
                injectMember {
                    method { name = "isHook" }
                    replaceTo(true)
                }
            }
        }

        loadApp(TARGET_PACKAGE) {
            YLog.debug(msg = "Hooked into: $TARGET_PACKAGE")

            // 感知层初始化（顺序固定）
            tryHook("LyricManager") { LyricManager.init(this) }
            tryHook("NotificationTracker") { NotificationTracker.init(this) }
            tryHook("IslandHook") { IslandHook.packageParam = this; IslandHook.hook() }

            // 执行层初始化
            tryHook("StatusBarHook") { StatusBarHook.packageParam = this; StatusBarHook.hook() }

            // 锁屏歌词 + 增益功能：无条件注册 Hook，由各自 init()/register() 内部读 prefs 决定是否生效
            // 原因：此处处于 Hook 注册阶段，StateController.loadSwitches() 尚未执行（在 Application.attach 后才运行），
            // 直接读 StateController 字段只能得到硬编码默认值，无法反映用户设置。
            tryHook("LockscreenLyricHook") { LockscreenLyricHook.packageParam = this; LockscreenLyricHook.hook() }
            tryHook("StatusBarIconRuleHook") { StatusBarIconRuleHook.packageParam = this; StatusBarIconRuleHook.hook() }
            tryHook("IconFilterHook") { IconFilterHook.packageParam = this; IconFilterHook.hook() }
            tryHook("FuckMusicHeadsUpHook") { FuckMusicHeadsUpHook.packageParam = this; FuckMusicHeadsUpHook.hook() }
            tryHook("FuckLockscreenMediaHook") { FuckLockscreenMediaHook.packageParam = this; FuckLockscreenMediaHook.hook() }
        }

        YLog.debug(msg = "===== Module Loaded =====")
    }

    private inline fun tryHook(name: String, block: () -> Unit) {
        try { block(); YLog.debug(msg = "✓ $name loaded") }
        catch (e: Exception) { YLog.debug(msg = "✗ $name failed: ${e.message}") }
    }
}
