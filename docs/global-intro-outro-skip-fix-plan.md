# Voice 片头片尾跳过功能：代码审查问题修正方案

> 用途：交给另一个 AI，在现有实现上进行小范围修正。
> 日期：2026-10-06。
> 依据：当前工作区中的实现及静态代码审查，原仓库 HEAD 为 `f827b30dc`；功能代码尚在未提交的工作区中。
> 本文只提供修正方案，不代表已完成修复或运行复现。编写本文时未修改应用代码，也未运行测试、构建或 lint。

> [!NOTE]
> 变更说明（2026-10-08 追加）：F1、F2、T1 已按本文修复，回归用例在旧实现下会失败，见提交「Apply the intro and outro skip review fixes」。第 1 节“本次不改动……时间范围”只对当次修正成立；其后一次真机反馈把片头片尾上限从 120 秒改为 60 秒（见[原开发规划](global-intro-outro-skip-plan.md)顶部的变更说明），该改动只涉及 `MAX_INTRO_OUTRO_SKIP_SECONDS` 常量与相应测试，不影响本文的修正内容。

## 1. 修正范围

处理两个问题，并补足直接相关的测试：

| 编号 | 优先级 | 问题 |
| --- | --- | --- |
| F1 | P2 | 最后一集完成后，再次定位到同文件片尾，可能被完成标记阻止再次跳过 |
| F2 | P2 | 暂时失去音频焦点等播放抑制状态下，检查任务仍运行，设置变化仍可能跳转进度 |
| T1 | 测试缺口 | 现有片尾用例主要通过 `seekTo()` 触发，没有独立覆盖定时检查跨过片尾阈值的路径 |

本次不改动设置页面、DataStore 注册、文案、时间范围、按文件生效规则、音频裁剪或数据库结构。保留已有用户代码与未跟踪文件，不重置工作区。

先读 [AGENTS.md](../AGENTS.md)、[架构文档](architecture.md) 和 [原开发规划](global-intro-outro-skip-plan.md)。本文细化原规划的完成去重、播放状态判断与测试部分。

## 2. 相关源码

主要修改：

- [VoicePlayer.kt](../core/playback/src/main/kotlin/voice/core/playback/player/VoicePlayer.kt)：完成标记、跳过事件与检查任务。
- [VoicePlayerIntroOutroSkipTest.kt](../core/playback/src/test/kotlin/voice/core/playback/player/VoicePlayerIntroOutroSkipTest.kt)：已有播放集成测试与夹具。

按需新增一个仅用于测试的文件，例如 `core/playback/src/test/kotlin/voice/core/playback/player/VoicePlayerIntroOutroSchedulingTest.kt`，用于可控 Player 状态与定时调度测试。

通常无需修改 `IntroOutroSkipPolicy.kt`：两个问题发生在状态管理与执行层，纯规则的片头、片尾判断本身不需要改变。

## 3. F1：完成去重必须同时检查当前位置

### 3.1 当前问题

当前 `finishLastFile()` 的核心逻辑是：

```kotlin
if (outroSkippedChapterId == chapter.id) return
val last = book.playbackItems().lastOrNull() ?: return
outroSkippedChapterId = chapter.id
player.seekTo(last.index, last.mark.durationMs)
```

在 `applyIntroOutroSkip()` 中，只有判断结果不是 `SkipOutro` 才清除该标记。

因此，标记实际表达了“这个文件以前完成过”，却被用来判断“当前位置已经在最终末尾”。两者并不等价。

典型触发路径：

1. 最后一个文件长 600 秒，片尾设置为 15 秒。
2. 到达 585 秒，自动定位到文件最终末尾，记录文件 ID。
3. 同一播放器、同一书籍未重新加载，通过媒体控制器再次定位到 590 秒。
4. 新位置仍在片尾区间，规则继续返回 `SkipOutro`，完成标记没有清除。
5. `finishLastFile()` 因文件 ID 相同直接返回，590 秒处的片尾可能正常播放。

某些 UI 路径会重新装载书籍并清除标记，不能据此认为问题消失。修正与回归测试应直接覆盖“不重新装载书籍”的媒体控制路径。

### 3.2 推荐最小修正

保留现有文件 ID 标记，但仅在下面三个条件同时成立时忽略重复处理：

