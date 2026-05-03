package statusbar.lyric.thirteen.hook

import android.graphics.Rect
import com.highcapable.yukihookapi.hook.log.YLog
import statusbar.lyric.thirteen.core.StateController

/**
 * IslandHook — 超级岛感知器（fix5 最终方案）
 *
 * 架构：感知层，只上报 StateController，不做任何 UI 操作。
 *
 * Hook 目标：IslandMonitor$RealContainerIslandMonitor$stateListener$1.onIslandSizeChanged(Rect, Z) after
 * - Rect 非零 → 岛出现，width=right-left，islandLeft=left
 * - Rect 全零 → 岛消失
 * 真机验证：Rect(438,30-761,132) → width=323, islandLeft=438, screen=1200
 *
 * 废弃方案（禁止再用）：
 * - onIslandStatusChanged(Z,Z,Z)：无宽度
 * - getIslandWidth()/setIslandWidth()：时序不可靠，返回 -1
 */
object IslandHook : BaseHook() {

    private const val STATE_LISTENER_CLASS =
        "com.android.systemui.statusbar.IslandMonitor\$RealContainerIslandMonitor\$stateListener\$1"

    override fun init() {
        with(packageParam) {
            runCatching {
                STATE_LISTENER_CLASS.hook {
                    injectMember {
                        method {
                            name = "onIslandSizeChanged"
                            param(Rect::class.java, Boolean::class.java)
                        }
                        afterHook {
                            if (StateController.isLandscape) return@afterHook
                            val rect = args[0] as? Rect ?: return@afterHook

                            if (rect.left == 0 && rect.right == 0 && rect.top == 0 && rect.bottom == 0) {
                                YLog.debug(msg = "IslandHook: hidden")
                                StateController.onIslandHidden()
                                return@afterHook
                            }

                            val islandWidth = rect.right - rect.left
                            val islandLeft  = rect.left
                            val screenWidth = packageParam.appContext
                                ?.resources?.displayMetrics?.widthPixels ?: 1080

                            // 获取发出超级岛的 App 包名：
                            // stateListener$1 是匿名内部类，持有外部 RealContainerIslandMonitor 实例。
                            // RealContainerIslandMonitor 里有 mPackageName / packageName / mPkg 等字段记录当前岛的来源 App。
                            // 候选字段名从 SystemUI 反编译整理，逐个尝试，找不到时降级用空串（保守走 SYSTEM_RESTORE）。
                            val islandPkg = resolveIslandPackage(instance) ?: run {
                                YLog.debug(msg = "IslandHook: islandPkg not found, falling back to empty (SYSTEM_RESTORE)")
                                ""
                            }

                            YLog.debug(msg = "IslandHook: shown rect=$rect w=$islandWidth left=$islandLeft screen=$screenWidth pkg=$islandPkg")
                            StateController.onIslandShown(
                                islandWidth = islandWidth,
                                islandLeft  = islandLeft,
                                packageName = islandPkg,
                                screenWidth = screenWidth
                            )
                        }
                    }
                }
            }.onFailure {
                YLog.debug(msg = "IslandHook: hook failed: ${it.message}")
            }
        }
        YLog.debug(msg = "IslandHook loaded")
    }

    /**
     * 从 stateListener$1（匿名内部类）反射出外部类 RealContainerIslandMonitor 实例，
     * 再从中读取当前超级岛发起的 App 包名。
     *
     * 字段候选（从 SystemUI v16.03.251211 反编译整理）：
     *   this$0 → 外部类引用（匿名内部类标准字段名）
     *   外部类字段：mPackageName / mPkg / packageName / mNotificationKey（含包名前缀）
     *
     * 找不到时返回 null，由调用方降级处理。
     */
    private fun resolveIslandPackage(listenerInstance: Any?): String? {
        listenerInstance ?: return null
        // 1. 拿到外部类实例（this$0 = RealContainerIslandMonitor）
        val outerInstance = runCatching {
            listenerInstance.javaClass.getDeclaredField("this\$0")
                .also { it.isAccessible = true }
                .get(listenerInstance)
        }.getOrNull() ?: return null

        // 2. 反编译确认：RealContainerIslandMonitor 本身没有 packageName 字段。
        //    包名存储在其持有的 islandController (StatusBarIslandControllerImpl) 的
        //    islandMap (java.util.Map) 字段里，key 就是发岛 App 的包名。
        //    islandMap 字段名在 SystemUI v16.03.251211 中未被 R8 混淆，可直接访问。
        val islandController = runCatching {
            outerInstance.javaClass.getDeclaredField("islandController")
                .also { it.isAccessible = true }
                .get(outerInstance)
        }.getOrNull()

        if (islandController != null) {
            val islandMap = runCatching {
                islandController.javaClass.getDeclaredField("islandMap")
                    .also { it.isAccessible = true }
                    .get(islandController)
            }.getOrNull()

            if (islandMap is java.util.Map<*, *> && !islandMap.isEmpty) {
                // islandMap 的 key 是通知 key，格式：userid|packageName|id|tag|userid
                // 需提取第二段才是真正的包名
                val rawKey = islandMap.keySet().filterIsInstance<String>().firstOrNull()
                if (!rawKey.isNullOrEmpty()) {
                    // key 格式：userid|packageName|id|tag|userid，取第二段
                    val parts = rawKey.split("|")
                    val pkg = parts.getOrNull(1)?.takeIf { it.contains(".") }
                    YLog.debug(msg = "IslandHook: islandMap rawKey=$rawKey parts=$parts extracted=$pkg")
                    if (!pkg.isNullOrEmpty()) {
                        return pkg
                    }
                }
            }
        }

        return null
    }
}
