# Voice 全局片头片尾跳过功能开发规划

> 用途：交给另一个 AI 或开发者直接实施。
> 编写日期：2026-10-06。
> 代码核对基线：`f827b30dc`。实施前重新检查工作区与相关源码；路径、接口以实施时的仓库为准。
> 本文是开发规划，尚未实现功能，也未执行应用构建或单元测试。

> [!NOTE]
> 变更说明（2026-10-08 追加）：第 1 节表格中的“每项 0～300 秒”是当时的规划取值，后续已两次调整，**本文其余部分未同步修改**：
>
> | 时间 | 上限 | 原因 |
> | --- | --- | --- |
> | 实施时 | 300 秒 | 按本规划 |
> | 首轮真机反馈 | 120 秒 | 滑块轨道过长，不便拖动；同时为弹窗增加数字输入 |
> | 次轮真机反馈 | 60 秒 | 实测 120 秒对片头片尾而言仍然多余 |
>
> 当前上限由 `core/data/api` 中的 `MAX_INTRO_OUTRO_SKIP_SECONDS` 单点定义，设置界面（滑块范围、数字输入纠正值）与播放层（读写归一化）都引用它。超过上限的已存值在读取时归一化为上限，不需要数据迁移。

## 1. 目标与实施默认值

在现有“偏好设置”页面增加全局片头、片尾跳过时间设置。设置行的外观与交互参照当前“跳过时间”：左侧图标、标题、秒数摘要，点击后弹出时间滑块，确认保存，取消不保存。

所有有声书共用同一组设置，实际跳过由后台播放器执行，离开播放页面、锁屏或使用通知栏控制时同样生效。

用户明确要求的是全局固定时间及与现有设置一致的显示效果。以下细节是本规划为便于直接实施选定的默认方案，不应描述为用户逐项确认过的要求：

| 项目 | 本规划采用的方案 |
| --- | --- |
| 设置入口 | “偏好设置”页面中的两个相邻设置行：“跳过片头”“跳过片尾” |
| 跳过单位 | 每个音频文件；文件内部的章节标记不重复应用 |
| 适用范围 | 所有书籍、所有文件共用设置 |
| 时间范围 | 每项 0～300 秒，最终保存整数秒 |
| 默认值 | 两项均为 0，分别表示关闭 |
| 开关 | 不增加额外总开关，0 秒即关闭该项 |
| 设置生效 | 播放中确认后生效；暂停中修改不自动播放、不自动跳转，在下次播放时应用 |
| 进度与书签 | 使用原文件时间坐标，继续显示原有时长 |

不增加按书籍覆盖、智能识别、章节类型识别、新页面或远程功能开关；不改变现有“跳过时间”的快进/后退含义。无需修改音频文件、扫描结果或 Room 表结构。

## 2. 项目结构与阅读顺序

先阅读 [AGENTS.md](../AGENTS.md)、[架构文档](architecture.md) 和 [开发说明](development.md)，遵守现有模块边界与测试要求。

项目使用 Kotlin、Jetpack Compose、Media3 / ExoPlayer、Metro、Room 和 DataStore。此功能需要改动的职责如下：

| 模块 | 职责 | 本次用途 |
| --- | --- | --- |
| `:features:settings` | 设置 UI 与状态 | 展示并编辑两个全局秒数 |
| `:core:data:api` | 数据契约与存储限定符 | 声明两个 Store 的注入限定符 |
| `:core:data:impl` | 持久化实现与注册 | 提供两个整数 DataStore |
| `:core:playback` | 后台播放及进度维护 | 计算并执行自动跳过 |
| `:core:strings` | 文案资源 | 新增默认语言与简体中文文案 |
| `:features:playbackScreen` | 播放 UI | 回归检查进度、书签定位等行为 |
| `:features:bookOverview` | 书架和完成状态展示 | 回归检查末尾跳过后的完成分类 |

通常无需新增模块、项目依赖或修改 `:app`、`:navigation`。核心播放模块不能反向依赖功能模块。

### 2.1 设置链路

```text
Settings.kt / 设置行 / 时间弹窗
    → SettingsListener
    → SettingsViewModel
    → 带限定符的 DataStore<Int>
    → Flow 更新 SettingsViewState 与 UI
```

重点阅读：

- [SeekTimeRow.kt](../features/settings/src/main/kotlin/voice/features/settings/views/SeekTimeRow.kt)：现有“跳过时间”的视觉与交互样板。
- [TimeSettingDialog.kt](../features/settings/src/main/kotlin/voice/features/settings/views/TimeSettingDialog.kt)：现有通用滑块弹窗。
- [SettingsViewModel.kt](../features/settings/src/main/kotlin/voice/features/settings/SettingsViewModel.kt)：通过 `collectAsState` 读取设置，通过 `updateData` 保存。
- [StoreModule.kt](../core/data/impl/src/main/kotlin/voice/core/data/store/StoreModule.kt)：现有 `SeekTimeStore`、`AutoRewindAmountStore` 的注册方式。

