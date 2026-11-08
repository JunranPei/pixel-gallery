# 二十四个详情窗口与大图对照

用户补充的原有正常用法：二十多个窗口，其中包含超过 100 MB 的地图图片；旧版长期不因这些操作被回收。

## 本轮条件

- 同一 emulator-5562，4.3.3.2 稳定基线与当前修复源码分属 com.pixel.gallery.memory4332 / memorypatched 隔离包。
- 两版均先完成编译，测量时不并行编译、运行另一版或操作手机。两版 VIEWER_METRICS_ENABLED=false。
- 同一 131014 条测试数据库、179 个本地 JPEG；同步互斥锁隔离测试数据库，实际启动 24 个独立 ACTION_VIEW 详情任务，加一个主 grid。
- 24 个详情使用不同图片路径。第 0、6、12、18 号使用大图，其余为 1856×2304 测试图片。
- 大图 A：原文件 scratch/emulator-storage-backup-20261028/indexed-jpeg-cold-b.jpg，152854905 字节，13071×12829。
- 大图 B：原文件 scratch/jpeg-hotspots-20261021/ui-fixtures/01-444.jpg，130720108 字节，9001×6001。
- 两张源图未经重编码，复制后核验 SHA-256。此处使用已有大图夹具，未核定画面是否为用户所说的地图内容。
- 10 毫秒 Java 堆采样；阶段记录 Java / native / PSS、任务与索引身份；主机每 2 秒保存 dumpsys meminfo；系统事件按实际任务号匹配 low-mem。
- 逐一返回所有原窗口，要求原 Activity 尚在且真实 View 图片已就绪；监听聚焦绘制时的图片状态，不做截图识别。
- 4.3.3.2 无 Quick Sort / 群组，对应阶段只写相同设置键。修复版另有真实群组开合与刷新压力用例，不将两者描述为相同功能。

## 执行记录

- large-4332-1：源文件通过 Windows adb stdin 传输时被截断，SHA-256 断言拦截，未进入测量。已改为 adb push 后在隔离包内复制并验证哈希。原始图片未改动。
- large-4332-2：24 个详情 + grid 及设置写入阶段完成，Java 堆阶段值 250.3 / 253.2 MiB，采样峰值 389.5 MiB，未记录 low-mem 或非主动 Activity 销毁。返回最早窗口时 getAppTasks 列表不含其任务，驱动 NoSuchElementException；系统 recent-task dump 中该任务在 mHiddenTasks，生命周期仍存活。该轮不记为完整返回验收。
- 已将两版测试的返回操作统一改为记录 taskId 的 moveTaskToFront，测试临时采用 REORDER_TASKS 权限，随后立即释放；不发送新图片 Intent，不修改产品。接口依据：https://developer.android.com/reference/android/app/ActivityManager#moveTaskToFront(int,int) 。重跑待完成。
- large-4332-3：完整完成 24 详情 + grid，并返回全部 24 个原 Activity。无 low-mem、无非主动销毁、返回无空图片层。Java 堆在 24 详情时 259.5 MiB、打开 grid 后 253.2 MiB、全部返回后 252.0 MiB；窗口阶段采样峰值 380.6 MiB。native 对应 216.5 / 222.4 / 213.8 MiB，阶段 PSS 655.0 / 660.1 / 653.8 MiB。
- 主机 processPeakPss / Rss 当前包含建库准备阶段，不能拿它直接当作窗口阶段峰值；阶段 Java sampledPeak 在 seed-complete 已重置。
- large-patched-1：完整完成相同窗口与返回负载，无 low-mem、无非主动销毁、返回无空图片层。24 详情 / grid / 全部返回 Java 堆为 280.1 / 285.6 / 300.0 MiB，全程窗口阶段采样峰值 318.6 MiB；native 为 207.0 / 209.9 / 214.9 MiB，阶段 PSS 为 696.3 / 702.8 / 725.4 MiB。返回后的 Java 单点比稳定版高 48.0 MiB，尚未用等条件 GC 与持有链区分临时量和常驻差额。
- large-patched-stress1：50 次实际开合正常，结束时 Java 堆 270.7 MiB，开合阶段峰值 286.5 MiB；8 次未改变数据的刷新结束时 308.9 MiB，阶段峰值 316.8 MiB。紧接的一条元数据修改 / 还原期间峰值达到 576.0 MiB，24 个任务 1291–1314 全部收到 low-mem 销毁。返回首窗原 Activity 已被回收，用例失败。原 APK 已保存该轮目录的 app-before-label-fix.apk。

## 追加定位：标签汇总

Labels.kt 的 buildLabelSummaries 原先无条件 visibleMedia.associateBy(contentId)。LabelsViewModel 在每个窗口 Eagerly 订阅，导致图库记录更新时每个窗口同时为 131014 行创建完整映射，即使 labels 或 memberships 为空。

git blame 指向 93d4e6072（2026-10-11，4.3.4.2 引入标签），4.3.3.2 尚无此路径。此前已优化的手动封面 URI 索引没有覆盖这里。

本轮先单独改此函数：无标签 / 无对应成员时不读取图库；有成员时只保留实际所需 ID 的可见媒体。计数、封面时间及 ID 并列规则、可见性、重复 ID 最后值行为保持一致。新增未请求图库读取断言、100 组新旧结果对照、24 窗口空标签累计分配对比。随后将用相同二十四窗口失败步骤验证。

驱动：scratch/version-memory-comparison/prepare_large_window_driver.py、run_large_windows.py；每轮独立目录保存 APK / 源图哈希、原始内存、系统事件、窗口与绘制记录。
