# 本次监听最后两次 grid 闪烁

用户限定只分析最近两次，且两次均为 grid 页面。以下时刻均为手机日志时间。

监听目录：scratch/viewer-trace-captures/live-4364-20261107-184244。收到分析指令后已停止采集；session.json 为 stopped，host-logcat-live.txt 以 HOST STOP 结束。

| 事件 | 第一次 | 第二次 |
| --- | --- | --- |
| 任务 ID | 29895 | 29894 |
| 系统 ActivityRecord | 196029900 | 243336894 |
| 此任务被系统销毁 | 17:39:43.396，low-mem | 17:39:43.396，low-mem |
| 本次返回时重启 Activity | 17:45:43.178 | 17:45:47.308 |
| onCreate 完成 | 17:45:43.192 | 17:45:47.323 |
| 首次 reportDrawFinished | 17:45:43.223 | 17:45:47.345 |
| HAS_DRAWN | 17:45:43.224 | 17:45:47.346 |

两次恢复均在同一个应用进程 7273 中执行；系统明确记录进程已在后台存活。开始采集时的 activities-start.txt 已显示这两个任务 state=DESTROYED（第 691、749 行）。回收发生在此次监听开始前，最后两次返回发生在此次采集内。

## 直接证据

- events-buffer-after.txt 第 2674、2676 行：两个对应任务的 low-mem 销毁原因。
- 同文件第 8487、8494 行：第一个任务 restart/create；第 8645、8652 行：第二个任务 restart/create。
- host-logcat-live.txt 第 153622、153811 行和第 159415、159637 行：实时采集中的相同 restart/create。
- latest-two-analysis.json 保存最后两次前后应用事件、系统事件、日志限流及内存样本；由 scratch/analyze_latest_two_grid.py 从保存文件解析。

## grid 恢复代码路径

PhotosViewModel 的 orderedMedia、photos 初值为空，browsingIndex 初值为 MediaBrowseIndex.Empty；Activity 重建后重新创建 ViewModel，再异步构建浏览索引。MainActivity 直接建立 Compose 内容，窗口首次绘制不等待 grid 数据或缩略图就绪。这条路径会重新建立 grid，与这次明确的 restart/create 对应。

本次没有完整收到两个窗口的数据就绪事件：应用每秒日志超过设备限额，17:45:43.803 报丢 458 行，17:45:47.823 报丢 520 行。因此尚不能从本段精确计算 grid 空白持续时间或指认某一张缩略图的加载变化。现有系统事件足以确认两次均经过页面重建。

## 内存样本

共 38 份每约 5 秒的 dumpsys meminfo 样本，进程始终为 7273；起始 Activity 对象数 9，结束 12。原始样本保留设备 Uptime/Realtime，不能直接按主机时钟等同手机日志时间。

最后两次恢复附近：Uptime 706823695 ms（恢复前）Java 已分配 250571 KiB、Activity 10；706829199 ms（第一次恢复后）269115 KiB、Activity 11；706834858 ms（第二次恢复后）290954 KiB、Activity 12。它们是离散内存样本，不是 17:39:43 回收前的峰值。

## 本次结论

最后两次 grid 闪烁对应已被 low-mem 销毁的任务返回后重建。原任务的销毁发生在本轮采集前；本轮没有新的这两个任务的销毁事件，也没有应用进程重启。接下来的版本对照应同时检查使后台页面被释放的内存来源，以及 grid 重建时先绘制空数据的恢复路径。
