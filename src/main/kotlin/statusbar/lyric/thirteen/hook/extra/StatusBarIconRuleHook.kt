package statusbar.lyric.thirteen.hook.extra

import com.highcapable.yukihookapi.hook.log.YLog
import statusbar.lyric.thirteen.hook.BaseExtraHook

object StatusBarIconRuleHook : BaseExtraHook() {

    override val key: String = "extra_icon_rule"

    private val configurableSlots = listOf(
        "alarm_clock", "vpn", "location", "nfc",
        "sync", "managed_profile", "cast",
        "wifi", "mobile", "battery", "bluetooth", "mute"
    )

    override fun register() {
        val isEnabled = prefs.getBoolean(key, false)
        if (!isEnabled) return

        with(packageParam) {
            try {
                "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl".hook {
                    injectMember {
                        method {
                            name = "setIconVisibility"
                            param(String::class.java, Boolean::class.java)
                        }
                        beforeHook {
                            val slot = args[0] as? String ?: return@beforeHook
                            if (slot in configurableSlots) {
                                when (getSlotRule(slot)) {
                                    1 -> {
                                        args[1] = true
                                        IconFilterHook.removeFromHideSlots(slot)
                                    }
                                    2 -> args[1] = false
                                }
                            }
                        }
                    }
                }
                YLog.debug("registered")
            } catch (e: Throwable) {
                YLog.error("Failed to hook", e)
            }
        }
    }

    private fun getSlotRule(slotName: String): Int = prefs.getInt("icon_rule_$slotName", 0)
}
