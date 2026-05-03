# 墨·状态栏歌词 操作手册 v14

> 当前源码：`Thirteen_v14_7-A_20.zip`
> 当前状态：7-A ✅ 已通过，进入7-B（通知图标拦截进行中）
> 写给人类和 AI 都能快速读懂

---

## 一、新对话必读

| 项目 | 值 |
|---|---|
| 包名 | `statusbar.lyric.thirteen` |
| 设备 | Redmi K90 Pro Max / HyperOS 3.0 / Android 16（API 36）|
| LSPosed | 2.0.1 (7639) / YukiHookAPI 1.3.1 |
| KSP | `2.2.10-2.0.2`（格式固定不能改）|
| Kotlin / AGP | 2.2.10 / 9.1.0 / SuperLyricApi 3.4 |

**每次必须提供：** 最新源码 zip + 本手册。崩溃时加 LSPosed 日志 zip。Hook 类名不确定时加 SystemUI APK。

**开场模板：**
```
我在开发 Android Xposed 模块（墨·状态栏歌词），包名 statusbar.lyric.thirteen，
设备 Redmi K90 Pro Max / HyperOS 3.0 / Android 16。
请先读手册 v12，再读源码 zip。当前任务：[填写]
先说方案，我确认后再写代码。
```

---

## 二、架构铁律

```
感知层  LyricManager / IslandHook / NotificationTracker
           ↓ 只调用 on*() 上报，不做任何业务判断
调度层  StateController（唯一大脑，判断状态，触发回调）
           ↓ 只通过注册的回调通知，不碰任何 View
执行层  StatusBarHook（唯一渲染者）
```

| # | 禁忌 | 后果 |
|---|---|---|
| F-01 | 感知层直接调执行层 | 破坏单向数据流 |
| F-02 | StateController 操作 View | 它只管状态 |
| F-03 | Hook 层调用 transitionTo() | 只能调 on*() |
| F-04 | 增益 Hook 引用 StateController | 增益与状态机解耦 |
| F-05 | LockscreenLyricHook 独立注册 setOnLyricUpdatedCallback | 覆盖主回调 → 状态栏歌词永久消失 |
| F-06 | Hook MediaDataManager 取歌名 | 误识视频标题 |
| F-07 | getApplicationIcon() 取图标 | 改用通知 smallIcon |
| F-08 | 进入 SYSTEM_RESTORE 前跳过 restoreAllSlots() | 系统无法正确接管 |
| F-09 | onTouchEvent beforeHook 里 result=true | 下拉状态栏卡死 |
| F-10 | 读 playbackState 字段 | SuperLyric 3.4 永远 null |
| F-11 | 感知层做业务过滤 | 黑名单/仲裁必须在 StateController |

---

## 三、开发路线图

```
✅ 步骤 1-6    基础链路（已完成）
✅ 7-C基础     样式参数（已提前完成）

── 主线 ──────────────────────────────────────────────

✅ 7-A   基础歌词 + 状态机 真机验收（已完成）
          V14 临时走 LYRIC_SHORT（BUG-B不阻塞）

   7-B   接通遗留 Config 开关 + 信号 BUG 修复         ⭐~⭐⭐
          无联动风险，先清掉最多遗留缺口

   7-C   锁屏歌词                                     ⭐⭐⭐
          前置：7-B。联动风险高，单独一步。
          progressEnabled = false 铁律在此确立，是 7-D 的前置。

   7-D   双排歌词 + 匀速进度                           ⭐⭐⭐
          前置：7-C（progressEnabled 铁律必须先确立）

── 主线完成 ──────────────────────────────────────────

   7-E   增益补全 + 触摸手势 + HyperOS 专属 + 全屏检测  ⭐⭐~⭐⭐⭐
   7-F   HIDE_TO_CUTOUT 真正实现（独立立项，主线跑通后合并）⭐⭐⭐⭐
   7-G   支线收尾（CrashDetector/逐字进度/图标包等）    ⭐~⭐⭐⭐⭐
   7-H   依赖更新（最后，API 变化风险高）               ⭐⭐
   7-I   代码清理 + 开源准备                            ⭐
   步骤8  发布
```

**排序逻辑：**
- 7-C 必须在 7-D 前：锁屏 `progressEnabled = false` 是双排的前置铁律，顺序反了必出 bug
- 7-F（HIDE_TO_CUTOUT）放主线之后：难度⭐⭐⭐⭐，不阻塞核心体验
- 7-G 逐字进度单独放：onDraw 复杂，不和双排绑在一起避免验收卡死
- 7-H 依赖更新最后：API 变化会破坏所有现有 Hook

---

## 四、状态机

### 六个状态

| 状态 | 含义 | 渲染要点 |
|---|---|---|
| `NORMAL` | 无音乐 | 隐藏歌词，恢复所有系统 View |
| `LYRIC` | 有音乐，无岛 | 显示歌词，隐藏原始时钟 |
| `LYRIC_SHORT` | 有音乐，短岛（≤30%）| 歌词限宽，信号隐藏，电池保留 |
| `SYSTEM_RESTORE` | 有音乐，大岛 | 先 restoreAllSlots()，再隐藏歌词 |
| `MANUAL_CLOCK` | 用户切回原始时钟 | 隐藏歌词，**customClockView 无论如何必须隐藏** |
| `MANUAL_SIGNAL` | 用户切回信号区 | 保留歌词，恢复信号，隐藏 customClockView |

### 转换规则

