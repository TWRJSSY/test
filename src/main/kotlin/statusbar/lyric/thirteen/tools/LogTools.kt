package statusbar.lyric.thirteen.tools

import android.util.Log

object LogTools {
    private const val MAX_LENGTH = 4000
    private const val TAG = "StatusBarLyric"
    private const val XP_TAG = "LSPosed-Bridge"
    private var outprint = false

    fun Any?.log(): Any? {
        if (!outprint) return this
        val content = if (this is Throwable) Log.getStackTraceString(this) else this.toString()
        if (content.length > MAX_LENGTH) {
            val chunkCount = content.length / MAX_LENGTH
            for (i in 0..chunkCount) {
                val max = 4000 * (i + 1)
                val value = if (max >= content.length) content.substring(MAX_LENGTH * i)
                            else content.substring(MAX_LENGTH * i, max)
                Log.d(TAG, value)
                Log.d(XP_TAG, "$TAG:$value")
            }
        } else {
            Log.d(TAG, content)
            Log.d(XP_TAG, "$TAG:$content")
        }
        return this
    }

    fun init(out: Boolean) { outprint = out }
}