1. 标记对应当前文件。
2. 当前播放项就是最终播放项。
3. 当前播放项相对位置已经处于最终目标末尾的极小容差内。

建议代码形态如下，按仓库现有格式调整：

```kotlin
private fun finishLastFile(
  book: Book,
  chapter: Chapter,
) {
  val last = book.playbackItems().lastOrNull() ?: return
  val target = SkipTarget(
    mediaItemIndex = last.index,
    positionMs = last.mark.durationMs,
  )

  if (outroSkippedChapterId == chapter.id && isAtFinalEnd(target)) {
    return
  }

  // 先记录，再 seek；seek 可能产生位置回调。
  outroSkippedChapterId = chapter.id
  player.seekTo(target.mediaItemIndex, target.positionMs)
}

private fun isAtFinalEnd(target: SkipTarget): Boolean {
  val positionMs = player.currentPosition
  return player.currentMediaItemIndex == target.mediaItemIndex &&
    positionMs >= 0L &&
    abs(positionMs - target.positionMs) <= FINAL_END_POSITION_TOLERANCE_MS
}

private const val FINAL_END_POSITION_TOLERANCE_MS = 5L
```

`5ms` 是针对最终末尾的建议容差，与当前末尾测试采用的量级一致；它不是经过新增真机验证的精度结论。实际实现需以现有夹具和底层报告位置为依据，保持毫秒级，不得扩大到秒级来掩盖定位错误。

不要直接把原来的全局 `END_POSITION_TOLERANCE_MS` 放宽，因为 `isAt()` 还参与片头及其他跳转去重。最终末尾的容差应独立命名、只用于完成处理。

这个方案允许完成标记继续存在，但标记不能再阻止用户离开末尾后重新应用规则。保留现有的换书清零、进入正文时清零等逻辑即可，不必再为每一种用户操作维护一个状态机。

### 3.3 必须保留的行为

- 末尾重复回调、重复 tick 不会反复 seek。
- 同文件重新定位到片尾后，仍会定位到最终末尾。
- 定位到正文后可以继续播放，不会被直接送回末尾。
- 暂停时手动定位不立即跳转，恢复播放时才校正。
- 换书后不会沿用旧文件的完成标记。
- 保留底层自然结束和 `PositionUpdater` 的既有保存路径。
- 不通过 `VoicePlayer.pause()` 完成书籍，避免引入用户暂停的自动回退。
- 不把 `STATE_ENDED` 单独当成“已在正确末尾”的判断依据，仍校验目标位置。

### 3.4 不推荐的处理

不要在每个 `DISCONTINUITY_REASON_SEEK` 上无条件清空完成标记。自动完成本身也通过 seek 执行，无法仅凭该 reason 区分用户操作与本功能操作，可能重新制造重复跳转。

也不要保留“文件 ID 相同就返回”的判断，仅尝试在播放按钮处理里清空标记；直接媒体控制器定位和其他事件入口仍可能绕过它。

## 4. F2：区分实际播放与播放意图

### 4.1 当前问题

目前检查任务的启动条件主要是：

```text
片尾设置非零 && playWhenReady && STATE_READY
```

`checkIntroOutroSkipWhilePlaying()` 也只判断 `playWhenReady`。

`playWhenReady` 表示播放意图。暂时失去音频焦点等播放被抑制的场景中，底层可能仍保留播放意图且处于 READY，但实际上没有出声。此时：

- 200 毫秒检查 Job 仍运行。
- 更新设置时可能跳转当前文件或完成最后文件。
- 仅收到播放抑制变化时，现有监听器没有对应的任务启停处理。

### 4.2 两类入口必须分开

| 入口 | 应依据的条件 |
| --- | --- |
| 持续片尾 tick、设置变化、普通播放事件 | 底层 `player.isPlaying` 为 true |
| 用户明确请求开始播放前的初始校正 | 配置就绪且请求有效；允许此时 `isPlaying=false` |

不能把 `if (!player.isPlaying) return` 放进 `applyIntroOutroSkip()`。该函数还供首次播放前调用；若在那里限制，会破坏“先跳过片头，再开始播放”。