```
onLyricUpdate(lyric, pkg, delay, words, secondary, title, artist)
  门卫1：videoBlacklist → return
  门卫2：多源仲裁（8秒 stale）→ return
  通过后：更新数据，触发回调
  title/artist 有值时无条件触发标题弹窗（纯音乐无歌词也触发，设计如此）
  if isLandscape | userForcedClock | SYSTEM_RESTORE → return（不切状态）
  NORMAL → LYRIC（lyric 非空时才切状态）

onMusicPaused()        lastLyricTimestamp=0，scheduleHide(5000ms)
onMusicStopped()       → NORMAL，清所有标志位

onLyricClicked()       LYRIC / LYRIC_SHORT / MANUAL_SIGNAL → MANUAL_CLOCK
onOriginalClockClicked() MANUAL_CLOCK → LYRIC
onNewClockClicked()    LYRIC / LYRIC_SHORT → MANUAL_SIGNAL
onSystemIconClicked()  MANUAL_SIGNAL → LYRIC

onIslandShown(width, left, pkg, screenWidth)
  BLOCK         → return
  HIDE_TO_CUTOUT→ 临时按宽度分叉（7-F 未完成前的过渡行为）
                  width≤30% → LYRIC_SHORT；else → SYSTEM_RESTORE
                  真正实现（收缩到挖孔动画）独立立项，主线跑通后合并
  width≤30%     → LYRIC_SHORT；else → SYSTEM_RESTORE
  只在首次进入时记录 previousState，连续岛不覆盖

onIslandHidden()
  previousState==MANUAL_CLOCK/SIGNAL → 恢复
  else → 有歌词→LYRIC，无→NORMAL

onOrientationChanged(landscape)   true→NORMAL；false→有歌词→LYRIC
onScreenLocked()    hideLyricWhenLockScreen=true → NORMAL
onScreenUnlocked()  有歌词且竖屏 → LYRIC
```

### 标志位

| 标志位 | 语义 | 清除时机 |
|---|---|---|
| `userForcedClock` | 用户切到原始时钟，新歌词不覆盖 | onOriginalClockClicked / onMusicStopped |
| `userForcedSignal` | 用户切到信号区 | onSystemIconClicked / onMusicStopped |
| `islandHiddenForLyric` | 冗余岛已处理 | onMusicStopped |
| `isLandscape` | 横屏，不干预 | onOrientationChanged(false) |
| `previousState` | 岛消失后恢复目标 | 首次进入 LYRIC_SHORT / SYSTEM_RESTORE 时记录 |
| `lastLyricTimestamp` | 多源仲裁 | onMusicPaused 清零 |

### 超级岛枚举

```kotlin
enum class IslandHandling { SYSTEM_RESTORE, BLOCK, HIDE_TO_CUTOUT }
enum class IslandRule { DEFAULT, ALWAYS_ALLOW, ALWAYS_BLOCK, HIDE_TO_CUTOUT }
// 存储在独立 SP：lyric_island_rules，key = "rule_${packageName}"
// 优先级：用户自定义 > 兜底 SYSTEM_RESTORE（不干预）
//
// ⚠️ v13 架构变更：
//   - 废弃硬编码 defaultVideoBlacklist 和歌词冗余岛判断
//   - 改为 applyRecommendedIslandRules() 在首次加载时预置推荐默认规则到 SP
//   - 推荐规则：视频类 APP → ALWAYS_BLOCK，音乐类 APP → HIDE_TO_CUTOUT
//   - 用户未设置的 APP 兜底走 SYSTEM_RESTORE（完全不干预）
//   - 规则全局生效，与是否有歌词无关
//   - islandMap key 格式：userid|packageName|id|tag|userid，取 split("|")[1]
```

---

## 五、功能开关完整清单

> Config 里所有字段只推迟不删除。SP 文件：`COMPOSE_CONFIG`

### 基础

| Key | 默认 | 状态 |
|---|---|---|
| `masterSwitch` | false | ❌ **7-B** |
| `hideTime` | true | ❌ **7-B**（当前硬编码隐藏，开关未接）|
| `hideNotificationIcon` | false | ❌ **7-B**（当前硬编码 INVISIBLE）|
| `showLauncherIcon` | false | ✅ Manifest 层 |
| `outLog` | false | ✅ MainActivity |

### 歌词核心

| Key | 默认 | 状态 |
|---|---|---|
| `show_custom_clock` | false | ✅ SC→Hook |
| `show_lyric_icon` | true | ✅ SC→Hook |
| `lyric_redundancy_hide` | true | ✅ SC.getIslandHandling() |
| `video_blacklist_enabled` | true | ✅ SC 门卫 |
| `titleSwitch` | true | ✅ 纯音乐/有歌词均触发弹窗（只要有 title/artist）|
| `titleDelayDuration` | 3000ms | ✅ 动态读 |
| `titleColorAndTransparency` | "" | ❌ **7-E** |
| `titleGravity` | 0 | ❌ **7-E** |
| `titleBackgroundRadius` | 0 | ❌ **7-E** |
| `titleBackgroundStrokeWidth` | 0f | ❌ **7-E** |
| `titleBackgroundStrokeColorAndTransparency` | "" | ❌ **7-E** |
| `titleShowWithSameLyric` | false | ❌ **7-D** |
| `hideLyricWhenLockScreen` | false | ✅ SC→onScreenLocked |
| `automateFocusedNotice` | true | ❌ **7-E** |
| `clickStatusBarToHideLyric` | false | ❌ **7-E** |
| `longClickStatusBarStop` | false | ❌ **7-E** |
| `slideStatusBarCutSongs` | false | ❌ **7-E** |
| `slideStatusBarCutSongsXRadius` | 10 | ❌ **7-E** |
| `slideStatusBarCutSongsYRadius` | 50 | ❌ **7-E** |
| `limitVisibilityChange` | false | ❌ **7-G** |
| `timeoutRestore` | true | ❌ **7-B**（当前硬编码开启）|
| `hideLyricWhenLockScreen` | false | ✅ |
| `hideCarrier` | false | ❌ **7-E** |
| `viewLocation` | 0 | ❌ **7-B**（0=左/1=右，当前硬编码左）|