### 2.2 播放链路

```text
播放界面 / 小组件 → PlayerController → MediaController
                                         ↓
通知栏 / 蓝牙 / Android Auto → PlaybackService / MediaSession
                                         ↓
                                    VoicePlayer
                                         ↓
                                     ExoPlayer
                                         ↓
                              PositionUpdater → 书籍仓库
```

核心文件：

- [VoicePlayer.kt](../core/playback/src/main/kotlin/voice/core/playback/player/VoicePlayer.kt)：统一播放控制入口，处理恢复进度、跳转、自动回退、章节结束定时器。
- [PlaybackItems.kt](../core/playback/src/main/kotlin/voice/core/playback/session/PlaybackItems.kt)：文件位置与播放项位置的映射。
- [MediaItemProvider.kt](../core/playback/src/main/kotlin/voice/core/playback/session/MediaItemProvider.kt)：将文件的章节标记转换成经过裁剪的播放项。
- [PositionUpdater.kt](../core/playback/src/main/kotlin/voice/core/playback/playstate/PositionUpdater.kt)：在跳转、切换、暂停等事件中保存原文件坐标。
- [PlaybackModule.kt](../core/playback/src/main/kotlin/voice/core/playback/di/PlaybackModule.kt)：播放器装配和播放作用域。
- [PlaybackService.kt](../core/playback/src/main/kotlin/voice/core/playback/session/PlaybackService.kt)：服务生命周期与最终进度保存。

### 2.3 必须区分文件和内嵌章节

| 类型 | 当前代码中的含义 |
| --- | --- |
| `Book` | 一本书，包含有序的文件列表 |
| `Chapter` | 一个音频文件，带原始文件时长与 URI |
| `ChapterMark` | 文件内部的章节标记 |
| `PlaybackItem` / `MediaItem` | 播放器中的一个章节片段，不一定是完整文件 |

例如，一个 M4B 文件有 20 个内嵌章节，会生成多个播放项，但片头片尾只按这个文件应用一次。下一音频文件也不一定是下一个 `MediaItem`。

现有 `player.currentPosition` 是当前播放项内的位置；文件位置应通过 `MediaId.positionInChapter(...)` 等现有映射获得。`player.duration` 也不能直接作为整个文件的时长，应使用匹配的 `Chapter.duration`。

## 3. UI 规格

### 3.1 页面布局

在原“跳过时间”之后、“自动回退”之前插入两个设置行。其余页面顺序保持不变。

```text
偏好设置

  [现有图标]  跳过时间
              20 秒

  [时间图标]  跳过片头
              30 秒

  [时间图标]  跳过片尾
              15 秒

  [现有图标]  自动回退
              2 秒
```

要求：

1. 使用与 `SeekTimeRow` 一致的 Material 3 `ListItem`、整行点击区域和 `fillMaxWidth()`。
2. 秒数摘要复用 `StringsR.plurals.duration_seconds`。0 时显示“0 秒”，避免为关闭状态另造一套布局。
3. 复用已有 `VoiceIcons.Timelapse` 即可，无需新增矢量资源或图标依赖。
4. 保持原有字号、颜色、间距、深色主题和无障碍处理模式。
5. 设置行本身不增加开关或快捷按钮，也不跳转到新页面。

### 3.2 弹窗

分别点击两个设置行，展示各自弹窗：

```text
跳过片头

适用于所有音频文件，0 秒表示关闭。
30 秒
[0 ─────── 滑块 ───────────────── 300]

                          取消   确认
```

- 弹窗初值来自当前已保存秒数。
- 滑动仅修改临时值；点击确认才保存。
- 点击取消、返回或弹窗外部关闭时不写入 Store。
- 再次打开时读取保存值，不保留被取消的临时值。
- 范围为 0～300，沿用现有滑块四舍五入保存整数秒的行为，不必增加 299 个离散刻度。
- 为展示说明，可给 `TimeSettingDialog` 增加一个默认值为 `null` 的可选说明参数；仅新弹窗传入，旧调用方保持原有外观。
- 若 0～300 的手势精度在真机上不足以方便选取目标值，应记录问题；数字输入不是本次必做项，不应借此重做所有时间设置。

### 3.3 文案

按项目现有 XML 点分命名方式增加：

| XML key | 默认英文 | 简体中文 |
| --- | --- | --- |
| `settings.playback.skip_intro.title` | Skip intro | 跳过片头 |
| `settings.playback.skip_outro.title` | Skip outro | 跳过片尾 |
| `settings.playback.skip_intro_outro.summary` | Applies to all audio files. Set to 0 seconds to turn off. | 适用于所有音频文件，0 秒表示关闭。 |