继续保留 `setPlayWhenReady(true)` 和 `schedulePlayAfterSkipConfigLoaded()` 中的直接校正调用，以及睡眠定时器要求保持停止时的返回处理。显式播放前校正是例外，不代表设置变化可以在抑制状态下校正。

### 4.3 修改事件检查入口

```kotlin
private fun checkIntroOutroSkipWhilePlaying() {
  if (!player.isPlaying) return
  applyIntroOutroSkip()
}
```

这里必须访问构造器持有的底层 `player`，不要基于 `VoicePlayer.getPlaybackState()` 推导是否播放。包装类当前会把 BUFFERING 映射为 READY。

### 4.4 统一片尾任务运行条件

提取一个很小的私有判断，供启动和每次 tick 共用：

```kotlin
private fun shouldRunOutroChecks(): Boolean {
  return (skipConfig.value?.outroMs ?: 0L) > 0L &&
    player.isPlaying &&
    player.currentMediaItemIndex != C.INDEX_UNSET
}
```

`updateOutroCheckJob()` 按以下流程管理任务：

```text
若 shouldRunOutroChecks() 为 false：
    取消已有 Job
    将字段设为 null
    返回

若已有 active Job：返回

创建 Job：
    在协程仍 active 且 shouldRunOutroChecks() 为 true 时：
        delay(200ms)
        再检查 shouldRunOutroChecks()
        若已不满足则退出
        调用 applyIntroOutroSkip()
```

延迟前后都检查状态，避免等待期间发生暂停、抑制、设置关闭或释放，随后仍执行一次过期跳转。

无需为了清理已结束 Job 引入复杂框架；后续用 `isActive` 判断即可。若使用 `finally` 清空字段，必须确认字段仍指向该 Job，避免旧任务结束时误清掉新任务。

### 4.5 监听实际播放状态变化

在已有 `introOutroSkipListener` 中增加：

```kotlin
override fun onIsPlayingChanged(isPlaying: Boolean) {
  checkIntroOutroSkipWhilePlaying()
  updateOutroCheckJob()
}
```

两个方法均重新读取底层当前状态，不仅依赖回调参数。事件处理可能嵌套，前面的跳过可能已经引发暂停、缓冲或结束。

先检查位置、再决定启动任务，可以避免检查本身触发睡眠定时器停止后又误启一个 Job。原有 `onPlaybackStateChanged`、`onPlayWhenReadyChanged` 和配置变化中的启停调用可以保留，只要任务管理保持幂等。

预期结果：

- 实际播放变为 false：取消片尾任务，不因设置变化自动定位。
- 音频焦点恢复、实际播放变为 true：立即用最新配置检查当前位置，再按需启动一个任务。
- 缓冲结束后的实际播放恢复也走同一入口。
- 焦点恢复期间，不由本功能主动调用 `play()`；是否恢复播放仍由既有播放器机制决定。
- 仅开启片头跳过时，不启动轮询；实际播放恢复事件仍会检查片头。
- 不将恢复事件的校正描述为采样级无声保证；它依赖实际播放器的事件调度。

## 5. T1：让测试真正覆盖定时检查路径

### 5.1 为什么现有测试不够

目前片尾测试主要执行：

```kotlin
player.seekTo(0, 585_000)
```

这会触发位置不连续事件，事件监听器已经完成跳过。之后再推进 200 毫秒任务，只能检查重复处理，不能证明 tick 本身能发现片尾。

新增测试必须让“音频位置越过阈值”与“发送位置事件”分离，并通过公开播放器行为进入逻辑，不能直接调用私有检查函数。

### 5.2 推荐确定性的调度测试

使用现有 MockK 或轻量的可控 `Player` 测试替身，不添加依赖，也不新增生产定时器抽象。

替身应具备：

- 可设置的 `currentPosition`、当前播放项、真实播放状态、播放意图与 playbackState。
- 捕获 `addListener()` 注册的监听器，并能明确派发 `onIsPlayingChanged` 等事件。
- 调用 `seekTo(index, position)` 时更新位置，并记录外部可见的目标/次数。
- 由测试直接推进位置时，不自动派发 seek 或媒体切换事件，以模拟连续时间流逝。
- `setMediaItems`、媒体 ID 和文件映射保持与生产输入一致，不把章节相对位置与文件位置混淆。