### 歌词样式（7-C 已部分完成）

| Key | 默认 | 说明 |
|---|---|---|
| `lyricSize` | 0 | **0=跟随时钟字号**（⚠️ 原作者逻辑，我们当前未实现，7-B修）|
| `lyricSpeed` | 1 | 滚动速度倍率 |
| `lyricColor` | "" | 空=跟随反色 |
| `lyricGradientColor` | "" | 渐变色 |
| `lyricBackgroundColor` | "" | 背景色 |
| `lyricBackgroundRadius` | 0 | 背景圆角 |
| `lyricLetterSpacing` | 0 | 字间距 |
| `lyricStrokeWidth` | 0 | 描边 |
| `lyricAnimation` | 0 | 动画类型（0-11，11=随机）|
| `lyricInterpolator` | 0 | 插值器 |
| `animationDuration` | 300ms | 动画时长 |
| `dynamicLyricSpeed` | false | 动态速度 |
| `lyricStartMargins/End/Top/Bottom` | 0 | 边距 |
| `lyricWidth` | 0 | 宽度百分比 |
| `fixedLyricWidth` | false | 固定宽度 |

### 图标样式

| Key | 默认 | 说明 |
|---|---|---|
| `iconSwitch` | true | 显示图标 |
| `iconSize` | 0 | 0=自适应 48px |
| `iconColor` | "" | 空=跟随反色 |
| `iconBgColor` | "" | 背景色 |
| `iconStartMargins/Top/Bottom` | 0 | 边距 |
| `changeAllIcons` | "" | 图标包路径（**7-G**）|
| `forceTheIconToBeDisplayed` | false | 强制显示（**7-G**）|

### 自定义注入目标（高级，**7-E**）

| Key | 默认 | 说明 |
|---|---|---|
| `textViewClassName` | "" | 自定义 TextView 类名 |
| `textViewId` | 0 | 自定义 TextView ID（⚠️ HomePage UI 缺了这项，7-B 补）|
| `parentViewClassName` | "" | 父 View 类名 |
| `parentViewId` | 0 | 父 View ID |
| `index` | 0 | 子 View 序号 |
| `relaxConditions` | false | 宽松条件 |
| `testMode` | false | 测试模式 |
| `pageRatio` | 0f | 进度比例（双排进度用，**7-D**）|

### HyperOS 专属（**7-E**）

| Key | 默认 | 说明 |
|---|---|---|
| `mMiuiHideNetworkSpeed` | false | 隐藏网速 |
| `mMiuiPadOptimize` | false | 平板优化 |
| `mHyperOSTexture` | false | 毛玻璃 |
| `mHyperOSTextureRadio` | 0 | 模糊半径 |
| `mHyperOSTextureCorner` | 0 | 圆角 |
| `mHyperOSTextureBgColor` | "" | 背景色 |

### 增益功能

| Key | 默认 | 状态 |
|---|---|---|
| `extra_kill_media_card` | true | ✅ |
| `extra_kill_music_headsup` | false | ✅ |
| `extra_kill_lockscreen_media` | false | ✅ |
| `extra_icon_rule` | false | ✅ |
| `extra_lockscreen_lyric` | false | ✅ 框架，**7-C 完善** |

### 7-D 新增

| Key | 默认 | 说明 |
|---|---|---|
| `lyric_display_mode` | "single" | "single" / "double" |
| `lyric_progress_mode` | "none" | "none" / "sweep" / "word"（word 在 7-G）|

### 超级岛用户规则

```
SP 文件：lyric_island_rules
Key：rule_${packageName}
Value：DEFAULT / ALWAYS_ALLOW / ALWAYS_BLOCK / HIDE_TO_CUTOUT
```

---

## 六、7-A 验收清单（当前任务）

```
✅ V1   日志：IslandHook / StatusBarHook / LyricManager 全部 loaded，无 hook_failed
✅ V2   SystemUI 不崩溃

✅ V3   播放有歌词歌曲 → 状态栏图标+歌词，原始时钟隐藏
✅ V4   播放纯音乐（无歌词）→ 有 title/artist 时触发标题弹窗，状态栏不出现歌词滚动
✅ V5   暂停 5 秒 → NORMAL，系统布局恢复（SuperLyric 暂停提示帧已过滤）
✅ V6   间奏 > 10 秒 → 最后一句保留，不消失
✅ V7   B站播放视频 → 状态栏不出现歌词

✅ V8   点歌词 → MANUAL_CLOCK，新歌词到来不自动切回，无标题弹窗误触发
✅ V9   MANUAL_CLOCK 下点原始时钟 → 切回 LYRIC
✅ V10  下拉状态栏不卡死

✅ V11  插耳机（短岛 ≤30%屏宽=360px）→ LYRIC_SHORT，歌词限宽（gap=20dp），信号隐藏，电池保留；拔掉恢复
✅ V12  MANUAL_CLOCK 下插拔耳机 → 拔掉后恢复 MANUAL_CLOCK，不被覆盖为 LYRIC
✅ V13  接充电器（大岛 >30%）→ SYSTEM_RESTORE，歌词消失；断开恢复
✅ V14  音乐 APP 发岛（推荐规则 HIDE_TO_CUTOUT）→ 走 LYRIC_SHORT；临时方案不阻塞
✅ V15  B站发岛（推荐规则 ALWAYS_BLOCK）→ BLOCK，歌词不受影响
```