添加到 `core/strings/src/main/res/values/strings.xml` 和 `values-zh-rCN/strings.xml`。代码中的资源标识符遵循现有生成形式，例如 `StringsR.string.settings_playback_skip_intro_title`。其他语言使用默认资源回退，不批量生成未经审核的翻译。

## 4. 播放行为契约

定义：`D` 为原文件时长，`S` 为片头时间，`E` 为片尾时间，`P` 为原文件中的当前位置；计算时统一使用 `Long` 毫秒。

在配置有效时，正文播放区间为 `[S, D-E)`。

| 场景 | 预期行为 |
| --- | --- |
| `S=0` 且 `E=0` | 保持原有播放行为，不启动片尾检查任务 |
| 从文件开头播放，`P<S` | 定位到 `S` 对应的播放项和相对位置，再播放 |
| 从正文恢复，`S≤P<D-E` | 原位置继续，不重复跳过 |
| 到达片尾，`E>0` 且 `P≥D-E` | 跳到下一文件的有效起点，或完成当前书籍 |
| `E=0` | 文件结尾由原有自然播放结束逻辑处理 |
| 同一文件的正文内嵌章节切换 | 不重复跳过该章节开头或末尾 |
| 片头范围跨越多个内嵌章节 | 一次定位到 `S` 对应的章节片段 |
| 片尾范围覆盖多个内嵌章节 | 从阈值处直接跳到下一文件，不逐个播放片尾章节 |
| 下一文件因过短禁用跳过 | 从该文件 0 开始，不能直接丢弃整个文件 |
| `D≤0`、时长未知或位置无效 | 本次检查不跳转，等待有效数据 |
| `S+E≥D` | 该文件同时禁用片头和片尾跳过，完整播放；包括只有一项非零且超过时长的情况 |
| 播放中修改设置 | 使用新的两个缓存值检查当前位置；正文内不强制重播 |
| 暂停中修改设置或手动定位 | 保持暂停与手动位置，下次播放时应用规则 |
| 播放中手动拖动/书签跳转到片头 | 校正到 `S` |
| 播放中手动拖动/书签跳转到片尾 | 应用片尾跳过规则 |
| 播放中手动定位到正文 | 保留目标位置 |
| 暂停自动回退进入片头 | 暂停时保留原自动回退行为；恢复时重新校正到 `S` |
| 倍速或跳过静音 | 依据真实音频位置判断，不按经过的现实秒数累加 |
| 后台、锁屏、通知栏或蓝牙播放 | 与前台使用同一套规则 |

需要听被跳过的内容时，将对应设置改为 0。第一版不增加“本次忽略跳过”的临时模式。

示例：文件长 10 分钟，片头 30 秒、片尾 15 秒。首次从 00:30 播放，到 09:45 跳到下一文件的 00:30；恢复到 05:00 时仍从 05:00 播放。若下一文件仅有 40 秒，因 30+15≥40，下一文件完整播放。

## 5. 存储与设置状态改造

### 5.1 两个独立的整数 DataStore

沿用现有全局时间设置模式：

| 项目 | 片头 | 片尾 |
| --- | --- | --- |
| 限定符 | `SkipIntroSecondsStore` | `SkipOutroSecondsStore` |
| 类型 | `DataStore<Int>` | `DataStore<Int>` |
| 文件名 | `skipIntroSeconds` | `skipOutroSeconds` |
| 默认值 | `0` | `0` |

修改 `core/data/api/.../store/StoreQualifiers.kt`，新增两个 `@Qualifier`。

修改 `core/data/impl/.../store/StoreModule.kt`，参照 `SeekTimeStore`，用 `@Provides`、`@SingleIn(AppScope::class)` 和对应限定符注册，内部调用 `VoiceDataStoreFactory.int(...)`。

两个弹窗独立保存，因此不要求两个 Store 原子更新。播放层可以使用 `combine` 收集成一组内存配置，每次发射即代表当时的有效设置。

新用户与旧版本升级用户均使用 0 默认值，不新增 SharedPreferences 迁移，不将字段放入 `BookContent`，不增加 Room migration。

写入边界与播放读取边界都将数值限制在 `0..300`，转换毫秒时先转 `Long`。若共享范围常量，放在双方可依赖的 `core/data/api` 小型契约文件中，不能让播放模块依赖设置模块；不必为两个数字设计通用配置框架。

### 5.2 SettingsViewState

增加：

```kotlin
val skipIntroInSeconds: Int
val skipOutroInSeconds: Int
```

