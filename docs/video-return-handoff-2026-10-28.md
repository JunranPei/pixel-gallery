# 视频返回交接优化：监听验收

本轮目标：隔离画面状态更新、按实际帧提交撤掉背景覆盖层，并对照验证新版 Media3 的解码恢复。沿用后台 AUDIO 禁用视频轨道，不修改媒体、不改暂停/播放位置。

## 实现

- `VideoPresentation` 承担就绪状态、视频输出和覆盖层，父级不再在组合阶段读取每次变化的缓冲区/覆盖层状态。
- Android 10+ 硬件绘制采用 `registerFrameCommitCallback`，提交后回到主线程，再核验覆盖层身份、表面代次和缓冲区就绪。旧提交、已销毁表面、再次进入后台不能误撤覆盖层。旧系统保留两帧等待。
- `VIDEO_BUFFER_AVAILABLE`、`VIDEO_HANDOFF_COMMITTED`、`VIDEO_COVER_CLEAR` 分别记录缓冲区、窗口帧提交及撤层决定。窗口帧提交不等于物理显示已经完成。
- 固定位置的真实详情页仪器测试使用已有隔离视频（MediaStore id 1047），分别在 2/7/14/28 秒测试播放/暂停切回。通过真实 HOME 和已有 task focus 触发窗口生命周期，不更换为简化播放器宿主。

## 资料

- [Android 帧提交回调](https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable))。
- [Media3 1.8.1 工厂源码](https://github.com/androidx/media/blob/1.8.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultRenderersFactory.java)：支持 Android 14+ DECODE_ONLY，默认关闭；预热第二解码器是另外的功能，本轮不启用。
- [官方版本记录](https://developer.android.com/jetpack/androidx/releases/media3)：1.8 系列包含 DECODE_ONLY 末帧处理等修复。升级收益需对照验证，不由版本号推断。

## 验收矩阵

| 用例 | 检查方式 | 状态 |
| --- | --- | --- |
| 旧代次提交不得撤掉新输出覆盖层 | 单元测试 | 通过 |
| 解码和缓冲区必须同时就绪 | 单元测试 | 通过 |
| 固定 2/7/14/28 秒，播放/暂停返回 | 真实详情页仪器测试和逐帧日志 | 三组各 8 次状态断言通过；暂停位置误差小于 60ms |
| 快速切回 | 原有任务切换脚本和日志 | 3/3 会话和交接状态通过 |
| 自动小窗 | 真实系统 PiP 与监听 | 通过，继续播放，未捕获/绘制返回覆盖层 |
| 旋转输出交接 | 原有生产输出组件的隔离宿主仪器测试 | 失败：暂停连续切换等待超时；恢复修改前 APK 同用例也超时，未在本轮扩大修复 |
| 升级前后首帧/撤层/UI 长帧比较 | 固定位置原始日志分析 | 对照完成；升级无明确收益，撤回升级和实验标志 |
| 后台视频轨道关闭 | 仪器测试检查实际 disabledTrackTypes | 通过；不等同于实测功耗 |
| 手机和旧版 Android | 本轮仅电脑模拟器 | 未测 |

本轮目录由 `scratch/current-video-handoff-dir.txt` 指向。旧日志与旧 APK 均保留；每次采集使用新的电脑目录，独立 logcat 进程保存全部缓冲区。

## 对照结果

证据根目录：`scratch/viewer-trace-captures/video-handoff-20261028-211042`。以下均为 Android 36.1 x86_64 模拟器、监听 debug 包；不能外推真机绝对耗时或断言屏幕每一像素无黑闪。

| 构建 | 有效原始记录目录 | 恢复至撤层范围 | 三秒内 UI 单帧最长耗时范围 |
| --- | --- | --- | --- |
| 本轮修改前，Media3 1.5.1 | `baseline-20261028-212653` | 650–1711ms | 131–302ms |
| 局部画面组合、帧提交交接，Media3 1.5.1 | `layers-1.5.1-valid-20261028-212840` | 718–1376ms | 118–278ms |
| 相同交接，Media3 1.8.1、API 34+ DECODE_ONLY | `media3-1.8.1-20261028-213234` | 705–1437ms | 137–288ms |

三组每组各 8 次，FRAME_ACTUAL 报告丢失计数均为 0。新增监听的后两组未记录到音频欠载或播放器错误；旧基线未含音频欠载监听，不能据此断言旧基线也没有。各组原始日志、逐用例 JSON 和哈希均保留。

局部交接版本的四个暂停用例，在确认缓冲区后约 10–19ms 撤层，但恢复至首个视频回调仍需 822–1306ms。说明交接等待已减少，视频恢复和 UI 长帧仍未解决，不能报告为无卡顿。升级组结果有好有坏，样本不足以证明升级收益，因此最终保留 1.5.1，不引入实验解码标志、后台持续视频解码或额外解码器。

无效记录保留并排除：`baseline-20261028-212510` 使用了未注册的旧仪器包；`layers-1.5.1-20261028-212814` 安装失败后误跑旧包，发现后主动停止。根目录的 `layers-1.5.1.apk` 是过期 outputs 路径的旧文件，不可作为本轮改后产物。有效 APK 使用 intermediates/apk 的 output-metadata 核实，分别为 `layers-x86-1.5.1.apk`、`media3-1.8.1-decode-only.apk`、`final-1.5.1-x86.apk`。

## 最终构建回归

最终日志目录 `final-regression-20261028-214152`：5 次播放、3 次暂停、3 次快速暂停返回，共 11 次状态断言通过；自动 PiP 通过。每帧窗口统计报告丢失为 0，无播放器错误或音频欠载记录。测试未使用截图识别。

| 场景 | 恢复至撤层 | UI 单帧最大值 | 结论 |
| --- | --- | --- | --- |
| 播放返回 5 次 | 733–1801ms | 274.57ms | 会话/交接通过，流畅度未通过 |
| 暂停返回 3 次 | 794–843ms | 180.71ms | 暂停意图保持，流畅度未通过 |
| 快速返回 3 次 | 56–126ms | 69.03ms | 无残留覆盖，仍有超时帧 |

旋转失败原始记录在上述目录的 `rotation-instrumentation.txt` 和完整 logcat；修改前包的复核在 `baseline-rotation-*` 专属目录。该隔离宿主用 1 秒短片反复切换 Surface/Texture，暂停后出现新 Texture 无更新回调、就绪等待超时。两构建都失败；没有以延长超时、换夹具或忽略断言将失败改记为通过。该结果不等同于用户完整详情页已复现同一旋转症状。

最终重新安装 `final-1.5.1-x86.apk` 到 emulator-5554 的 `com.pixel.gallery.viewertrace`。本轮未安装手机、未提交、未推送；9 项就绪状态单元测试及构建通过。