**7-A-13 新增验收：**
```
✅ V16  反色正常：歌词/图标颜色与右侧系统图标一致，切壁纸后跟随变化
✅ V17  TitleDialog 音符图标与原版视觉一致（moduleResources 方案）
✅ V18  异常标题弹窗：showTitle 日志追踪，观察是否还会无故触发
```

**7-A-14 新增验收：**
```
✅ V19  时钟/通知图标不再在歌词状态下偶现（显示决策拦截生效）
✅ V20  歌词限宽：插耳机后歌词从第一帧就在正确宽度内，不出现右侧截断
✅ V21  TitleDialog 音符图标与原版 ic_song.xml 一致（VectorDrawable 版）
✅ V22  暂停永久停留：观察日志中 scheduleHide/cancelHideTimer/onMusicStopped 时序
```

---

## 七、后续步骤实施要点

### 7-B：接通 Config 开关（前置：7-A 通过）

**需要做的事：**

```kotlin
// 1. masterSwitch：MainHook.onHook() 最开始
if (!prefs.getBoolean("masterSwitch", false)) return

// 2. lyricSize == 0 → 跟随时钟字号（原作者逻辑，当前遗漏）
val size = if (config.lyricSize == 0) clockView.textSize else config.lyricSize.toFloat()
container.lyricView.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)

// 3. viewLocation（0=左，1=右）：lyricInit() 里决定注入到时钟左侧还是右侧

// 4. timeoutRestore：setOnLyricUpdatedCallback 里的计时器接开关
if (StateController.timeoutRestore) {
    mainHandler.sendEmptyMessageDelayed(TIMEOUT_RESTORE, 10000L)
}

// 5. hideTime / hideNotificationIcon：applyLyric() 里接开关（当前硬编码）

// 6. StateController 新增字段（在 loadSwitches() 里读）：
var timeoutRestore: Boolean = true
var hideTime: Boolean = true
var hideNotificationIcon: Boolean = false
var viewLocation: Int = 0

// 7. 探针C + signalViews补全：
// 在 findAndCacheSignalViews() 里加一段打印，采集日志后补全 signalViews 列表

// 8. HomePage 补 textViewId UI 控件（一行代码）
```

**验收：** 所有开关在 APP 里拨动后重启 SystemUI 立即生效；LYRIC_SHORT 时信号完全隐藏。

---

### 7-C：锁屏歌词（前置：7-B）

⚠️ **联动铁律：LyricSwitchView 的 `progressEnabled` 必须在这里设为 false，这是 7-D 双排歌词的前置条件，顺序不能反。**

```kotlin
// 布局
LinearLayout(vertical)
  ├─ titleView: TextView          // title · artist，小字灰色
  ├─ lyricView: LyricSwitchView   // progressEnabled = false！强制，不接开关
  └─ translationView: TextView    // ellipsize=END，静止不滚动

// 锁屏容器候选（依次尝试）
"com.android.keyguard.widget.HyperOSKeyguardRootView"  // HyperOS 3 推荐
"com.android.systemui.keyguard.ui.view.KeyguardRootView"
// ⚠️ 方法名可能变：依次尝试 onViewAttached / onAttachedToWindow / onFinishInflate

// 不得独立注册 setOnLyricUpdatedCallback（F-05）
// 数据通过 StatusBarHook 的回调追加调用 LockscreenLyricHook.updateLyric()

// Bug-10：锁屏切歌后开屏，标题弹窗残留
// 开屏时：titleDialog.forceReset()，强制触发 observableChange
// 只在 currentLyric != null 时触发弹窗
```

**验收：** 锁屏显示歌词；无进度着色（progressEnabled=false）；切歌开屏后弹窗不残留。

---

### 7-D：双排歌词 + 匀速进度（前置：7-C）

⚠️ **前置铁律：7-C 必须已完成，`progressEnabled` 机制必须稳定，否则进度着色会污染锁屏。**

```kotlin
// 滚动速度公式（7-C / 7-D 共用）
// 顺序：measureText → calcScrollSpeed → setScrollSpeed → setText（严格不能乱）
fun calcScrollSpeed(textLengthPx: Float, viewWidthPx: Float, delayMs: Long): Float {
    val overflow = textLengthPx - viewWidthPx
    if (overflow <= 0f) return 0f
    if (delayMs <= 0L) return 4f
    return (overflow / (delayMs / 16f).coerceAtLeast(1f)).coerceIn(0.5f, 20f)
}

// LYRIC_SHORT 双排限宽：上下排各自独立 layoutParams，绝不共用
val lpMain = container.lyricView.layoutParams as? LinearLayout.LayoutParams
lpMain?.width = maxWidth; container.lyricView.layoutParams = lpMain
container.secondaryView?.let { sv ->
    val lpSec = sv.layoutParams as? LinearLayout.LayoutParams
    lpSec?.width = maxWidth; sv.layoutParams = lpSec
    // 下排用自己的 paint 量宽度，字号 ×0.75，不能用上排的
}

// 匀速进度 sweep：
// onDraw 里禁止创建任何对象（Paint/RectF 提前分配为成员变量）
// 暂停 / TIMEOUT_RESTORE 时停止进度 Handler
// words == null 时自动降级匀速，不报错

// 新增开关（lyric_display_mode / lyric_progress_mode）
// double 模式强制 lyric_progress_mode = none
// titleShowWithSameLyric 在这里顺手实现
```

