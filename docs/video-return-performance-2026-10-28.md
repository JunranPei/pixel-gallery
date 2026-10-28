# 视频后台返回：逐帧性能复核

本轮修正了恢复通知延迟及重复设置，没有证明视频返回已经流畅。保留后台 AUDIO 禁用视频轨道的省电策略。

## 改动与依据

- ViewerScreen：生命周期回调直接通知播放器可见性，替代等待 Compose LaunchedEffect；设置更新独立处理。
- VideoPlaybackSession：设置、可见性相同时不重复配置，视频轨道禁用状态不变时不重建选择参数。
- ViewerLoadMetrics：Window.FrameMetrics 在独立线程读取、格式化和记录；UI 图层仍由主线程 Choreographer 逐帧采样，按 vsync 时间关联，避免后台线程访问播放器/UI。FRAME_ACTUAL 的状态字符串是已有的 AtomicReference 快照，不能当作该完成帧的精确图层状态。FRAME_STATE 仍保留每次回调的完整图层信息。
- FrameMetrics 中 previousTraceWorkNs 在最终构建表示监听线程耗时；不能与旧版主线程上的同名字段直接比较。reportsDropped 表示监听报告丢失，不表示屏幕掉帧。

官方经验：

- [Media3 慢速定位说明](https://developer.android.com/media/media3/exoplayer/troubleshooting)：准确恢复需要从前一个关键帧解码并丢弃旧帧。降低定位精度会改变位置，增加缓冲也不能直接消除解码工作。
- [Media3 issue 2316](https://github.com/androidx/media/issues/2316)：相似表象最后定位为 HLS 时间戳源导致音轨重建；本项目本地 MP4 不以该 issue 作为根因证据。
- [异步解码队列](https://developer.android.com/media/media3/exoplayer/customization#enabling-asynchronous-buffer-queueing)：Android 12 以上已默认启用，模拟器无需重复开启。

## 证据

基线：`scratch/viewer-trace-captures/emulator-video-perframe-20261028-163949/`。

本轮：`scratch/viewer-trace-captures/emulator-video-resume-20261028-170609/`。
全部原始 logcat 连续写入电脑，最终大小 29,508,592 字节；未清除旧日志。`raw-log-sha256.json`、`final-apk.json` 保存校验值。

原始文件含三次测试会话，最终构建为 PID 12500、session `6e74693b-7eb7-48cc-9111-274a5d5791a5`；最终 11 次结果单独在 `final-build-results.json`。`system-trace.perfetto-trace` 属于只改通知时机、尚未移动监听线程的中间构建，不能冒充最终构建记录。

基线首个返回：ACTIVITY_RESUME 后约 157ms 才应用前台视频策略。修改后最终 5 次播放返回的策略通知均在 RESUME 日志之前，消除了这段应用层等待。

中间构建 playing_3：decoder 初始化耗时 80ms，首帧前跳过 130 帧，播放器进入 BUFFERING；约 1.3 秒后才产生视频首帧。系统跟踪还显示 UI 重绘及系统窗口重建耗时，不能把全部界面卡帧归因于视频解码。

## 验收矩阵

| 项目 | 层级 | 结果 |
| --- | --- | --- |
| ~~编译监听版~~ | 构建 | 通过，`scratch/video-resume-monitor-build.log` |
| ~~输出表面代次/就绪判断~~ | 单元测试 | 7 项通过 |
| ~~播放中后台 AUDIO 返回~~ | 模拟器真实任务切换 | 5/5 会话保持、覆盖层交接、后台禁用视频、无崩溃 |
| ~~暂停中后台返回~~ | 模拟器真实任务切换 | 3/3 暂停意图保持、覆盖层交接、无崩溃 |
| ~~快速离开再返回~~ | 模拟器真实任务切换 | 3/3 无残留覆盖层 |
| ~~自动小窗~~ | 模拟器真实系统 PiP | 播放继续，无覆盖层捕获/绘制，无崩溃 |
| 返回无明显停顿 | 模拟器逐帧监听 | **未通过**：最终播放覆盖层保留 609–1418ms，UI 单帧最大 272.501ms |
| 暂停返回流畅 | 模拟器逐帧监听 | **未通过**：覆盖层保留 809–906ms，UI 单帧最大 196.337ms |
| 快速返回耗时 | 模拟器逐帧监听 | 覆盖层保留 50–90ms，UI 单帧最大 51.433ms |
| 物理显示无黑帧 | 真机屏幕呈现 | 未测；buffer/回调就绪不等于物理显示证明 |
| 最终构建手机性能 | 用户手机 | 未安装；本轮仅使用 emulator-5554 |

最终 11 次返回统计窗口内 FrameMetrics 报告丢失为 0。并非已证明设备全程没有丢失任何系统日志。监听版模拟器数据不能等同于手机 release 耗时。

模拟器安装空间不足时，仅将旧测试文件 `/sdcard/Download/indexed-jpeg-cold-b.jpg` 备份到 `scratch/emulator-storage-backup-20261028/`，两端 SHA-256 一致后删除设备副本；未删除其他媒体。测试包重装保留其数据，权限已恢复。

剩余问题：准确位置的解码追帧延迟和返回 UI 长帧。未采用后台持续解码、跳转播放位置或放宽画面就绪判断来掩盖等待。本轮不应标记为“已完全修复卡顿”。
