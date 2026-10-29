# 后台声音播放返回：缓冲和长帧对照

前序 PiP / 详情页工作已提交并推送为 `cb762664`。本轮专门处理 AUDIO 后台播放返回，不启用隐藏视频持续解码、第二解码器或额外轮询，不改变播放位置和界面样式。

## 源码和开发资料

- [Media3 官方排障说明](https://developer.android.com/media/media3/exoplayer/troubleshooting#why-is-seeking-in-my-video-slow)：准确恢复需要从前一个关键帧补解码；读缓存与解码时间应分别分析。
- [当前使用的 1.5.1 ProgressiveMediaPeriod](https://github.com/androidx/media/blob/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java)：重新启用轨道时尝试在 SampleQueue 定位；失败则重新定位媒体并重置所有活动流。
- [1.5.1 SampleQueue](https://github.com/androidx/media/blob/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/SampleQueue.java)：视频定位必须能在队列中找到目标位置之前的关键帧。
- [DefaultLoadControl 文档](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl)：默认不保留关键帧之前的回看缓冲。保留关键帧时额外占用取决于文件 GOP 间隔，不能声称完全不增加内存。
- [MediaCodecRenderer 时间预算](https://developer.android.com/reference/androidx/media3/exoplayer/mediacodec/MediaCodecRenderer#setRenderTimeLimitMs(long))：可限制一轮连续排出/填入数据的时间；不能打断一次已经阻塞的系统解码器调用。
- [ExoPlayer 输出解绑讨论](https://github.com/google/ExoPlayer/issues/528)和 [1.5.1 ExoPlayerImpl](https://github.com/androidx/media/blob/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayerImpl.java)：释放旧输出前需要等待播放线程确认。不能把 setter 随便移到其他线程，绕过其生命周期约束。

## 验证中的修改

1. AUDIO 返回先绑定有效输出，再恢复视频轨道，减少先初始化占位输出再切换输出的机会。
2. 现有压缩数据队列保留当前位置之前的关键帧；回看时长设置为 0，不另建位图缓存、不扩大现有 allocator 预算。Media3 在缓冲预算不足且前向数据不足时仍可丢弃回看数据，不能保证任意长 GOP 都可免重新定位。
3. 视频每轮连续处理设 8ms 预算，为同一播放线程上的音频留出调度机会；单次系统调用可能超过预算，必须看真实记录。
4. 详情页显示期间，底层 grid 使用 `navigationBarsIgnoringVisibility` 占位；不在系统栏动画每一帧重组整个 MainScaffold。[WindowInsets 官方说明](https://developer.android.com/reference/kotlin/androidx/compose/foundation/layout/WindowInsets)明确提示避免在 composition 读取动画中的 inset。

## 验收矩阵

| 用例 | 验证 | 状态 |
| --- | --- | --- |
| 播放中返回、固定多个位置 | 真正详情页、HOME/task focus、缓冲事件、视频帧与 Window 帧 | 对照中 |
| 暂停返回 | 同上，暂停意图和位置保持 | 对照中 |
| 后台时间跨越多个关键帧 | 延长后台时间，确认音频继续、视频轨道禁用 | 12 秒后台 × 8 次：状态通过；播放返回 0 次 BUFFERING |
| 快速返回 / PiP | 既有真实操作测试，检查状态和输出连续性 | 300ms 后台返回状态通过；两种 PiP 入口、16 段缩放状态通过，性能限制见下文 |
| 省电边界 | 禁用视频轨道、无新增解码器/周期任务 | 代码核查；未实测功耗 |
| 多格式 / 真机 | 本轮以 PC 模拟器测试 | 未测 |

证据根目录为 `scratch/viewer-trace-captures/pip-ui-20261028-220716/`。每轮另建目录，电脑独立进程保存完整 logcat 和 Perfetto 流；最终完整 trace 拉取后比对 SHA256，再删除本轮设备端临时 trace。旧记录均保留。

`audio-return-output-order-20261028-235604` 被另一组仪器测试占用 UiAutomation 中断，不能算产品回归通过。编译同时运行的实验只用于定位机制，不用于绝对耗时优劣结论。

`audio-return-baseline-idle-20261029-000845` 使用已提交的 PiP APK、视频 432，四个播放返回中三个进入 BUFFERING，四个暂停返回全部出现 BUFFERING。这证明额外重新缓冲确实存在，尚不证明它解释所有界面长帧。

## 中间对照（并非最终完成声明）

`audio-return-budget-idle-20261029-001852` 与 `audio-return-insets-idle-20261029-002552` 使用相同恢复策略，仅后者固定详情页底层导航栏占位。八次返回后第一秒 `Recomposer:recompose` 从 7–11 次降至每次 3 次，累计耗时从 295–451ms 降至 33–123ms。对应原始查询见各目录 `layout-results.csv`。这项减少实际重算，有明确收益。

两组四个播放返回均未进入 BUFFERING；暂停返回仍进入一次 BUFFERING。全系统 SurfaceFlinger 图层采集时仍有音频欠载和较长窗口帧，不能据此宣称完全流畅。为区分采集开销，另保留每帧窗口/视频输出和系统 atrace，关闭全系统图层快照运行 `audio-return-insets-lean-20261029-002827`：八次状态检查通过、音频欠载为 0；播放返回首个视频回调仍为 751–1498ms，窗口最长帧 100–243ms。仍在定位这些剩余耗时，未将其归结为手机型号。

## 后续验证和撤回的实验

- `audio-return-long-background-20261029-003631`：后台 12 秒，8 次状态检查通过，播放返回 4 次均无 BUFFERING，暂停返回各一次 BUFFERING，所有用例无音频欠载。播放返回首个视频回调为 873–1795ms，最长 Window 帧为 141–284ms。此处视频回调不是屏幕实际显示时间。
- `audio-return-no-budget-lean-20261029-003213`：撤掉每轮解码时间预算的对照，播放首帧 983–1952ms，窗口最长帧 193–303ms。样本小且主机负载有波动，只能支持继续保留预算作验证，不能推算真机改善百分比。
- `audio-return-dynamic-long-20261029-004314`：测试 Media3 1.5.1 已有的动态调度。后台声音播放的处理循环从约 83–87 次/秒降到 38–40 次/秒，但恢复阶段循环次数增加，首帧没有稳定改善，且出现音频欠载。已撤回，最终代码不启用此开关；查询在两组 `scheduling-results.csv` 中。主机内存压力也影响了这组，不能归因全部欠载于调度。
- `audio-return-pip-regression-20261029-005125`：手动和 HOME 进入 PiP，两组各 4 次双击、2 次捏合和2次暂停缩放，保持同一播放器；缩放期间输出绑定、Surface 销毁和解码器初始化均为 0。实际显示图层记录仍有一段捏合的 163ms 帧间隔，不能把状态检查通过写成全部流畅。原始图层及逐帧数据完整保留。
- `audio-return-insets-aot-20261029-005405`：调试包提前编译后的对照，8 次状态通过，仍有窗口长帧。提前编译不能将调试包变成发布包，因此继续补做真正的 release 构建测试。

[Android 官方性能测试说明](https://developer.android.com/topic/performance/appstartup/setup-env)要求用发布模式测试，调试模式会影响耗时。之前所有调试包数据仍作为事件顺序、重复缓冲和输出重建的证据保留；不将其绝对耗时当成正式包真机结果。发布模式测试仅在隔离工作树设置 x86_64、测试签名和 release instrumentation，保留 trace 包名，不改正式项目的打包配置。

限制：保留压缩关键帧不等于不增加任何内存，长 GOP 的代价取决于媒体文件；本轮未做真机功耗测量。仍然只使用一个播放器，不在 AUDIO 后台持续解码视频，不预热第二个解码器。原始日志、Perfetto 与哈希比对记录均按实验单独保存。

## 发布模式和线程调度核验

构建 `assembleRelease`、`assembleReleaseAndroidTest` 和选定的 `testReleaseUnitTest` 成功，`VideoOutputReadinessTest` 9 项、`ViewerSessionTest` 7 项均通过。安装的独立测试包为 `com.pixel.gallery.viewertrace`，x86_64，版本号 4073（ABI 输出规则在基础版本 2073 上偏移）；`dumpsys package` 确认没有 DEBUGGABLE 标志。测试签名不影响正式包。本轮未升级生产版本、未安装手机。

- `audio-return-release-mode-20261029-011028`：发布模式内部驱动的 8 次真实详情页返回状态检查全部通过。播放返回各 0 次 BUFFERING；暂停各 1 次，暂停位置保持。所有播放用例无音频欠载。`cmd package compile -m speed` 返回 Failure，因此不声称此发布包完成了额外 AOT 编译。
- `audio-return-release-external-20261029-011314`：退出内部 instrumentation 后，独立电脑脚本直接 ACTION_VIEW 打开、HOME、按原 task id 切回，3 次返回均 0 次 BUFFERING、0 音频欠载、0 播放错误。首视频回调 1098–1563ms，窗口最大帧 166–243ms，说明剩余延迟不能归因于内部测试驱动或 debug 标志。未开启 SurfaceFlinger 图层数据，不能据此给出实际显示首帧时间。
- `audio-return-release-scheduler-20261029-011746`：保留 `sched_switch` / `sched_wakeup` 再做3次外部驱动返回，同样无重新缓冲、欠载和错误。返回后第一秒，主线程实际运行 189–209ms，Runnable/被抢占后待运行合计 321–432ms，睡眠 379–475ms。主线程并非全部时间都在执行 Compose。主线程CPU等待是观测结果；不能直接推断真机同样如此，也不能把全部睡眠当作无害等待。查询与原始记录见 `cpu-results.csv`、`process-cpu-first-return.txt`、`thread-cpu-first-return.txt`。

**尚未通过“返回完全无可感知卡顿”的验收。** 本轮已验证重复缓冲被消除、返回时额外重组减少，但窗口长帧及视频冷恢复延迟仍存在，不将本轮标为整体修复完成，也不以保持后台视频解码来掩盖剩余问题。