**验收：**
```
□ 单行/双行开关互斥；双行时进度选项灰显
□ 有 secondary → 双行；无 secondary → 降级单行
□ 下排字号 ×0.75，70% 透明度
□ 上下排各自独立滚动速度和限宽
□ 匀速进度平滑，暂停/超时时停止
□ LockscreenLyricView progressEnabled=false，无进度着色
```

---

### 7-E：增益补全 + 触摸手势 + HyperOS 专属 + 全屏检测

> 支线步骤，不影响主线。可以跳过直接进 7-F。

```kotlin
// 媒体卡片压制
Utils.useQsMediaPlayer() → replaceToFalse()
MediaFeatureFlag.getEnabled() → replaceToFalse()

// 触摸手势（PhoneStatusBarView.onTouchEvent）
// ⚠️ beforeHook 里绝对不能 result=true（F-09）
// clickStatusBarToHideLyric / longClickStatusBarStop
// slideStatusBarCutSongs（XRadius/YRadius 范围判断）

// FocusNotifyController（automateFocusedNotice）
// 原作者完整实现在 xiaomi/FocusNotifyController.kt，直接参考迁移

// 全屏检测（轻量，无需额外 Hook）
Settings.Global.getString(context.contentResolver, "policy_control")
// 含 "immersive.full" / "immersive.status" → onFullscreenChanged(true)

// HyperOS 专属
// mHyperOSTexture：参考 BlurTools.kt（已迁移）
// mMiuiHideNetworkSpeed：Hook MiuiNetworkSpeedView.setVisibility
// mMiuiPadOptimize：Hook 平板状态栏布局

// 样式参数完善（7-C 遗留）
// titleColor/gravity/radius 等
// 字号变化后重新 calcScrollSpeed（在 updateConfig 广播回调里触发）

// hideCarrier：Hook CarrierLabel.setVisibility
```

---

### 7-F：HIDE_TO_CUTOUT 真正实现

> 难度 ⭐⭐⭐⭐。前置：7-A 状态机稳定即可，与 7-C/7-D 无依赖。

**结论（SystemUI APK 反编译确认）：**
- 岛收缩逻辑在 `com.miui.systemui.statusbar.StatusBarClickTool`
- 该类在动态 ClassLoader 里，主 ClassLoader 找不到
- 需要 `hookDynamicClassLoaders`（Hook 所有 BaseDexClassLoader 构造函数）
- 在动态 ClassLoader 创建时捕获，loadClass 拿到该类，反射调用收缩方法
- 需要多轮探针D迭代确认方法名

**临时方案（当前）：** 歌词来源 App 发岛 → LYRIC_SHORT，歌词限宽，岛正常显示右侧。

---

### 7-G：支线收尾

- `CrashDetector`：MainHook.onHook() 最开始，20秒内3次崩溃 → isSafeMode=true → return
- `limitVisibilityChange`：Hook setVisibility，防外部覆盖歌词 View 可见性
- 逐字进度 `word`：words 数组着色，onDraw 禁止创建对象，设置页加性能警告
- `changeAllIcons`：图标包路径，加载自定义 Bitmap 替换 App 图标
- `forceTheIconToBeDisplayed`：强制显示图标（即使无通知 smallIcon）

---

### 7-H：依赖更新（最后做）

| 依赖 | 当前 | 处理 |
|---|---|---|
| YukiHookAPI | 1.3.1 | 等 2.0 正式版，API 会有 breaking change |
| Haze | 1.5.4 | 升 1.7.x，`hazeChild` → `hazeEffect` |
| SuperLyricApi | 3.4 | 确认 jitpack 最新 tag |
| MiuiX | 0.4.3 | 查 GitHub releases |

---

### 7-I：代码清理

删除：`TestPage.kt / ChoosePage.kt / ActivityTestTools.kt` 及对应路由。清理调试注释和无用 TODO。

---

## 附录 A：踩坑记录

### 7-A-13 实战新增

| 坑 | 原因 | 解决 |
|---|---|---|
| SuperLyric 暂停推提示帧「歌曲已暂停，即将隐藏歌词」 | 服务端先 sendLyric(提示帧) 再 sendStop，提示帧显示 5 秒 | LyricManager.onLyric 精确字符串过滤 |
| MANUAL_CLOCK 下标题弹窗误触发 | onLyricUpdate 里 title 触发在 userForcedClock return 之前 | 将 return 检查提前到 onTitleChangedCb 之前 |
| islandMap key 是通知 key 格式 | `userid\|packageName\|id\|tag\|userid`，非纯包名 | split("\|")[1] 提取第二段 |
| MiuiClock.setTextColor hook 失败 | 方法在 TextView 基类，MiuiClock 无自己实现 | 改 hook android.widget.TextView.setTextColor(int)，按 id 过滤 |
| LYRIC_SHORT→LYRIC 信号不恢复 | applyLyric() 未调 restoreSignalViews() | 补调 |
| 硬编码黑名单污染架构 | defaultVideoBlacklist 写死在业务逻辑里 | 改为 applyRecommendedIslandRules() 预置 SP，用户可覆盖 |

