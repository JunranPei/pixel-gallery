# 1000 文件转移和视频返回原位停顿

用户授权修复后在模拟器执行 1000 文件转移。使用 functional-edge-testing skill；测试夹具为专用 UUID 相册，不处理已有媒体。

## 检查与改动

- 视频：原输出切换会移除 View，并在新 SurfaceView 尚未创建时绑定 PlayerView。Media3 1.5.1 此时先设置空输出。改为一个稳定的输出宿主，普通 SurfaceView 保持附着，旋转时才创建 TextureView；直接切到有效 Surface。返回后释放纹理输出，不保留第二个解码器。
- 遮盖仍等待输出就绪，并记录耗时与是否超时。原有 1500ms 兜底不能证明所有停顿来自超时，需设备测量。
- 网格：lazy grid 的数量与内容原本捕获旧 presentation，而 key 回调读取可变的新 presentation。改为共同捕获同一份展示快照，消除批量收缩时旧索引访问新列表的风险。没有用户手机崩溃堆栈，不能将它定为已确认的手机崩溃根因。
- 诊断：增加上次进程退出信息、批次开始/完成及媒体刷新阶段日志；Performance & Caching 可导出本地 ZIP。日志日期格式串行访问，异常日志失败也继续调用原崩溃处理器。

## 矩阵

| ID | 操作 | 预期与验证层级 | 状态 |
|---|---|---|---|
| ~~V1~~ | 普通播放和暂停时各 4 次往返显示层切换 | 生产播放器、输出宿主与遮盖在隔离宿主中运行；SurfaceView 不重建，交接不达到 1500ms | 通过：16 次切换，返回原输出 163–207ms；不是详情页手势端到端测试 |
| ~~T1~~ | 真实主界面选 1000 JPEG，Move 半页面转移 | 前台服务实际执行；1000 内容哈希/时间戳/源移除/MediaStore 条目验证 | 通过：PID 7412，批次约 118 秒，含完成后等待/截图 123.5 秒；全部 1000 文件校验通过 |
| ~~T2~~ | 同一批 1000 JPEG 通过 Quick sort 胶囊再次转移 | 同上，并覆盖批次完成时源网格收缩 | 通过：PID 8786，批次约 406 秒，含完成后等待/截图 411.7 秒；全部 1000 文件校验通过 |
| ~~T3~~ | 两次转移期间滚动主网格、完成后继续操作 | 实际 UI、进程及崩溃日志检查 | 稳定性通过：持续触摸滑动、Move 完成后继续选择并进入 Quick sort；两批均完成网格收缩和索引刷新，无应用崩溃/ANR。帧率流畅性未通过专项验收 |
| P1 | 用户手机复测 | 验证偶发闪退是否还存在，若有读取诊断 | 未测 |

原模拟器 emulator-5554 仅剩约 61MB，保留其数据，改建 GalleryBulk1000_20261026（emulator-5558）运行。夹具 512×384 JPEG，测试文件数量及批次/UI交界，不将结果等同于多 GB 大图压力测试。

## 构建与证据

- 测试运行 Android 36.1 x86_64 模拟器，debug 包 `com.pixel.gallery.multitask.test`。手机交付为相同应用源码构建的 arm64 release，不能把模拟器结果当作用户手机验收。
- 22 项相关单元测试通过。完整构建：`scratch/bulk1000-final-build.log`。
- 视频测试：`scratch/video-handoff-device-result.txt`，instrumentation code -1。覆盖 Media3 MP4 播放/暂停各 4 轮、两个方向，普通 SurfaceView 对象保持不变；原生兼容解码路径和用户手机未实测。
- 文件夹具运行标识 `a10a0cf4`，1000 张 JPEG 合计 162,903,000 字节。目录 `/storage/emulated/0/Pictures/GalleryBulk_a10a0cf4/`；私有测试报告 `files/bulk1000/a10a0cf4/`。
- 初期测试脚本曾因 pending 文件路径、未先长按进入选择模式、误点搜索输入框而失败；修正的是测试工具，未将这些失败计作应用缺陷或转移失败。
- 普通移动通过后，脚本未展开默认收拢的 Quick sort 面板，等待搜索框超时；补上标题上拖动作后从快速分类阶段继续，普通移动通过证据保存在 `scratch/bulk1000-evidence-a10a0cf4/move-pass-quick-sort-expand-needed.txt`。两次批量操作不冒充同一次不间断进程测试。
- 图形采样 `scratch/bulk1000-gfx-during-move.txt` 包含启动、选择及搜索阶段，debug 模拟器有明显超时帧；不能据此宣称滑动流畅，也不能直接归因于文件移动。
- 运行中发现 Quick sort 比 Move 慢。代码检查显示：无预选冲突策略时，每个文件前会调用 `conflictCount` 遍历剩余队列；普通 Move 默认 KEEP_BOTH，不走这段反复预检查。属于额外耗时线索，本轮没有为提高测试速度取消冲突保护或改写转移逻辑。

## 最终结果

- 两批各 1000 个真实文件全部转移完成；两次各自校验全部 SHA-256、修改时间、源移除、1000 个 MediaStore 条目、无临时文件残留。目标相册截图显示 1001，是 1000 个被转移文件加测试前创建相册用的 1 个 seed.jpg。
- `scratch/bulk1000-evidence-a10a0cf4/` 保存独立预期清单、完整报告、Move 完成截图、Quick sort 选中/完成截图及 instrumentation 结果；`quick-sort-pass.txt` 最终 code -1。
- `scratch/bulk1000-device-logcat.txt` 记录两次批次完成后的 library sync finished，无应用 FATAL EXCEPTION/ANR。`process-exits-final.txt` 中两个测试进程均为 finished inst 后的系统 force stop，不是崩溃。
- 运行中内存采样：Move PSS 269,470KB；Quick sort 前期 255,687KB、中期 268,845KB。采样不等于峰值，也不推断手机偶发闪退根因。
- 修复版 arm64 release：`released_apks/4.3.5.5-auto-multitask/rotation-bulk-stability-fix/PixelGallery-4.3.5.5-rotation-bulk-stability-fix-arm64-v8a.apk`，SHA-256 `ba9371354d7d01ed1e958136772e6ca3c7dda25df13eea0448f8b3fc8e595592`。未安装到用户手机，未提交/推送本轮改动。

## 参考

- [Media3 1.5.1 ExoPlayerImpl](https://raw.githubusercontent.com/androidx/media/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayerImpl.java)：SurfaceHolder 无有效 Surface 时设置空视频输出。
- [Media3 Surface types](https://developer.android.com/media/media3/ui/surface)：Surface 生命周期与省电差异。
- [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)：上次进程退出原因，包括崩溃、ANR 和内存回收。
- [Android memory](https://developer.android.com/topic/performance/memory/manage-app-memory)：图片内存与进程内存诊断；属于排查依据，不能据此推断此次闪退就是 OOM。
