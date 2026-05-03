package statusbar.lyric.thirteen.hook

import android.app.AndroidAppHelper
import android.content.Context
import android.content.SharedPreferences
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam

/**
 * 增益功能 Hook 基类
 * 提供 prefs 封装和统一的 hook() 调用入口
 */
abstract class BaseExtraHook {
    abstract val key: String
    abstract fun register()

    lateinit var packageParam: PackageParam

    /**
     * 封装 SharedPreferences 读取。
     * 修复：Hook 注册阶段 AndroidAppHelper.currentApplication() 可能为 null。
     * 改为每次安全获取，null 时使用 packageParam.appContext 兜底。
     *
     * 注意：方法名改为 fetchPrefs() 以避免与 val prefs 的 JVM getter (getPrefs) 冲突。
     */
    @Suppress("unused")
    protected fun fetchPrefs(): SharedPreferences? = runCatching {
        (AndroidAppHelper.currentApplication()
            ?: packageParam.appContext)
            ?.getSharedPreferences("COMPOSE_CONFIG", Context.MODE_PRIVATE)
    }.getOrNull()

    /** 兼容旧代码的 prefs 属性，安全返回（不抛异常） */
    protected val prefs: SharedPreferences
        get() = runCatching {
            (AndroidAppHelper.currentApplication()
                ?: packageParam.appContext)
                ?.getSharedPreferences("COMPOSE_CONFIG", Context.MODE_PRIVATE)
        }.getOrNull() ?: run {
            YLog.debug("BaseExtraHook: prefs unavailable, using no-op")
            // 返回一个可安全读取、始终返回默认值的空实现
            packageParam.appContext
                ?.getSharedPreferences("COMPOSE_CONFIG_EMPTY_FALLBACK", Context.MODE_PRIVATE)
                ?: throw IllegalStateException("Cannot get SharedPreferences")
        }

    /**
     * 外部调用入口
     */
    fun hook() {
        try {
            register()
            YLog.debug("${javaClass.simpleName} registered")
        } catch (e: Exception) {
            YLog.error("Hook ${javaClass.simpleName} failed", e)
        }
    }
}
