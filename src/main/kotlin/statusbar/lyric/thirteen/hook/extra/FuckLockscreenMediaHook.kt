package statusbar.lyric.thirteen.hook.extra

import com.highcapable.yukihookapi.hook.log.YLog
import statusbar.lyric.thirteen.hook.BaseExtraHook

object FuckLockscreenMediaHook : BaseExtraHook() {

    override val key: String = "extra_kill_lockscreen_media"

    override fun register() {
        val isEnabled = prefs.getBoolean(key, false)
        if (!isEnabled) return

        with(packageParam) {
            "com.android.systemui.media.controls.ui.controller.MediaHierarchyManager".hook {
                injectMember {
                    method { name = "updateDesiredLocation" }
                    beforeHook {
                        val desiredLocation = args[0] as? Int
                        if (desiredLocation == 0) {
                            args[0] = -1
                        }
                    }
                }
            }
        }
        YLog.debug("registered")
    }
}