在 `Dialog` 中增加 `SkipIntro`、`SkipOutro`，同步更新 `preview()`、所有构造调用和测试夹具。

### 5.3 SettingsListener 与 SettingsViewModel

增加四个事件，并更新 `SettingsListener.noop()`：

```kotlin
fun onSkipIntroRowClick()
fun onSkipOutroRowClick()
fun skipIntroAmountChanged(seconds: Int)
fun skipOutroAmountChanged(seconds: Int)
```

ViewModel 注入两个 Store，使用现有 `remember { store.data }.collectAsState(initial = 0)` 模式读取，点击行设置相应对话框状态，确认后在现有 `mainScope` 中调用 `updateData`。

继续由弹窗确认流程调用 `dismissDialog()`，不需要新增导航目的地、ViewEffect 或业务服务。

### 5.4 Settings.kt 与设置行

新增一个小文件，例如 `IntroOutroSkipRows.kt`，包含两个语义明确的设置行及弹窗包装。可以在文件内部共享一个私有行组件，但不要为此重构其他偏好设置。

在 `Settings.kt` 中插入两行，并补齐底部 `when (viewState.dialog)` 的两个分支。确认 Preview、默认监听器及所有 exhaustive `when` 均可编译。

## 6. 核心播放实现

### 6.1 推荐结构

在 `core/playback` 内分离“纯规则计算”和“播放器事件/跳转执行”：

- 新增内部规则文件，例如 `player/IntroOutroSkipPolicy.kt`，输入文件时长、位置和配置，输出无需处理、跳到文件内位置、结束当前文件等决策。
- 在 `VoicePlayer` 接入设置缓存与规则执行。若事件、Job 管理使类明显膨胀，可提取一个由 `VoicePlayer` 持有的内部协调类；不需要新增模块或通用插件机制。
- 规则函数不读取 DataStore、数据库或 Android Player，便于精确测试边界。
- 规则计算负责文件坐标；执行层借助现有映射定位到实际 `MediaItem`。

文件名和内部结果类型可调整，但职责与下列验收行为应保持一致。

### 6.2 不改变裁剪边界或持久化坐标

继续使用 `MediaItemProvider` 原有内嵌章节裁剪配置。通过 `seekTo(index, position)` 跳过头尾，避免新增一套被裁剪后的时间轴。

原因：当前 `PositionUpdater`、书签、`PlayerController.setPosition()` 和播放 UI 依赖原文件坐标。若修改每个播放项的裁剪起点，还需同步调整媒体 ID、持久化换算和时长显示，会扩大本次改动。

整个文件的进度及书籍总时长维持现有含义，不扣除被跳过时间。播放界面显示章节相对位置时，也沿用原有换算。

### 6.3 缓存配置和当前书籍映射

1. 在播放作用域收集两个 Store，保存经过范围校验的内存配置。
2. `setBook(...)` 已读取书籍，可以保存当前书籍所需的文件与播放项映射；不在每次检查时重新读数据库。
3. 每次执行前校验当前播放器的 book ID、文件 ID、播放项仍与决策匹配。换书后丢弃旧跳转任务与旧目标。
4. 异步任务暂停后恢复时重新校验，避免设置读取完成后跳错书。
5. 配置未加载完成时，不应把内存初值 0 当成用户保存值而先播放出片头。首次启动应在配置就绪后校正位置再放行播放。
6. 优先利用现有作用域与挂起流程；不要在高频回调中使用 `runBlocking` 或 `data.first()`。现有 `setBook` 本身有同步读取，但不是扩展阻塞行为的理由。

### 6.4 触发点与检查任务

| 触发点 | 操作 |
| --- | --- |
| 首次开始/恢复播放 | 在发出音频前校正片头或已处于片尾的恢复位置 |
| 自然切换播放项 | 按新文件位置检查；同一文件正文章节不重复跳过 |
| 播放中位置不连续事件 | 校验快进、后退、拖动和书签跳转后的目标 |
| 播放状态恢复为可播放 | 处理缓冲结束后仍处于跳过区间的情况 |
| 全局设置变化 | 更新缓存，正在播放时立即重新判断 |
| 连续播放 | 定期检查是否到达文件片尾 |
| 暂停、结束、换书、释放 | 停止或重建相应任务，清除失效决策 |

持续检查先采用约 200 毫秒间隔的播放作用域 Job。只在配置启用片尾跳过且实际播放时运行，暂停、缓冲或无有效媒体项时停止。仅启用片头跳过时，依靠开始播放及事件检查即可。

片尾检查依据当前 Player 音频位置，不能使用数据库中的上次保存位置。现有持久化间隔可能是 400 毫秒，也可能因实验配置变成 5 分钟，与本功能无关。