一条精确的时间线：

| 测试时间 | 操作 | 应断言 |
| --- | --- | --- |
| `t=0` | 配置片头 30s、片尾 15s；文件长 600s；位置为 584900ms；使底层实际播放并派发对应事件；运行当前队列 | 未跳到下一文件，检查任务已进入首个 delay |
| `t=199ms` | 直接把测试替身位置推进到 585000ms，不发送位置事件 | 仍无片尾 seek |
| `t=200ms` | 推进协程时间并执行当前队列 | 出现一次跳到下一文件有效起点的 seek |
| 后续一个检查周期 | 下一文件仍在正文 | 不重复执行同一跳转 |

注意 `advanceTimeBy()` 对目标时刻任务的执行语义，配合 `runCurrent()`，不要把“时间已推进”误当成“该时刻任务已执行”。

这条测试证明规则由定时任务发现并执行；它不证明真实解码器、音频焦点系统或真机听感。保留现有 ExoPlayer 集成测试验证末尾状态和位置保存，不把替身测试包装成真机验证。

如果现有 Media3 夹具能够方便地实现自然进度，也可以增加或改用真实测试播放器从阈值前自然播放到阈值后的用例。必须分别控制播放器时钟和协程时钟，并确保阈值前没有先发出一个跨阈值 seek；不要仅为此新增大体量测试基础设施。

## 6. 需要新增或调整的针对性用例

### 6.1 F1 完成标记

| 用例 | 设置与动作 | 断言 |
| --- | --- | --- |
| F1-A | 最后一集完成后，不调用 `setBook()`，直接媒体定位到片尾，再实际播放/触发事件 | 再次定位到最终末尾，不能从该片尾位置持续播放 |
| F1-B | 完成后暂停，直接定位到片尾，仍保持暂停，再调用 `play()` | 暂停期间不跳；播放前重新完成片尾校正 |
| F1-C | 完成后定位到正文并播放 | 正文正常继续，标记不阻止重听 |
| F1-D | 已在末尾容差内，重复收到事件或检查 | 不重复 seek，不循环；计数针对实际 `seekTo` 调用 |
| F1-E | 最后文件有多个章节标记，跳回该文件片尾的较早播放项 | 不会仅因相同文件 ID 而忽略跳转 |

F1-A/F1-B 的关键前提是复用同一个 `VoicePlayer` 与同一已加载书籍。不能通过重建夹具或 `setBook()` 清掉标记后再断言，这样测不到原问题。

涉及 `PlayerController` 时注意它可能在 ENDED 状态重新 prepare/装载书籍；为稳定覆盖问题，应在测试中直接走公开 Player/MediaController 定位语义，避免额外重载掩盖完成标记缺陷。

### 6.2 F2 播放抑制与恢复

| 用例 | 设置与动作 | 断言 |
| --- | --- | --- |
| F2-A | `playWhenReady=true`、READY，但 `isPlaying=false`；派发实际播放状态变化；推进多个检查周期 | 不 seek，也不继续周期性执行位置检查 |
| F2-B | 在上述抑制状态下修改片头，使当前位置落入片头 | 保存新配置，但位置不变 |
| F2-C | 在上述抑制状态下修改片尾，使当前位置落入片尾 | 不跳下一文件，不提前完成最后文件 |
| F2-D | 随后恢复 `isPlaying=true`，只派发 `onIsPlayingChanged` | 使用最新设置立即检查，并按需恢复单个片尾任务 |
| F2-E | 多次通知实际播放状态/READY | 不产生多个同时运行的片尾检查任务 |
| F2-F | 片尾检查 delay 期间变为不播放或配置改为 0 | 该周期不再执行跳转 |

F2-A 若只断言“没有 seek”，不足以证明任务已经停掉，因为正常轮询可能恰好发现无需跳过。可通过测试替身对位置读取次数进行计数，在处理完暂停事件后记录基线，再推进有限周期，确认没有额外轮询读取；不要为了测试暴露生产 Job 字段。

模拟播放抑制时保留 `playWhenReady=true` 和 READY。若把它们改成 false/IDLE，旧代码本来就会停止，无法证明修复有效。

### 6.3 T1 调度及旧行为回归