### 7-A-14 实战新增

| 坑 | 原因 | 解决 |
|---|---|---|
| 时钟/通知图标被系统周期性恢复 | `MiuiCollapsedStatusBarFragment` 通过 `showClock()` / `updateStatusBarVisibilities()` 持续主动覆盖 | hook 这两个决策方法，歌词状态下直接 return |
| 限宽时机太晚，歌词滚出才限宽 | `applyLimitedWidth` 在 `post{}` 里，歌词已开始滚动 | 改为同步执行，进入 LYRIC_SHORT 第一帧就完成限宽 |
| TitleDialog 图标丑 | `createMusicNoteBitmap()` 用 Path 手绘，与原版 SVG 差距大 | 改用 `moduleResources` 加载 `ic_song.xml`（VectorDrawable） |
| `isStop=true` 导致 hideTitle 永久阻断 | `showTitle` 没有重置 `isStop` | `showTitle` 首行加 `isStop = false` |
| moduleResources 需要提前初始化 | SystemUI 进程里无法用 `R.drawable.xxx` | `StatusBarHook.init()` 里 `packageParam.moduleAppResources` 存全局 |
| `onScreenUnlocked` 不检查 `userForcedClock` | 用户在 MANUAL_CLOCK 锁屏解锁后被强制切回 LYRIC | `onScreenUnlocked` 加 `if (userForcedClock || userForcedSignal) return` |
| `resultNull()` 用于 void 方法 | YukiHookAPI void 方法规范写法是 `resultUnit()` | 全部改为 `resultUnit()` |
| `isLyricShowing` 需要手动维护 | 各 apply 方法里手动赋值，容易漏 | 改为计算属性 `get() = isLyricActive()`，完全派生自状态机 |
| `applyRecommendedIslandRules` 每次 loadSwitches 都执行 | 配置更新广播频繁触发，重复写 SP | 加 `recommendedRulesApplied` 标志，只执行一次 |

### 7-A-19/20 实战新增

| 坑 | 原因 | 解决 |
|---|---|---|
| 超级岛透明（持续多版）| `tryHideIslandView()` 把岛容器 `layoutParams.width=0`，`setOnIslandHideToCutoutCallback` 一直在触发 | 19版禁用该回调，岛由系统正常渲染 |
| `showClock` 拦截导致岛透明 | `showClock` 是434字节大方法，同时驱动岛UI更新，`resultNull()` 把岛渲染也截断 | 去掉 `showClock` 拦截，改为 `TextView.setVisibility` hook |
| `hideNotificationIconArea` reflection失败 | `collapsedStatusBarFragment` onViewCreated时机问题，fragment实例一直为null | 改为 View.setVisibility 拦截方案（20版探针确认结构中） |

### 7-A 实战新增（v11 未记录）

| 坑 | 原因 | 解决 |
|---|---|---|
| LockscreenLyricHook 独立注册 setOnLyricUpdatedCallback | 覆盖主回调，状态栏歌词永久消失 | 锁屏通过 StatusBarHook 追加调用（F-05）|
| TitleDialog 加载 R.drawable.ic_song | SystemUI ClassLoader 无法解析 pathInterpolator → 崩溃 | 改为代码绘制 Bitmap |
| BaseExtraHook.prefs by lazy | 注册阶段 Application null → NPE | val prefs: get() 动态获取 |
| KeyguardRootView.onAttachedToWindow 不存在 | HyperOS 3 方法名变化 | 依次尝试多候选名 |
| 增益 Hook 注册阶段读 StateController | loadSwitches 未执行，读到默认值 | 无条件注册，内部读 prefs |
| TIMEOUT_RESTORE 调 restoreNotificationArea() | 状态机仍是 LYRIC，通知区不该恢复 | 移除该调用 |
| IconFilterHook.restoreAllSlots() 无条件 setVisible(true) | 勿扰图标被强制显示 | 改为只清除拦截标志 |
| ExtendPage 改设置不发 updateConfig 广播 | StateController 不重新加载 | onCheckedChange 后调 changeConfig() |

### 历史坑（v11 继承）

| 坑 | 解决 |
|---|---|
| IslandHook 用 onExpansionChanged / getIslandWidth | 改用 onIslandSizeChanged(Rect, Z) after |
| 限宽用 screenWidth-islandWidth | 改用 getLocationOnScreen 统一坐标系 |
| MANUAL_CLOCK 岛消失后跳 LYRIC | onIslandHidden 恢复 previousState |
| HIDE_TO_CUTOUT 永远不触发 | handling 改为枚举（Boolean 丢失三路分支）|
| 连续大岛 previousState 被覆盖 | 只在首次进入时记录 |
| onStop 双重触发 | setSystemPlayStateListenerEnabled(false) |
| LyricManager 直接调 StatusBarHook | 改为纯 StateController 回调 |
| LYRIC_SHORT 双排共用 layoutParams | 上下排各自独立 |
| setScrollSpeed 在 setText 之后 | 严格顺序：measureText→calcScrollSpeed→setScrollSpeed→setText |
| TitleDialog 阻挡下滑 | FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE |
| TitleDialog 延迟不更新 | companion val → 每次动态读 |
| onTouchEvent beforeHook result=true | 去掉，否则下拉卡死 |
| MiuiNotificationListener 签名错误 | HyperOS 3 用 2 参数：(SBN, RankingMap) |
| SuperLyricApi ClassNotFound | 必须 implementation，不能 compileOnly |
| containers 内存泄漏 | OnAttachStateChangeListener detach 时 remove |