200 毫秒是检查周期，不是系统实时保证。常规条件下的越界量约为“播放速度 × 检查间隔”，另有调度与定位延迟。测试应区分精确规则判断和真机听感，不承诺采样级截断。若实测明显听到片尾或影响体验，再优化边界调度；不通过修改文件裁剪来绕开坐标问题。

不要从播放页面 Composable、页面 ViewModel 或 UI 进度刷新定时器执行跳过，否则后台行为会依赖页面是否存在。

### 6.5 文件内位置与跨文件目标

从当前 `MediaId` 得到文件 ID，并用 `positionInChapter(player.currentPosition)` 得到文件位置 `P`。

规则伪代码：

```text
若未准备好 / 没有合法当前文件或位置：不处理
将设置转为 Long 毫秒
若 D 无效或 S + E >= D：该文件不自动跳过
若 P < S：请求定位到当前文件 S
否则若 E > 0 且 P >= D - E：请求结束当前文件
否则：不处理
```

执行文件内定位：

```text
book.playbackItemForPosition(chapterId, targetFilePosition)
    → playbackItem.index
    → playbackItem.positionInMediaItem(targetFilePosition)
    → 底层 player.seekTo(index, relativePosition)
```

执行片尾跳过时，先查找 `book.chapters` 中当前文件的下一个文件，再计算该文件自己的有效起点。若下一文件配置不适用，目标为 0，否则为 `S`。不要调用 `VoicePlayer.seekToNext()`，它目前被重定向成快进；`forceSeekToNext()` 也只跳下一个播放项，可能仍在当前文件内。

现有章节末尾有 `duration - 1` 与相对时长的边界约定，必须复用映射并覆盖 ±1 毫秒测试，不顺手修改全部章节端点语义。

### 6.6 防止循环、重复跳转和生命周期泄漏

- 检查与播放器调用在其 application looper 上执行；当前播放作用域使用 `Dispatchers.Main.immediate`。
- 监听底层 Player 的真实状态。`VoicePlayer.getPlaybackState()` 会把缓冲映射成 READY，不能用这个包装后的状态判断底层是否就绪。
- 同一时刻只保留一个片尾检查 Job，避免多次 `play()` 或事件通知启动多个循环。
- 自动跳转会触发新的位置事件，应合并重入或记录待完成目标；到达目标后重新读取状态，不重复发出同一个 seek。
- 不采用递归逐个跳过章节或文件的算法；短文件应正常播放，因此也不应出现连续丢弃整本书的行为。
- 准备期间的校正不能无意设置 `playWhenReady=true`，暂停或音频焦点抑制后也不能由检查任务自行恢复播放。
- 若增加 `release()` 覆写，移除自己注册的 listener，取消自己创建的 Job，再调用既有释放链路。不要从 `VoicePlayer` 取消不归它所有的整个共享作用域。
- 服务已经负责最终进度保存和作用域取消，遵守其释放顺序，不向已释放 Player 发送命令。

## 7. 与现有行为的兼容性

### 7.1 章节结束睡眠定时器

现有 `VoicePlayer` 在 `DISCONTINUITY_REASON_AUTO_TRANSITION` 或 `STATE_ENDED` 时检查 `SleepTimerState.Enabled.WithEndOfChapter`。自动片尾跳转通常是主动 seek，不能假设它会触发现有的自然切章分支。

本规划采用“章节结束停止优先”的规则：

1. 正文内嵌章节自然结束时，保留现有停止行为，不把睡眠章节单位改成整个文件。
2. 自动片尾跳过若跨出当前章节片段，应先停止播放并禁用章节结束定时器，再定位到下一文件的有效起点；下一文件不能自动开始。
3. 自动片头校正若跨出当前章节片段且该定时器已启用，也遵循停止优先；停在计算出的目标位置，等待用户下次播放。
4. 在同一章节片段内进行片头校正，不应提前触发章节结束停止。
5. 不把所有手动 seek 统一当成自然章节结束；普通用户定位沿用原有定时器语义，只有本功能自身引发的跨章节自动跳转执行上述优先规则。
6. 不改变按时长睡眠定时器的累计和淡出逻辑，也不能在其暂停后由自动跳过重新开始播放。

可把现有暂停并禁用定时器的小函数提升到 `VoicePlayer` 私有方法，供自然结束与自动跳过共用。不要用合成 AUTO_TRANSITION 事件欺骗其他监听器。

### 7.2 最后一个文件与完成状态

书架的 [BookOverviewCategory.kt](../features/bookOverview/src/main/kotlin/voice/features/bookOverview/overview/BookOverviewCategory.kt) 当前用“总进度达到总时长减 5 秒”判断已完成。

因此，最后一个文件到达片尾阈值后，不能只在 `D-E` 暂停。需要让播放器位置和持久化位置都到达最终文件的末尾，并停止实际播放。

