package statusbar.lyric.thirteen.manager

import android.app.AndroidAppHelper
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import statusbar.lyric.thirteen.core.StateController

object NotificationTracker {

    private val supportedApps = setOf(
        "com.tencent.qqmusic", "com.netease.cloudmusic",
        "com.kugou.android", "com.kugou.android.lite",
        "com.kugou.android.lite.support", "com.kugou.android.support",
        "cn.kuwo.player", "com.miui.player",
        "com.meizu.media.music", "com.heytap.music", "com.oppo.music",
        "com.hihonor.cloudmusic", "com.huawei.music",
        "com.luna.music", "com.salt.music",
        "com.maxmpz.audioplayer", "com.r.rplayer",
        "com.lalilu.lmusic", "com.mimicry.mymusic",
        "com.xuncorp.qinalt.music", "com.xuncorp.suvine.music",
        "cn.toside.music.mobile", "cn.toside.music.mobile.lyric",
        "cn.aqzscn.stream_music"
    )

    private var currentSbn: StatusBarNotification? = null

    fun init(packageParam: PackageParam) {
        packageParam.apply {
            try {
                hookNotificationListener(this, "com.android.systemui.statusbar.notification.MiuiNotificationListener")
                YLog.debug("Hooked MiuiNotificationListener")
                return
            } catch (e: Throwable) {
                YLog.debug("MiuiNotificationListener not found, trying fallback")
            }
            try {
                hookNotificationListener(this, "com.android.systemui.statusbar.notification.NotificationListener")
                YLog.debug("Hooked NotificationListener")
            } catch (e: Throwable) {
                YLog.debug("Both notification listener classes failed: ${e.message}")
            }
        }
    }

    private fun hookNotificationListener(packageParam: PackageParam, className: String) {
        with(packageParam) {
            className.hook {
                injectMember {
                    // 反编译确认：onNotificationPosted(SBN, RankingMap) 两个参数
                    method {
                        name = "onNotificationPosted"
                        paramCount(2)
                    }
                    afterHook {
                        val sbn = args[0] as? StatusBarNotification ?: return@afterHook
                        if (sbn.packageName !in supportedApps) return@afterHook
                        val context = AndroidAppHelper.currentApplication() ?: return@afterHook
                        val notification = sbn.notification
                        currentSbn = sbn
                        val title = notification.extras?.getString(Notification.EXTRA_TITLE)
                        StateController.onTitleReceived(title)
                        val icon = notification.smallIcon ?: return@afterHook
                        val drawable = icon.loadDrawable(context) ?: return@afterHook
                        val bitmap = renderIcon(drawable, StateController.currentTextColor)
                        StateController.onIconReceived(bitmap, sbn.packageName)
                    }
                }
                injectMember {
                    // 反编译确认：onNotificationRemoved 有两个重载，都 paramCount(2) 或 paramCount(3)
                    // 用 paramCount(2) 匹配主要的那个
                    method {
                        name = "onNotificationRemoved"
                        paramCount(2)
                    }
                    afterHook {
                        val sbn = args[0] as? StatusBarNotification ?: return@afterHook
                        if (sbn.packageName in supportedApps) {
                            StateController.onNotificationRemoved(sbn.packageName)
                            if (currentSbn?.packageName == sbn.packageName) currentSbn = null
                        }
                    }
                }
            }
        }
    }

    fun rerenderCurrentIcon(newColor: Int) {
        val sbn = currentSbn ?: return
        val context = AndroidAppHelper.currentApplication() ?: return
        val icon = sbn.notification.smallIcon ?: return
        val drawable = icon.loadDrawable(context) ?: return
        val bitmap = renderIcon(drawable, newColor)
        StateController.onIconReceived(bitmap, sbn.packageName)
    }

    private fun renderIcon(drawable: Drawable, textColor: Int): Bitmap {
        val size = 48
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.colorFilter = PorterDuffColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        drawable.draw(canvas)
        return bitmap
    }
}
