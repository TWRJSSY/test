package statusbar.lyric.thirteen.hook.lockscreen

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import com.highcapable.yukihookapi.hook.log.YLog
import statusbar.lyric.thirteen.core.StateController
import statusbar.lyric.thirteen.hook.BaseHook
import statusbar.lyric.thirteen.tools.ReflectTools.goMainThread

object LockscreenLyricHook : BaseHook() {

    private var lockscreenLyricView: LockscreenLyricView? = null
    private var keyguardContainer: ViewGroup? = null

    override fun init() {
        // 无条件注册 Hook，在实际执行时（onKeyguardViewInflated）读 prefs 判断是否生效
        // 原因：此处（Hook 注册阶段）Application 尚未完全启动，SharedPreferences 不可靠
        YLog.debug(msg = "Starting lockscreen lyric hook...")
        hookLockscreenViews()
    }

    private fun hookLockscreenViews() {
        with(packageParam) {
            // HyperOS 3 推荐容器（手册附录C）
            // 依次尝试多个方法名（手册：onViewAttached / onAttachedToWindow / onFinishInflate）
            val hyperOSCandidates = listOf("onViewAttached", "onAttachedToWindow", "onFinishInflate")
            var hyperOSHooked = false
            for (methodName in hyperOSCandidates) {
                if (hyperOSHooked) break
                runCatching {
                    "com.android.keyguard.widget.HyperOSKeyguardRootView".hook {
                        injectMember {
                            method { name = methodName }
                            afterHook {
                                onKeyguardViewInflated(instance as? ViewGroup)
                            }
                        }
                    }
                    YLog.debug(msg = "Hooked HyperOSKeyguardRootView.$methodName")
                    hyperOSHooked = true
                }.onFailure {
                    YLog.debug(msg = "HyperOSKeyguardRootView.$methodName not found: ${it.message}")
                }
            }

            try {
                "com.android.keyguard.MiuiKeyguardHostView".hook {
                    injectMember {
                        method {
                            name = "onFinishInflate"
                            emptyParam()
                        }
                        afterHook {
                            onKeyguardViewInflated(instance as? ViewGroup)
                        }
                    }
                }
                YLog.debug(msg = "Hooked MiuiKeyguardHostView.onFinishInflate")
            } catch (e: Throwable) {
                YLog.debug(msg = "MiuiKeyguardHostView not found: ${e.message}")
            }

            // KeyguardRootView：依次尝试多个可能的方法名
            val keyguardCandidates = listOf(
                "onViewAttached", "onAttachedToWindow", "onFinishInflate", "onViewCreated"
            )
            var keyguardHooked = false
            for (methodName in keyguardCandidates) {
                if (keyguardHooked) break
                runCatching {
                    "com.android.systemui.keyguard.ui.view.KeyguardRootView".hook {
                        injectMember {
                            method { name = methodName }
                            afterHook {
                                onKeyguardViewInflated(instance as? ViewGroup)
                            }
                        }
                    }
                    YLog.debug(msg = "Hooked KeyguardRootView.$methodName")
                    keyguardHooked = true
                }.onFailure {
                    YLog.debug(msg = "KeyguardRootView.$methodName not found: ${it.message}")
                }
            }
        }
    }

    private fun onKeyguardViewInflated(keyguardView: ViewGroup?) {
        if (keyguardView == null) return
        goMainThread {
            try {
                // 运行时读 prefs 判断开关（Application 已启动，context 可用）
                val isEnabled = runCatching {
                    keyguardView.context
                        .getSharedPreferences("COMPOSE_CONFIG", android.content.Context.MODE_PRIVATE)
                        .getBoolean("extra_lockscreen_lyric", false)
                }.getOrDefault(false)
                if (!isEnabled) {
                    YLog.debug(msg = "Lockscreen lyric disabled by user prefs, skipping")
                    return@goMainThread
                }
                if (lockscreenLyricView?.parent != null) return@goMainThread
                lockscreenLyricView = LockscreenLyricView(keyguardView.context)
                keyguardContainer = keyguardView
                val layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = dpToPx(keyguardView.context, 80)
                }
                keyguardView.addView(lockscreenLyricView, layoutParams)
                YLog.debug(msg = "LockscreenLyricView injected")
                val currentLyric = StateController.currentLyric
                if (!currentLyric.isNullOrEmpty()) {
                    lockscreenLyricView?.showLyric(currentLyric)
                }
            } catch (e: Exception) {
                YLog.error(msg = "Error injecting LockscreenLyricView", e = e)
            }
        }
    }

    /**
     * 供 StatusBarHook 调用，更新锁屏歌词显示。
     * ⚠️ 不能在这里调用 StateController.setOnLyricUpdatedCallback！
     *    那会覆盖 StatusBarHook 已注册的回调，导致状态栏歌词完全消失。
     *    锁屏歌词更新由 StatusBarHook 在其回调里追加调用本方法。
     */
    fun updateLyric(lyric: String?) {
        goMainThread {
            runCatching {
                if (lyric.isNullOrEmpty()) lockscreenLyricView?.hideLyric()
                else lockscreenLyricView?.showLyric(lyric)
            }
        }
    }

    fun updateAppIcon(icon: android.graphics.Bitmap?) {
        goMainThread { runCatching { lockscreenLyricView?.setAppIcon(icon) } }
    }

    private fun dpToPx(context: android.content.Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    override fun unhook() {
        try {
            lockscreenLyricView?.let { (it.parent as? ViewGroup)?.removeView(it) }
            lockscreenLyricView = null
            keyguardContainer = null
            YLog.debug(msg = "Lockscreen lyric hook unhooked")
        } catch (e: Exception) {
            YLog.error(msg = "Error unhooking", e = e)
        }
    }
}
