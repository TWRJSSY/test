package statusbar.lyric.thirteen.tools

import android.os.Handler
import android.os.Looper
import com.highcapable.yukihookapi.hook.log.YLog

/**
 * 反射工具类
 * 替代原版 statusbar.lyric.tools.Tools 和 statusbar.lyric.tools.LogTools
 * 内部封装 XposedHelpers 的常用反射方法
 */
object ReflectTools {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 反射调用对象方法
     */
    fun callMethod(obj: Any, methodName: String, vararg args: Any?): Any? {
        return try {
            val method = obj.javaClass.methods.firstOrNull { it.name == methodName }
                ?: obj.javaClass.declaredMethods.firstOrNull { it.name == methodName }
            method?.isAccessible = true
            method?.invoke(obj, *args)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 反射获取对象字段值
     */
    fun getObjectField(obj: Any, fieldName: String): Any? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(obj)
            } catch (e: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException("Field '$fieldName' not found in ${obj.javaClass.name}")
    }

    /**
     * 反射获取对象字段值（不存在时返回 null，不抛异常）
     */
    fun getObjectFieldIfExist(obj: Any, fieldName: String): Any? {
        return try {
            getObjectField(obj, fieldName)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 在主线程执行代码
     */
    fun goMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /**
     * 日志工具
     */
    fun log(msg: String, e: Throwable? = null) {
        if (e != null) {
            YLog.error("$msg: ${e?.message}", e)
        } else {
            YLog.debug(msg)
        }
    }
}