优先实现路径：将底层播放器定位到最后一个播放项的有效末尾，利用并验证现有结束与保存事件链路。是否可靠进入 `STATE_ENDED` 必须以当前 Media3 版本的集成测试为依据，不能仅凭一次 `seekTo` 调用就宣称完成。

必须满足的结果：

- 不播放被跳过的片尾，不自动切换到其他书。
- `isPlaying=false`，通知栏与页面不显示正在播放。
- 保存最后一个文件及其有效末尾位置，允许现有映射产生的约 1 毫秒端点差异。
- 书架归类为已完成，不残留“还有 30 秒”的未完成进度。
- 服务释放前的 `flushPositionNow()` 不会把完成位置覆盖回片尾起点。
- 重复事件不导致反复 seek、回到片尾起点或死循环。
- 再次点击播放不循环重试片尾跳过；用户显式定位到正文或文件开头后仍可重新播放。

若现有事件链路不足，最小限度补充显式结束/保存逻辑，并在播放器位置稳定后保存。不要只改书架完成阈值，也不要让核心模块依赖书架分类代码。

### 7.3 自动回退与进度写入

`VoicePlayer.pause()` 会通过 `setPlayWhenReady(false)` 触发自动回退。末尾自动完成和睡眠定时器停止，应避免误走这个用户暂停分支，否则可能把最终位置拉回到片尾。

普通用户暂停仍保留自动回退；恢复后再按片头规则校正。跳转造成的位置保存尽量复用 `PositionUpdater`，不要另外开启高频数据库写循环。

### 7.4 初次加载与暂停状态

只有准备书籍但未请求播放时，不因全局设置改变已恢复的位置。首次明确请求播放时，先应用已加载配置，再继续播放。

如果在准备期间用户又暂停、切书或服务释放，先前等待配置的播放请求必须失效，不能在等待完成后再次把播放打开。

## 8. 文件改动清单

以下路径均相对于仓库根目录；新增文件名是建议，可按最终实现调整。

| 路径 | 操作与用途 |
| --- | --- |
| `core/data/api/src/main/kotlin/voice/core/data/store/StoreQualifiers.kt` | 新增两个限定符 |
| `core/data/impl/src/main/kotlin/voice/core/data/store/StoreModule.kt` | 注册两个默认 0 的整数 Store |
| `features/settings/src/main/kotlin/voice/features/settings/SettingsViewState.kt` | 两个秒数字段、两个 Dialog 类型、Preview |
| `features/settings/src/main/kotlin/voice/features/settings/SettingsListener.kt` | 四个事件及 noop 实现 |
| `features/settings/src/main/kotlin/voice/features/settings/SettingsViewModel.kt` | Store 注入、读取、校验和保存 |
| `features/settings/src/main/kotlin/voice/features/settings/views/Settings.kt` | 插入设置行、接入弹窗分支 |
| `features/settings/src/main/kotlin/voice/features/settings/views/IntroOutroSkipRows.kt` | 新增行与弹窗包装 |
| `features/settings/src/main/kotlin/voice/features/settings/views/TimeSettingDialog.kt` | 可选说明参数，保持旧调用默认效果 |
| `core/strings/src/main/res/values/strings.xml` | 默认英文文案 |
| `core/strings/src/main/res/values-zh-rCN/strings.xml` | 简体中文文案 |
| `core/playback/src/main/kotlin/voice/core/playback/player/IntroOutroSkipPolicy.kt` | 新增纯规则计算 |
| `core/playback/src/main/kotlin/voice/core/playback/player/VoicePlayer.kt` | 配置缓存、事件检查、跳转、定时器及结束处理 |
| `features/settings/src/test/kotlin/voice/features/settings/SettingsViewModelTest.kt` | 设置状态与持久化交互测试 |
| `core/playback/src/test/kotlin/voice/core/playback/player/IntroOutroSkipPolicyTest.kt` | 新增规则边界测试 |
| `core/playback/src/test/kotlin/voice/core/playback/player/VoicePlayerTest.kt` | 新增真实事件链路测试并更新构造参数 |
| `core/playback/src/test/kotlin/voice/core/playback/session/PlaybackItemsTest.kt` | 必要时补充跨章节映射与端点测试 |

仅在证据表明确有必要时改动 `PlaybackModule.kt`、`PlaybackService.kt`、`PositionUpdater.kt` 或新增生命周期协调类。不要为满足这张表而强行修改无需改动的文件。

实施时搜索 `SettingsViewState(`、`SettingsViewModel(`、`VoicePlayer(` 和 `SettingsListener` 的全部调用方，更新测试夹具；不只修复生产代码中的构造器。

## 9. 测试计划

### 9.1 规则单元测试

