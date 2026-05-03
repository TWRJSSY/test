package statusbar.lyric.thirteen.hook.extra

import com.highcapable.yukihookapi.hook.log.YLog
import statusbar.lyric.thirteen.hook.BaseExtraHook

object FuckMusicHeadsUpHook : BaseExtraHook() {

    override val key: String = "extra_kill_music_headsup"

    private val musicAppsWhitelist = setOf(
        "com.tencent.qqmusic", "com.netease.cloudmusic", "kugou.android",
        "cn.kuwo.player", "com.miui.player", "com.sdiread.pulamsi.news",
        "com.kugou.android.lite", "com.tencent.qqmusiccar", "com.tencent.blackbird",
        "com.kugou.android.business", "com.netease.cloudmusic.lite",
        "com.taihe.music.play", "com.ximalaya.ting.android", "com.duomi.android",
        "com.spotify.music", "com.apple.android.music", "com.google.android.apps.youtube.music",
        "com.amazon.mp3", "com.deezer.android", "com.pandora.android",
        "com.tidal.vortex", "com.soundcloud.android", "com.napster.android", "com.vibe.android"
    )

    override fun register() {
        val isEnabled = prefs.getBoolean(key, false)
        if (!isEnabled) return

        with(packageParam) {
            try {
                "com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl".hook {
                    injectMember {
                        method { name = "shouldHeadsUp" }
                        afterHook { handleShouldHeadsUp(args, this) }
                    }
                }
                YLog.debug("Hooked AOSP NotificationInterruptStateProviderImpl")
                return
            } catch (e: Throwable) {
                YLog.debug("AOSP path failed, trying HyperOS path")
            }

            try {
                "com.android.systemui.statusbar.phone.MiuiHeadsUpManager".hook {
                    injectMember {
                        method { name = "shouldHeadsUp" }
                        afterHook { handleShouldHeadsUp(args, this) }
                    }
                }
                YLog.debug("Hooked MiuiHeadsUpManager")
            } catch (e: Throwable) {
                YLog.debug("HyperOS path also failed: ${e.message}")
            }
        }
    }

    private fun handleShouldHeadsUp(args: Array<Any?>, hookScope: Any) {
        val entry = args.firstOrNull() ?: return
        try {
            val sbn = entry.javaClass.getMethod("getSbn").invoke(entry)
            val packageName = sbn?.javaClass?.getMethod("getPackageName")?.invoke(sbn) as? String
            if (packageName != null && packageName in musicAppsWhitelist) {
                hookScope.javaClass.getMethod("setResult", Any::class.java).invoke(hookScope, false)
            }
        } catch (e: Throwable) { /* ignore */ }
    }
}
