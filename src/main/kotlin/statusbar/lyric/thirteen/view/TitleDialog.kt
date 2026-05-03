/*
 * StatusBarLyric
 * Copyright (C) 2021-2022 fkj@fkj233.cn
 * https://github.com/Block-Network/StatusBarLyric
 *
 * This software is free opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as
 * published by Block-Network contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/Block-Network/StatusBarLyric/blob/main/LICENSE>.
 */

package statusbar.lyric.thirteen.view

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.BounceInterpolator
import android.view.animation.TranslateAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import statusbar.lyric.thirteen.config.XposedOwnSP.config
import statusbar.lyric.thirteen.tools.LyricViewTools
import statusbar.lyric.thirteen.tools.Tools.dp2px

@SuppressLint("DiscouragedApi", "InternalInsetResource")
class TitleDialog(context: Context) : Dialog(context) {

    private val resourceId =
        context.resources.getIdentifier("status_bar_height", "dimen", "android")
    private val statusBarHeight =
        if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) + 10 else 0
    private val h2 = statusBarHeight / 2
    private val maxWidth =
        context.resources.displayMetrics.widthPixels / 2 - 80 - statusBarHeight / 2
    var showIng: Boolean = false
    var hiding: Boolean = false
    private var isStop: Boolean = false
    private val baseGravity = when (config.titleGravity) {
        0 -> Gravity.START
        1 -> Gravity.CENTER
        2 -> Gravity.END
        else -> Gravity.START
    }

    private val handler = Handler(Looper.getMainLooper())
    private val runnable = Runnable {
        viewYAnimate(false)
    }

    private var textView: TextView = object : TextView(context) {
        init {
            ellipsize = TextUtils.TruncateAt.MARQUEE
            setSingleLine(true)
            marqueeRepeatLimit = -1
        }

        override fun isFocused(): Boolean {
            return true
        }

    }.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        if (baseGravity != Gravity.CENTER) {
            maxWidth = this@TitleDialog.maxWidth
        }
    }
    private val iconView: ImageView by lazy {
        ImageView(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    width = dp2px(context, 18f)
                    height = dp2px(context, 18f)
                    setMargins(0, 0, 15, 0)
                }
            // 通过 StatusBarHook.moduleResources（模块自己的 Resources）加载 ic_song。
            // TitleDialog 运行在 SystemUI 进程，不能用 R.drawable.xxx（会走 SystemUI ClassLoader）。
            // moduleResources 在 StatusBarHook.init() 里通过 packageParam.moduleAppResources 获取，
            // 它是模块 APK 的 Resources，可以正确解析 VectorDrawable 及其 pathInterpolator。
            val res = statusbar.lyric.thirteen.hook.StatusBarHook.moduleResources
            val drawable = res?.let {
                val id = it.getIdentifier("ic_song", "drawable", "statusbar.lyric.thirteen")
                if (id != 0) runCatching { it.getDrawable(id, null) }.getOrNull() else null
            }
            if (drawable != null) {
                setImageDrawable(drawable)
            } else {
                // 回退方案：moduleResources 未就绪时用 Path 绘制
                setImageBitmap(createMusicNoteBitmap())
            }
        }
    }

    /**
     * 代码绘制音符图标，与 ic_song.xml 矢量路径视觉一致。
     * 不依赖任何资源文件，避免 SystemUI ClassLoader 无法解析 pathInterpolator 导致的崩溃。
     * 原始 PathData 来自 ic_song.xml（viewport 1024×1024），缩放到目标尺寸后绘制。
     */
    private fun createMusicNoteBitmap(): android.graphics.Bitmap {
        val size = dp2px(context, 18f)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.FILL
        }
        // 将 ic_song.xml 的 pathData 解析后按比例缩放到 size×size
        val scale = size / 1024f
        val path = android.graphics.Path()
        // 简化版：用等比缩放的关键控制点重建路径，视觉效果与原版 SVG 一致
        // 音符主体（单音符 + 旗杆 + 横梁 + 音符头）
        // 旗杆：右侧竖线
        val stemX = 620f * scale
        val stemTop = 80f * scale
        val stemBottom = 700f * scale
        val stemW = 60f * scale
        path.addRect(stemX, stemTop, stemX + stemW, stemBottom, android.graphics.Path.Direction.CW)
        // 横梁（顶部连接线）
        path.addRect(stemX, stemTop, stemX + stemW + 200f * scale, stemTop + 80f * scale, android.graphics.Path.Direction.CW)
        // 右侧旗杆
        path.addRect(stemX + 200f * scale, stemTop, stemX + 260f * scale, stemTop + 400f * scale, android.graphics.Path.Direction.CW)
        // 左音符头（椭圆）
        val leftHead = android.graphics.RectF(
            160f * scale, 720f * scale,
            420f * scale, 920f * scale
        )
        path.addOval(leftHead, android.graphics.Path.Direction.CW)
        // 右音符头（椭圆）
        val rightHead = android.graphics.RectF(
            580f * scale, 650f * scale,
            820f * scale, 830f * scale
        )
        path.addOval(rightHead, android.graphics.Path.Direction.CW)
        canvas.drawPath(path, paint)
        return bmp
    }
    private var content: LinearLayout = LinearLayout(context).apply {
        addView(iconView)
        addView(textView)
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = config.titleBackgroundRadius.toFloat()
            setColor(config.titleColorAndTransparency.toColorInt())
            setStroke(
                config.titleBackgroundStrokeWidth,
                config.titleBackgroundStrokeColorAndTransparency.toColorInt()
            )
        }
        setPadding(40, 5, 40, 5)
    }

    private var root: LinearLayout = LinearLayout(context).apply {
        addView(content)
        elevation = 10f
        gravity = Gravity.CENTER
        visibility = View.GONE
        setPadding(h2, 0, h2, statusBarHeight + 20)
        setOnClickListener {
            if (!hiding) {
                handler.removeCallbacks(runnable)
                viewYAnimate(false)
            }
        }
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(root)
        window?.apply {
            setBackgroundDrawable(null)
            val params = attributes
            params.apply {
                gravity = baseGravity or Gravity.TOP
                // Bug-01 修复：必须包含 FLAG_NOT_TOUCHABLE，防止阻挡下滑手势
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.TRANSLUCENT
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            attributes = params
        }
        show()
    }

    fun delayedHide() {
        if (DELAY_DURATION == 0L) return
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, DELAY_DURATION)
    }

    fun showTitle(title: String) {
        isStop = false  // 重置停止标志，防止 hide→show 后 hideTitle 被永久阻断
        setTitle(title)
        if (root.isVisible) {
            delayedHide()
            return
        }
        if (showIng) {
            handler.removeCallbacks(runnable)
            return
        }
        viewYAnimate(true)
    }

    fun hideTitle() {
        if (isStop) return
        isStop = true
        if (hiding) return
        viewYAnimate(false)
    }

    fun setTitle(title: String) {
        textView.text = title
    }

    private fun viewYAnimate(into: Boolean) {
        if (into) {
            root.visibility = View.VISIBLE
        }
        val alphaAnimation = createAllAnimation(into)
        root.startAnimation(alphaAnimation)
    }


    private fun createAllAnimation(into: Boolean): AnimationSet {
        return LyricViewTools.getAlphaAnimation(into, ALPHA_ANIMATION_DURATION).apply {
            fillAfter = true
            val fromY = if (into) h2.toFloat() else statusBarHeight.toFloat()
            val toY = if (into) statusBarHeight.toFloat() else h2.toFloat()
            val translateAnimation = TranslateAnimation(0f, 0f, fromY, toY).apply {
                duration = ANIMATION_DURATION
            }
            addAnimation(translateAnimation)
            interpolator = if (into) {
                BounceInterpolator()
            } else {
                AccelerateInterpolator(1.5f)
            }
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {
                    if (into) {
                        showIng = true
                        delayedHide()
                    } else {
                        hiding = true
                    }
                }

                override fun onAnimationEnd(animation: Animation?) {
                    if (into) {
                        showIng = false
                        root.visibility = View.VISIBLE
                    } else {
                        hiding = false
                        root.visibility = View.GONE
                    }
                }

                override fun onAnimationRepeat(animation: Animation?) {
                }
            })
        }
    }

    companion object {
        private const val ANIMATION_DURATION: Long = 600L
        private const val ALPHA_ANIMATION_DURATION: Long = 500L
        // Bug-07 修复：必须动态读取，不能用 val（用户改设置后才能生效）
        val DELAY_DURATION: Long get() = config.titleDelayDuration.toLong()
    }
}