---

## 附录 B：已知问题

| # | 问题 | 根本原因 | 归属 |
|---|---|---|---|
| BUG-A | 间奏极长时歌词死锁 | SuperLyric 不发 pause；多源仲裁 8 秒死锁 | 7-A 后 |
| BUG-B | HIDE_TO_CUTOUT 未真正实现 | tryHideIslandView已禁用（19版），岛由系统正常渲染；7-F独立实现 | **独立立项，主线跑通后合并** |
| BUG-C | LYRIC_SHORT 退出时偶尔回 NORMAL | previousState 记录时机偶尔不准 | 7-A 后 |
| BUG-F | 偶现歌词完全不显示，重启恢复 | detached View 的 post 被丢弃 | 7-A 后 |
| 信号不隐藏 | LYRIC_SHORT 时信号仍可见 | signalViews 只收录 2 个 View | **7-B 探针C** |

---

## 附录 C：关键 API 参考

### SuperLyric 3.4

```kotlin
SuperLyricHelper.setSystemPlayStateListenerEnabled(false) // 注册后立即调用！

// SuperLyricData 有效字段
hasLyric()→getLyric()  hasSecondary()→getSecondary()  hasTranslation()→getTranslation()
hasTitle()→getTitle()  hasArtist()→getArtist()

// SuperLyricLine
getText()        // @NonNull
getStartTime()   // 行开始时间 ms
getEndTime()     // 行结束时间 ms
getDelay()       // deprecated，用 endTime-startTime
getWords()       // SuperLyricWord[]，可 null

// SuperLyricWord
getWord() / getStartTime() / getEndTime()

// ⚠️ 禁止：playbackState / base64Icon / mediaMetadata（永远null或deprecated）
```

### SystemUI Hook 目标

```
超级岛：
  IslandMonitor$RealContainerIslandMonitor$stateListener$1
  方法：onIslandSizeChanged(Rect, Z) after
  ⚠️ 废弃：onIslandStatusChanged / getIslandWidth

MiuiClock：com.android.systemui.statusbar.views.MiuiClock
  Hook：构造函数 after，view.post{} 异步
  识别：getResourceEntryName(id) in ["clock", "pad_clock"]

PhoneStatusBarView：com.android.systemui.statusbar.phone.PhoneStatusBarView
  Hook：onTouchEvent(MotionEvent)
  ⚠️ beforeHook result=true → 下拉卡死（F-09）

DarkIconDispatcherImpl：com.android.systemui.statusbar.phone.DarkIconDispatcherImpl
  Hook：applyDarkIntensity(float) after，字段 mIconTint(int)

StatusBarIconControllerImpl：com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl
  Hook：setIconVisibility(String, boolean)
  ⚠️ StatusBarIconController 是接口，必须用实现类

MiuiNotificationListener：com.android.systemui.statusbar.notification.MiuiNotificationListener
  Hook：onNotificationPosted(StatusBarNotification, RankingMap) ← 必须 2 参数

锁屏容器：
  com.android.keyguard.widget.HyperOSKeyguardRootView（HyperOS 3 推荐）
  com.android.systemui.keyguard.ui.view.KeyguardRootView
  ⚠️ 方法名：依次尝试 onViewAttached / onAttachedToWindow / onFinishInflate
```

### customClockView 注入规范

```kotlin
// 时钟格式反射（依次尝试，兜底 "HH:mm"）
val fmt = listOf("mFormat", "mDescFormat", "mPattern").firstNotNullOfOrNull { name ->
    runCatching {
        clock.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(clock) as? String
    }.getOrNull()
} ?: "HH:mm"

// 可见性铁律：
// LYRIC / LYRIC_SHORT      → showCustomClock 开关控制
// MANUAL_CLOCK             → 无论如何必须隐藏（防双时钟）
// MANUAL_SIGNAL            → 隐藏
// SYSTEM_RESTORE / NORMAL  → 隐藏
fun updateCustomClockVisibility(c: LyricContainer, want: Boolean) {
    c.customClockView?.visibility =
        if (StateController.showCustomClock && want) View.VISIBLE else View.GONE
}

// ⚠️ TitleDialog 不能加载模块的 R.drawable（SystemUI ClassLoader 崩溃）
// 所有图标改为代码绘制 Bitmap
```

### 广播与资源名

```
广播：
  "updateConfig"              ← 改完设置必须发，否则 StateController 不重新加载
  ACTION_SCREEN_OFF           ← 锁屏
  ACTION_USER_PRESENT         ← 解锁

资源名（findByEntryName）：
  "system_icons"              ← 右侧系统图标容器
  "notification_icon_area"    ← 通知图标区
  "battery_meter_view"        ← 电池 View

Keyevent：87=NEXT / 88=PREVIOUS / 85=PLAY_PAUSE
```

