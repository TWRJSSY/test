package statusbar.lyric.thirteen.hook.lockscreen

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.highcapable.yukihookapi.hook.log.YLog
import statusbar.lyric.thirteen.core.StateController

/**
 * 任务卡片 12：锁屏歌词自定义 View
 *
 * 布局结构（水平 LinearLayout）：
 * LockscreenLyricView
 * ├── iconView（ImageView, 32×32dp, 可选）
 * └── lyricView（TextView + Marquee 滚动）
 */
class LockscreenLyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var iconView: ImageView? = null
    private var lyricView: TextView? = null

    init {
        initializeLayout()
    }

    private fun initializeLayout() {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0x20000000) // 20% 黑色透明背景

        // 应用图标区域（32×32dp）
        if (shouldShowIcon()) {
            iconView = ImageView(context).apply {
                layoutParams = LayoutParams(dpToPx(32), dpToPx(32)).apply {
                    marginStart = dpToPx(12)
                    marginEnd = dpToPx(8)
                }
                contentDescription = "App Icon"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            addView(iconView)
        }

        // 歌词显示区域（使用 TextView + Marquee 替代 LyricSwitchView）
        lyricView = TextView(context).apply {
            layoutParams = LayoutParams(0, dpToPx(48)).apply {
                weight = 1f
                marginStart = if (shouldShowIcon()) 0 else dpToPx(12)
                marginEnd = dpToPx(12)
            }
            textSize = 12f
            setTextColor(StateController.currentTextColor)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            isSelected = true // 启用跑马灯
            marqueeRepeatLimit = -1 // 无限循环滚动
        }
        addView(lyricView)

        // 默认隐藏，等待数据到达
        visibility = View.GONE

        YLog.debug("Layout initialized (icon: ${shouldShowIcon()})")
    }

    fun showLyric(lyric: String) {
        if (lyric.isBlank()) {
            hideLyric()
            return
        }
        try {
            lyricView?.text = lyric
            updateIconIfNeeded()
            if (visibility != View.VISIBLE) {
                visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            YLog.error("Error showing lyric", e)
        }
    }

    fun hideLyric() {
        try {
            lyricView?.text = ""
            visibility = View.GONE
        } catch (e: Exception) {
            YLog.error("Error hiding lyric", e)
        }
    }

    fun setLyricColor(color: Int) {
        lyricView?.setTextColor(color)
    }

    fun setLyricSize(size: Float) {
        lyricView?.textSize = size
    }

    fun setAppIcon(bitmap: Bitmap?) {
        try {
            if (iconView == null) return
            if (bitmap != null) {
                iconView?.setImageBitmap(bitmap)
                iconView?.visibility = View.VISIBLE
            } else {
                iconView?.visibility = View.GONE
            }
        } catch (e: Exception) {
            YLog.error("Error setting app icon", e)
        }
    }

    private fun updateIconIfNeeded() {
        if (!shouldShowIcon() || iconView == null) return
        try {
            val currentIcon = StateController.currentIconBitmap
            if (currentIcon != null) {
                iconView?.setImageBitmap(currentIcon)
                iconView?.visibility = View.VISIBLE
            } else {
                iconView?.visibility = View.GONE
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun shouldShowIcon(): Boolean = true

    fun setBackgroundAlpha(alpha: Int) {
        val clampedAlpha = alpha.coerceIn(0, 255)
        setBackgroundColor((clampedAlpha shl 24) or 0x000000)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    fun getCurrentLyric(): String? = lyricView?.text?.toString()

    fun isLyricVisible(): Boolean = visibility == View.VISIBLE
}
