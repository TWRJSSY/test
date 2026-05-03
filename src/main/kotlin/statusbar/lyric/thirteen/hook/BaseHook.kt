package statusbar.lyric.thirteen.hook

import com.highcapable.yukihookapi.hook.param.PackageParam

/**
 * Hook 基类
 * 参照原版 statusbar.lyric.hook.BaseHook，增加 PackageParam 引用
 */
abstract class BaseHook {
    var isInit: Boolean = false
    lateinit var packageParam: PackageParam

    abstract fun init()
    open fun unhook() {}

    /**
     * 外部调用入口，带防重入检查
     */
    fun hook() {
        if (isInit) return
        init()
        isInit = true
    }
}