使用纯函数测试精确断言，不依赖真实时钟：

| 编号 | 输入/条件 | 预期 |
| --- | --- | --- |
| P01 | 两项均为 0 | 无跳转 |
| P02 | `D=600s, S=30s, E=15s, P=0` | 定位到 30s |
| P03 | 同上，`P=30s` | 无跳转 |
| P04 | 同上，`P=300s` | 无跳转 |
| P05 | 同上，`P=584999ms` | 无片尾跳转 |
| P06 | 同上，`P=585000ms` | 结束当前文件 |
| P07 | `S=0` 或 `E=0` | 另一项可独立生效；E=0 不接管自然末尾 |
| P08 | `S+E=D` 与 `S+E>D` | 当前文件完整播放 |
| P09 | `D≤0`、未知/非法位置 | 无跳转或越界计算 |
| P10 | 负数/大于 300 的配置 | 按统一范围归一化，Long 毫秒计算正确 |
| P11 | 片头落在第二/第三个内嵌章节 | 对应文件目标与播放项相对位置正确 |
| P12 | 片尾起点在倒数第二个内嵌章节 | 指向下一文件，而非同文件下一章节 |
| P13 | 下一文件过短 | 下一文件起点为 0 |
| P14 | 已是最后文件 | 返回完成决策，没有无效下一索引 |
| P15 | 章节起点、末尾及 ±1ms | 保持现有位置映射约定 |

### 9.2 设置测试

扩展现有 `SettingsViewModelTest`，沿用 `MemoryDataStore`、Molecule 和 Turbine：

- 初始值为 0；已有非零 Store 值被正确展示。
- 点击两行分别打开正确的 Dialog，关闭后 Dialog 为空。
- 保存片头仅更新片头 Store，保存片尾仅更新片尾 Store。
- 保存 0、300 和越界输入的结果符合范围约束。
- Store 外部更新后 viewState 跟随变化。
- Preview 与 `SettingsListener.noop()` 保持可用。
- 弹窗取消不保存由组件交互验证；若未建立适用的 UI 测试设施，按手工验收验证并说明，不用纯 ViewModel 测试冒充取消按钮的 UI 覆盖。
- 不编写只验证 DataStore 第三方实现的重复测试；磁盘保存和重启恢复用已有存储测试设施或手工重启验证。

### 9.3 播放集成测试

在 `VoicePlayerTest` 的现有 Media3 测试工具与 Robolectric 环境中覆盖：

1. 首次播放不从已配置片头的 0 秒开始；正文恢复位置保持不变。
2. 配置延迟加载时，不先按默认 0 播放；等待期间暂停/换书不会被旧请求恢复播放。
3. 多文件自动衔接到下一文件的有效起点。
4. 同一文件的多个内嵌章节不重复跳过。
5. 片头跨多个章节、片尾覆盖多个章节时目标正确。
6. 播放中更新设置立即检查；暂停中更新不跳转且不开始播放。
7. 播放中的拖动、快进、回退与书签目标落入跳过区间时行为正确。
8. 下一/上一章节按钮仍保留现有语义，配置为 0 时现有测试全部通过。
9. 短文件完整播放，不连跳整本书。
10. 最后文件完成时停止播放，仓库保存最终文件末尾；服务最终 flush 不回退进度。
11. 章节结束定时器覆盖自然切章、自动片尾跳转和跨章节片头校正；不误改普通手动 seek 语义。
12. 普通暂停自动回退仍可用，自动完成不触发回退。
13. 重复事件只产生必要跳转；换书后旧 Job 不影响新书；release 后不再调用 Player。
14. 倍速依据音频位置触发；持久化实验开关开启后，自动跳过仍独立正常工作。

现有测试夹具的两个注意点：

- `FakeMediaSource` 当前用文件时长构造每个假播放项，不能据此认定已经模拟了真实章节裁剪。为新映射/片尾测试创建语义正确的片段时长夹具或使用真实裁剪源，验证原文件位置与播放项位置的区别；不要无范围限制地重写全部旧测试。
- 现有断言辅助方法会调用 `advanceUntilIdle()`。新增无限定时循环后可能导致测试一直推进，应用 `backgroundScope`、可取消 Job 及有限的 `advanceTimeBy` / `runCurrent` 控制。还要区分协程测试时钟与 ExoPlayer 测试时钟，不能假设推进一个就会推进另一个。

### 9.4 手工验收

准备：一本包含至少两个普通音频文件的书、一个含多个内嵌章节的 M4B 文件、一段不足 45 秒的短音频。