- 增加第 5.2 节的无位置事件定时触发用例。
- 首次开始播放仍在 `isPlaying=false` 时校正片头，然后才允许播放。
- 配置延迟加载时仍不提前播放；等待期间暂停仍取消播放意图。
- 跨章节片头/片尾跳转仍遵守章节结束睡眠定时器的停止优先规则。
- 末尾仍进入停止状态，保存到最终文件有效末尾。

改变状态判断后，现有夹具 `harness.play()` 只设置 `playWhenReady=true`，不一定表示底层已经实际播放。对声称“播放中定位”的用例，应使用当前 Media3 测试工具等待真实 `isPlaying=true` 后再操作；对“播放前校正”用例，则有意保留尚未播放的状态。

使用当前依赖实际提供的测试 API，不凭空编造辅助方法名称，也不要将生产判断退回 `playWhenReady` 来让旧测试通过。

所有测试中的 Player、PositionUpdater 和自有任务应按夹具所有权释放。无限后台任务使用 `backgroundScope` 或显式取消，不对永久循环调用无限制的 `advanceUntilIdle()`。

## 7. 最小实施顺序

1. 检查当前工作区，确认 F1/F2 代码尚未被其他改动修正；保护所有现有功能和文档。
2. 先补 F1-A/F1-B 的回归用例，确认测试不通过重载书籍规避标记问题。
3. 修改 `finishLastFile()`，将去重条件收紧为“同文件且仍位于最终末尾”。
4. 分离实际播放检查与显式播放前校正，新增 `onIsPlayingChanged` 并统一 Job 条件。
5. 补 F2 抑制/恢复测试和 T1 无位置事件的定时触发测试。
6. 必要时调整旧测试的实际播放等待及资源释放，不改变旧断言的业务含义。
7. 检查最终 diff，确认未扩展到设置、存储、数据库或依赖变更。

## 8. 验证范围

用户要求避免多余验证。编写本文不执行任何测试；接手修复时优先只运行直接涉及的播放测试：

```bash
./gradlew :core:playback:testDebugUnitTest \
  --tests '*VoicePlayerIntroOutroSkipTest' \
  --tests '*VoicePlayerIntroOutroSchedulingTest' \
  --tests '*VoicePlayerTest'
```

若没有创建 SchedulingTest 文件，应删除对应过滤项，或替换为真实类名。命令失败时先定位具体失败，不重复运行不相关检查。

根目录 AGENTS.md 要求触及共享行为或高风险改动时扩大测试范围。这次会修改共享播放状态判断，最终按该要求运行一次 `./gradlew voiceUnitTest`；这是仓库的既有要求，不代表每次调整都需重跑全量测试。若用户明确将接手任务限制为静态修改或指定测试范围，则遵守该限制，并在交付中记录未执行项。

在没有修改 DI、UI、资源或依赖的前提下，不默认重复 assemble、lint、设置模块测试或真机全流程；出现新的相关风险或失败证据时再有针对性地扩大验证。

禁止宣称本次确定性调度测试已经验证真实设备的音频焦点行为或片尾听感。交付时区分静态判断、自动化结果与未做的设备验证。

## 9. 完成标准与交接回复

- [ ] 同一最后文件完成后再次定位到片尾，会重新应用跳过规则。
- [ ] 完成去重只对仍在最终末尾的情况生效，容差保持毫秒级。
- [ ] 暂停时定位不立即跳转，明确请求播放时可在播放前校正。
- [ ] 实际播放被抑制时无片尾轮询、无设置驱动跳转。
- [ ] 实际播放恢复事件能重新检查最新设置，并恢复单个任务。
- [ ] 至少一条测试在没有位置事件的情况下，通过定时 tick 触发片尾跳过。
- [ ] 首次片头校正、延迟配置、睡眠定时器和末尾保存行为没有退化。
- [ ] 改动集中在 VoicePlayer 与相关测试，未重做已完成的设置功能。
- [ ] 如执行测试，报告实际命令与结果；未执行的验证明确说明。

接手 AI 的最终回复应简要说明：两个问题分别如何修复、哪个测试确实覆盖了定时触发、实际执行了哪些检查，以及仍有哪些未验证事项。不要仅以“测试全绿”代替对上述触发路径的说明。
