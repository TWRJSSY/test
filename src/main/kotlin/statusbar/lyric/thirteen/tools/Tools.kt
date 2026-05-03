package statusbar.lyric.thirteen.tools

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.icu.text.SimpleDateFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.Toast
import de.robv.android.xposed.XSharedPreferences
import statusbar.lyric.thirteen.BuildConfig
import statusbar.lyric.thirteen.MainActivity
import statusbar.lyric.thirteen.tools.ActivityTools.isHook
import java.util.Locale

@SuppressLint("StaticFieldLeak")
object Tools {

    val buildTime: String =
        SimpleDateFormat("yyyy/M/d H:m:s", Locale.CHINA).format(BuildConfig.BUILD_TIME)

    val isPad by lazy { getSystemProperties("ro.build.characteristics") == "tablet" }

    val getPhoneName by lazy {
        val xiaomiMarketName = getSystemProperties("ro.product.marketname")
        val vivoMarketName = getSystemProperties("ro.vivo.market.name")
        when {
            Build.BRAND.uppercaseFirstChar() == "Vivo" -> vivoMarketName.uppercaseFirstChar()
            xiaomiMarketName.isNotEmpty() -> xiaomiMarketName.uppercaseFirstChar()
            else -> "${Build.BRAND.uppercaseFirstChar()} ${Build.MODEL}"
        }
    }

    fun String.uppercaseFirstChar(): String =
        this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    fun dp2px(context: Context, dpValue: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dpValue, context.resources.displayMetrics
        ).toInt()

    internal fun isPresent(name: String): Boolean {
        return try {
            Thread.currentThread().contextClassLoader!!.loadClass(name)
            true
        } catch (_: ClassNotFoundException) { false }
    }

    @SuppressLint("PrivateApi")
    fun getSystemProperties(key: String): String {
        return try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String::class.java).invoke(null, key) as String
        } catch (_: Exception) { "" }
    }

    fun goMainThread(delayed: Long = 0, callback: () -> Unit): Boolean =
        Handler(Looper.getMainLooper()).postDelayed({ callback() }, delayed * 1000)

    fun Context.isLandscape() =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun getPref(key: String): XSharedPreferences? {
        return try {
            val pref = XSharedPreferences(BuildConfig.APPLICATION_ID, key)
            if (pref.file.canRead()) pref else null
        } catch (_: Throwable) { null }
    }

    fun getSP(context: Context, key: String): SharedPreferences {
        @Suppress("DEPRECATION", "WorldReadableFiles")
        return context.createDeviceProtectedStorageContext()
            .getSharedPreferences(
                key, if (isHook()) Context.MODE_WORLD_READABLE else Context.MODE_PRIVATE
            )
    }

    fun shell(command: String, isSu: Boolean) {
        try {
            if (isSu) {
                try {
                    val p = Runtime.getRuntime().exec("su")
                    val os = p.outputStream
                    os.write("$command\n".toByteArray())
                    os.flush(); os.close()
                } catch (_: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(MainActivity.appContext, "Root permissions required!!", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Runtime.getRuntime().exec(command)
            }
        } catch (_: Throwable) {}
    }

    inline fun <T> T?.isNotNull(callback: (T) -> Unit): Boolean {
        if (this != null) { callback(this); return true }
        return false
    }

    inline fun Boolean.isNot(callback: () -> Unit) { if (!this) callback() }

    inline fun Any?.isNull(callback: () -> Unit): Boolean {
        if (this == null) { callback(); return true }
        return false
    }

    inline fun <T> T?.ifNotNull(callback: (T) -> Any?): Any? {
        if (this != null) return callback(this)
        return null
    }

    fun Any?.isNull() = this == null
    fun Any?.isNotNull() = this != null
}