### build.gradle

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'         version '2.2.10'
    id 'org.jetbrains.kotlin.plugin.compose'  version '2.2.10'
    id 'com.google.devtools.ksp'              version '2.2.10-2.0.2'  // 格式固定！
}
android {
    namespace = "statusbar.lyric.thirteen"
    compileSdk = 36
    defaultConfig { minSdk = 31; targetSdk = 36 }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = VERSION_21; targetCompatibility = VERSION_21 }
    kotlinOptions { jvmTarget = "21" }
}
dependencies {
    implementation 'com.highcapable.yukihookapi:api:1.3.1'
    ksp          'com.highcapable.yukihookapi:ksp-xposed:1.3.1'
    implementation 'com.github.HChenX:SuperLyricApi:3.4'  // 必须 implementation！
    compileOnly  'de.robv.android.xposed:api:82'
    implementation 'androidx.core:core-ktx:1.16.0'
    implementation 'androidx.activity:activity-compose:1.10.1'
    implementation platform('androidx.compose:compose-bom:2025.04.00')
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.ui:ui-graphics'
    implementation 'androidx.compose.foundation:foundation'
    implementation 'androidx.navigation:navigation-compose:2.9.0'
    implementation 'top.yukonga.miuix.kmp:miuix-android:0.4.3'
    implementation 'dev.chrisbanes.haze:haze:1.5.4'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1'
}
```

---

## 附录 D：调试探针

```kotlin
// 探针A：MiuiClock 时钟格式字段
param.thisObject.javaClass.declaredFields.forEach { f ->
    f.isAccessible = true
    if ("format" in f.name.lowercase() || "pattern" in f.name.lowercase())
        YLog.debug("${f.name}=${runCatching { f.get(param.thisObject) }.getOrNull()}")
}

// 探针B：岛宽度验证（确认30%阈值）
YLog.debug("[岛] w=${rect.right-rect.left} left=${rect.left} screen=$screenWidth ratio=${"%.1f".format((rect.right-rect.left)*100f/screenWidth)}%")

// 探针C：system_icons 子View枚举（采集后补全 signalViews，解决信号不隐藏）
for (i in 0 until container.childCount) {
    val child = container.getChildAt(i) ?: continue
    YLog.debug("[signal][$i] ${child.javaClass.name} id=${runCatching { child.resources.getResourceEntryName(child.id) }.getOrNull()}")
}

// 探针D：HIDE_TO_CUTOUT 岛容器查找
// ⚠️ dynamicisland 类在动态 ClassLoader，需要先 hookDynamicClassLoaders
obj.javaClass.declaredFields.forEach { f ->
    f.isAccessible = true
    val v = runCatching { f.get(obj) }.getOrNull()
    if (v is android.view.View) YLog.debug("[岛容器] ${f.name} w=${v.width}")
}

// 探针E：状态机调用栈
YLog.debug("State: $currentState → $newState\n" +
    Thread.currentThread().stackTrace.drop(1).take(5)
        .joinToString("\n") { "\t${it.className}.${it.methodName}:${it.lineNumber}" })

// 探针F：限宽坐标系验证
val loc = IntArray(2); container.layout.getLocationOnScreen(loc)
YLog.debug("[限宽] containerLeft=${loc[0]} islandLeft=${StateController.currentIslandLeft} " +
    "maxWidth=${StateController.currentIslandLeft - loc[0] - (8 * density).toInt()}")
```

---

## 附录 E：参考资料

### HyperIsland 借鉴

```kotlin
// hookDynamicClassLoaders（7-F 用）
// Hook 所有 BaseDexClassLoader 构造函数，动态 ClassLoader 创建时捕获
// 在回调里 loadClass("com.miui.systemui.statusbar.StatusBarClickTool")
// 拿到 Class 后 Hook 其内部的收缩方法，让岛执行系统原生"收进挖孔"动画

// resolveVisibleWidth（限宽计算备选）
private fun resolveVisibleWidth(view: View): Int {
    var w = Int.MAX_VALUE
    var p = view.parent
    while (p is ViewGroup) { if (p.width in 1 until w) w = p.width; p = p.parent }
    return if (w != Int.MAX_VALUE) w else view.width.coerceAtLeast(0)
}
```

### 词幕借鉴

```
D-3 StatusBarDisableHooker：
    MiuiCollapsedStatusBarFragment.disable(displayId, state1, state2, animate)
    FLAG_DISABLE_SYSTEM_INFO = 0x00800000
    (state1 and FLAG_DISABLE_SYSTEM_INFO != 0) → 全屏 → 隐藏歌词

D-4 CrashDetector（7-G 实现）：
    MainHook.onHook() 最开始
    20 秒窗口内 3 次崩溃 → isSafeMode=true → return（跳过所有 Hook）
```

### 功能联动冲突速查

| 冲突 | 级别 | 规避 |
|---|---|---|
| LyricTextView 进度着色污染锁屏 | 致命 | progressEnabled 开关，锁屏强制 false |
| LYRIC_SHORT 限宽上下排共用 layoutParams | 严重 | 两排各自独立，不互相引用 |
| 双排下排字号不同导致速度计算错误 | 严重 | 各自用自己 paint.measureText，字号 ×0.75 |
| setScrollSpeed 在 setText 之后 | 严重 | 严格顺序 |
| customClockView 与原始时钟同时可见 | 致命 | MANUAL_CLOCK 时无条件隐藏 |
| 进度 Handler 未随暂停/超时停止 | 中 | onMusicPaused 和 TIMEOUT_RESTORE 时同步停止 |
| updateConfig 后字号变化速度不更新 | 中 | updateConfig 广播回调里重新触发 calcScrollSpeed |
