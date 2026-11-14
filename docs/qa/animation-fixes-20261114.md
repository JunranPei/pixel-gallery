# 详情打开动画与设置修复

分支为 `codex/animation-fixes`，基于 `4703654d4`，在独立工作目录修改。原目录的其他未提交改动保留。

## 问题与处理

- 打开动画原先会先等待详情预览，最长 180 ms，并额外等待一帧。现在直接使用网格已加载的缩略图开始过渡，移除预览等待和额外帧等待，没有新增图片解码请求。缺少缩略图时沿用淡入回退，预览加载不阻塞动画调度。
- 点击时保存缩略图的位置、圆角和已有像素；过渡采用该位置，不再等详情页放置后重新查找起点。原先进入详情会把底层收起的标题栏展开，现将详情标题栏和底层列表的滚动状态分开处理。
- 原先打开动画检查缩放记忆使用 `id:version`，原生看图组件实际保存为 `media:id:version`，因此没有发现已保存的缩放，造成开场与最终画面比例不一致。现在兼容原生和 Glide 的两种键，并复用已有的变换判断；普通适屏状态可以重复展开，需要恢复缩放或旋转时使用淡入。
- 监听开关从 Settings 首页移至 Performance & Caching 的独立 Diagnostics 板块，与 Export diagnostics 并列。标题和说明改用语言资源，英文界面显示英文，中文界面显示中文。
- 补齐 Library 的 Album organization 标题及性能子页的 Diagnostics 标题。逐项检查子页调用，现有 SettingsGroup 均提供标题；设置首页的导航布局保留。

## 验收

模拟器为 `GalleryAlbumSearchQA20261019:5554`，1080×2400、60 Hz；隔离应用为 `com.pixel.gallery.codexclean`，4.3.6.8-auto-multitask / 2085。

| 检查 | 层级 | 结果 | 证据 |
| --- | --- | --- | --- |
| ~~预览未就绪时立即调度动画帧~~ | 单元 | 通过 | ViewerOpeningGeometryTest.animationStartsWithoutWaitingForTheViewerPreview |
| ~~初次放置使用点击时的窗口位置和圆角~~ | 单元 | 通过 | firstPlacedFrameUsesTheCapturedCellInWindowCoordinates |
| ~~无动画、结束、取消后无持续帧请求~~ | 单元 | 通过 | noneAlsoReleasesAnAlreadyPendingEntryWithoutFrames 及既有完成/取消检查 |
| ~~三种打开效果、顶部与滚动后点击~~ | 实际界面和逐帧图 | 通过 | ui-final 下的 thumbnail、fade、none 的 top/collapsed 录屏 |
| ~~保存过缩放的图片交接~~ | 实际界面和逐帧图 | 通过 | thumbnail-saved-zoom.mp4、saved-zoom-target-details.json |
| ~~返回、后台中断和拖动关闭~~ | 实际界面 | 通过 | thumbnail-back.mp4、thumbnail-background-same-task.mp4、drag-dismiss.json/png |
| ~~动画结束后的静置~~ | 实际应用绘制计数 | 通过 | idle.json，三种效果等待 8 秒后再静置 3 秒，新增绘制均为 0 |
| ~~监听位置、英文、标题、实际启闭及再次进入~~ | 实际界面和监听协议 | 通过 | settings-home、performance、monitor-enabled、monitor-closed、library |
| ~~新文字随中英文切换~~ | 实际界面 | 通过 | performance-zh，结束后恢复原应用语言 |

本次 Android 动画与手势单元测试共 68 项，失败、错误、跳过均为 0。采用可控帧时钟验证不等待预览；实际录屏核对起点、背景位置与交接画面。

滚动后点击的修复前录屏中，图片开始展开前，背景紫色方图顶部下移 168 px；修复后对应指标为 0。统计按 30 fps、缩小至 270×600 后提取同一视频中的方图顶部变化，空间分辨率为原屏 4 px，数据在 `grid-shift-comparison.json`。两段视频的图库内容不同，比较的是各自点击前后的位移。

静置的三次进程 CPU 原始差分别为 Thumbnail 110 ms、Scale & fade 70 ms、None 90 ms，包含计数读取开销。打开期间能耗与峰值内存尚未做修改前后的对照测量。

后台用例通过 Home、最近任务卡片返回原窗口，前后任务编号均为 298，恢复后仍为同一详情页，见 thumbnail-background-same-task-tasks.json。最初使用 am start 的操作新建了窗口，旧 thumbnail-background.mp4 仅保留为测试操作失误的记录，没有计入后台恢复通过结果。

## 构建与证据

最终安装 APK SHA-256 为 `505bbfe5ac4a6c0e6b478260c867809938237feabe2272d8db4c37d2c49bb4f8`，已与设备上实际安装的文件核对。构建和测试使用较小堆、单工作进程，解决了验证过程中 Windows 提交内存不足导致的 Java 退出。

本次只修改 Kotlin 和界面资源。模拟器测试包重建了 Kotlin/UI 代码，并复用先前安装包的原生库；15 个 x86_64 原生库与基线逐字节一致。构建参数及内存限制在本地测试 init 脚本，未改项目的构建默认值。

证据保存在本工作目录的 `scratch/animation-fixes-20261114/`：

- `baseline/` 保存修复前背景跳位录屏；`ui/` 保存最初的设置检查和修正缩放记忆读取前的中间录屏，中间录屏没有计入最终动画通过结果。
- `ui-final/` 保存最终动画录屏、逐帧图、文件详情、拖动关闭与静置计数；文件详情核对了自有夹具的名称、路径与 600×1200 尺寸。该目录旧 thumbnail-background.mp4 除外，后台验收采用带 same-task 的录屏。
- `ui-settings-final/` 保存最终包的设置首页、性能页、Library、中英文及监听协议实际启闭检查。
- `build-verification.json` 保存实际 APK 哈希、原生库核对与单元测试计数。构建过程的原始日志保留在原工作目录同名 scratch 文件夹中。
- 自有录屏在设备上移入带 `.nomedia` 的测试目录，本地副本保留；避免继续出现在图库中。测试没有改动既有用户媒体。新增的单张自有图片夹具位于 Pictures/AnimationFixQA20261114。

实现参考 [Compose 动画指南](https://developer.android.com/develop/ui/compose/animation/quick-guide) 和 [延后状态读取](https://developer.android.com/develop/ui/compose/performance/bestpractices)。逐帧变换仍在图层和绘制阶段读取；完成或取消后移除过渡图层，没有加入常驻计时器。
