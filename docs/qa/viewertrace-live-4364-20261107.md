# 4.3.6.4 真机监听：详情窗口重建空档

## 采集

- 用户说“好了”后已停止；未重新启动应用、安装或操作窗口。
- 设备 CPH2745，包 com.pixel.gallery.viewertrace，版本 4.3.6.4 / 4081，进程 30963。
- 原始日志：scratch/viewer-trace-captures/live-4364-20261107-173805/host-logcat-live.txt。
- 补读系统既存 events 缓冲：同目录 events-buffer-after.txt；这是历史缓冲读取，不是新一轮实时监听。
- 以下均为手机日志时间。主机时间与手机时间不同。START 标记未出现在所采日志中；STOP_4364 出现在 16:38:50.206，文件以 HOST STOP 结束。

## 已证实的链条

1. 历史 events 缓冲 16:36:10.963：任务 29848、ActivityRecord 246468753 被销毁，原因明确为 low-mem。同一批系统回收了本应用 12 个 Activity。这发生在本次实时采集之前。
2. 本次实时日志 16:38:30.921：用户从最近任务恢复任务 29848，系统记录 mainWin=null、state=DESTROYED；16:38:30.936 为 wm_restart_activity。进程仍是 30963。
3. 16:38:30.944：ACTIVITY_CREATE，saved=true（原始日志第 10439 行）。
4. 16:38:31.031：标签来源的 VIEWER_LIST_READY count=0（第 10810 行）。16:38:31.050 已 reportDrawFinished，.052 窗口 HAS_DRAWN。
5. 16:38:31.261：同一标签列表变为 count=2（第 11228 行）；.264 全量源发出 131014 项；.273 Viewer 才挂载，当前图片 54746。
6. .360 至 .416 的 6 个 FRAME_STATE 采样均为 previewLoaded=false、tilesReady=false、previewView=VISIBLE/alpha0、tileView=GONE。6 个 vsync 均能关联到 FRAME_ACTUAL 完成回调。回调有重复上报，按 vsync 去重，不计作 12 帧。
7. .416 预览完成，.427 首个记录到预览 alpha1 的采样；.471 记录到大图层 alpha1，.482 预览隐藏而大图保持显示。

这些日志证明：窗口重建后先提交了没有详情图片的内容，随后重新加载图片；并非只有图片加载耗时的推测。窗口首次完成绘制到预览 predraw 约 377 ms。该数字不是屏幕实拍测得的白闪持续时间，系统任务切换动画也可能覆盖其中一部分。

## 对应代码

- MainScaffold.kt:2014 用 remember 保存 Viewer 会话列表，Activity 销毁重建后会丢失；初值取 livePhotosForViewer。
- MainScaffold.kt:2034 只有 photosForViewer 非空才挂载 ViewerScreen；本次恢复先出现空标签列表，因此首轮未挂载 Viewer。
- SimpleSubsamplingImageView.kt:531 的 previewLoaded 初始 false；新的 ImageView 和 tile view 需要重新加载。
- MainActivity.onCreate 直接建立 Compose 内容，没有以“恢复目标图片已可绘制”为条件的首帧交接。

之前的列表稳定化、后台群组订阅收敛可以减少存活页面重载，但不能保留已被系统销毁的 View，也未覆盖这条重建恢复路径。

## 尚不能归因的部分

- 本段没有捕获到 16:36:10 系统 low-mem 回收前的群组操作和分配峰值，不能把这次系统回收归到某次群组开合或具体提交。
- 保持存活的 entry39/currentMedia150541 在所收到的采样中持续为 TILES ready、alpha1；这不代表所有窗口或缺失帧均正常。
- 系统 LOG_FLOWCTRL 明确丢弃了 255 行应用日志；逐帧数据仅输出 logcat，不进入应用持久日志，缺失部分不能补算为正常。
- 新恢复窗口的最慢回调总时长约 206 ms；随后有 96/90 ms 回调。NativeJpegFit 一次 IOException 触发标准预览回退，约 15 ms 后已有磁盘缓存预览，不能单独解释之前的空列表阶段。

## 修复与验证目标

优先补齐恢复链：单独保存并优先恢复当前媒体的必要信息，不等待完整标签/全库列表；恢复首帧与可绘制图片交接，保留缩放和位置；异步补齐其余浏览列表。随后用同一进程内销毁后台 Activity、恢复原任务的方式验证，记录首帧和实际图片层状态，并修复监听丢行问题。另行捕获群组操作前后的内存峰值，才能回答回收触发源。

本轮只停止采集、分析日志并记录证据；未改生产代码。