| 步骤 | 验收结果 |
| --- | --- |
| 打开偏好设置 | 两个新增行与“跳过时间”风格、宽度、摘要格式一致 |
| 设置片头 30s、片尾 15s | 确认后摘要更新；取消不改变保存值 |
| 重启 App | 两个值仍存在 |
| 播放普通文件 | 从 30s 起播；到片尾阈值切入下一文件的有效起点 |
| 从正文退出再恢复 | 正文位置继续，不重播片头或丢失进度 |
| 播放 M4B 中间章节 | 内嵌章节起点不重复减去/跳过 30s |
| 播放短文件 | 因配置覆盖整个文件而完整播放 |
| 开启“章节结束时停止” | 自动跳过不使下一章继续出声 |
| 跳过最后文件片尾 | 播放停止、通知状态正确、书架显示已完成 |
| 在暂停和播放中修改设置 | 分别符合延后应用与即时检查规则 |
| 关闭页面并锁屏 | 后台跳过正常，无持续唤醒 UI 的依赖 |
| 蓝牙/通知栏控制、倍速及跳过静音 | 原有控制功能可用，跳过按音频时间生效 |
| 两项重设为 0 | 原有完整播放行为恢复 |
| 深色模式、大字体、屏幕阅读器 | 行与弹窗可读、可操作、不截断关键信息 |

无设备时必须说明未完成哪些手工验收，不将单元测试结果代替实际后台听感验证。

## 10. 实施顺序与检查命令

### 阶段 A：确认基线与准备

1. 阅读仓库约定，检查 `git status --short`，保护已有用户改动。
2. 核对本文涉及的核心类，确认尚无同类功能，避免重复实现。
3. 明确采用第 1、3、4 节的默认方案；仅遇到会改变产品含义的冲突时再向用户澄清。
4. 不升级依赖、不改签名/发布/CI，不执行修改全局 Gradle 配置的脚本作为常规步骤。

### 阶段 B：存储与设置 UI

完成两个 Store、状态、事件、行、弹窗及文案，再运行设置模块测试：

```bash
./gradlew :features:settings:testDebugUnitTest
```

阶段验收：可修改两个值、取消不保存、原有设置行为不变。UI 可展示只是中间结果，不能据此宣称功能完成。

### 阶段 C：纯规则与映射

先完成规则与边界测试，再接入播放器：

```bash
./gradlew :core:playback:testDebugUnitTest --tests '*IntroOutroSkipPolicyTest'
```

实际测试类名不同则调整过滤条件。覆盖短文件、片头片尾独立开启、跨内嵌章节映射及末尾决策。

### 阶段 D：后台播放集成

接入 VoicePlayer，完成配置加载、事件、Job 管理、定时器优先和最终进度保存，再运行：

```bash
./gradlew :core:playback:testDebugUnitTest
```

不要为了绕过新循环导致的测试挂起而删除旧测试；修正任务生命周期与测试时钟控制。

### 阶段 E：全量检查与真机验收

因本次涉及核心播放与跨模块设置契约，按仓库要求执行：

```bash
./gradlew voiceUnitTest
./gradlew :app:assembleFreeDebug
```

如果实际修改了数据实现测试或存储行为，可先加跑 `./gradlew :core:data:impl:testDebugUnitTest`。采用 `freeDebug` 进行本地验证，无需引入 Play/Firebase 凭据。

完成第 9.4 节手工验收，检查最终 diff。格式由仓库 hook 处理，不运行全仓格式重写。若命令失败，记录具体失败任务、错误和与本次改动的关系；环境阻塞不能写成测试通过。

## 11. 最终交付检查表

- [ ] 偏好设置中有两个与“跳过时间”样式一致的设置行。
- [ ] 两项独立确认保存、取消不保存，0 秒关闭，重启后保留。
- [ ] 全局应用到每个音频文件，内嵌章节不重复跳过。
- [ ] 后台、锁屏及各控制入口共用核心播放实现。
- [ ] 首次配置加载、正文续播、拖动和暂停恢复行为明确且经过测试。
- [ ] 短文件、非法时长、末尾端点和连续事件不会导致循环或越界。
- [ ] 章节结束睡眠定时器优先，普通暂停自动回退保持正常。
- [ ] 最后文件正确结束并保存完成进度，通知栏与书架状态一致。
- [ ] 暂停/释放时无遗留检查任务，换书后无旧任务影响新书。
- [ ] 原文件坐标、书签、章节导航、原有快进步长和时长显示保持兼容。
- [ ] 新行为有规则测试、设置测试和关键播放集成测试。
- [ ] 相关测试、全量单元测试和 freeDebug 构建通过，或有具体未执行原因。
- [ ] 默认英文和简体中文文案齐全，无无关依赖、数据库或架构改动。

接手 AI 最终应提交：代码与测试、主要改动说明、实际执行的命令及结果、手工验收结果和仍存在的限制。本文中的建议 API 名称可调整，但不能省略这些行为与验证要求。
