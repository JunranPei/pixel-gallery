# o-kill 退出记录复查

本项保持未解决：已完成的模拟器用例验证的是应用堆增长导致的后台 Activity low-mem 回收；尚未在手机上验证厂商 o-kill 触发与修复效果。

## 18:25 的批量退出

证据文件：`scratch/viewer-trace-captures/live-4364-20261107-193904/events-buffer-after.txt`，2733–2756 行。

18:25:35.898–.918（20 毫秒）共 6 个进程收到 `o-kill(4010)`：系统搜索、Fitbit、Google Home、正式版 Gallery、Next Launcher、监听版 Gallery。

| 应用 | PID | 结束时间 | 原始原因内的数字 |
| --- | ---: | --- | --- |
| com.pixel.gallery.multitask | 12941 | 18:25:35.916 | `49MB, 65MB, 23MB` |
| com.pixel.gallery.viewertrace | 7273 | 18:25:35.918 | `226MB, 559MB, 485MB`，另有 `K\|U:28\|M:559,226` |

随后分别于 .948 和 18:25:36.112 记录 am_proc_died。系统计划重启两者的 GalleryNotificationListenerService。上述 OEM 数字的字段定义未核实，不将它们擅自标成 Java heap、PSS 或 RSS。

18:24:50–18:25:45 的窗口中共 27 条 am_kill：18:24:57 为 10 条，18:25:18 为 1 条，18:25:32 为 10 条，18:25:35 为 6 条；这是这一时间窗内的记录数，不把它们全认作同一轮或同一原因。结构化截取保存在 `okill-1825-events.json`。

系统同时记录 `am_mem_factor [3,0]`（18:24:57.813）、`[1,0]`（18:25:32.754）、`[3,1]`（18:25:36.499）。AOSP Android 16 的 EventLogTags 将两个值定义为当前、之前的等级，ProcessStats 将 3 定义为 CRITICAL。该状态与批量清理相邻；等级记录本身没有说明哪条厂商策略触发了 4010，也没有证明 Gallery 单独造成整机压力。

官方字段来源：
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-16.0.0_r1/services/core/java/com/android/server/am/EventLogTags.logtags
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-16.0.0_r1/core/java/com/android/internal/app/procstats/ProcessStats.java

## 更早的另一个原因码

`grid-live-20261107-004401-455/host-logcat-live.txt:7058` 的正式版退出是 11-06 23:36:18.281、`o-kill(2) ... with oneclick`。`live-4364-20261107-184244/events-buffer-after.txt:754` 在 17:39:07.559 也保留正式版的 `o-kill(2) ... with oneclick`。它们与 4010 分别登记，不推断是用户做了某个具体操作。

## 补证进度

- 复查 4010 周围事件已完成；网上未找到可核实该固件 4010 / U:28 定义的官方实现。其他设备的零散日志不用于解释本机原因码。
- 用户连接手机并要求“先补录，补录完再监听新出现的闪烁”后，电脑时间 21:20:31 完成一次性补读。完整记录保存在 `scratch/viewer-trace-captures/okill-retained-20261107-212031/`。两份退出历史、系统和 events 缓冲、Activity 状态及安装包信息均读取成功；`/proc/meminfo` 可读，`/proc/pressure/memory` 被设备拒绝访问，没有 PSI 数据。
- `scratch/listen_latest_phone.py` 已在电脑时间 21:20:46 启动新一轮采集，目录为 `live-4364-20261107-212046`，监听 PID 47496、logcat PID 17720。开始时退出历史、应用内存及系统内存记录均已产生；进程切换触发的补读尚待本轮实际事件验证。当前安装包最后更新时间为手机时间 16:16:25，仍是先前监听版，本轮不能登记为新修复包的复测。
- 修复包需在同一手机复测，同时检查 Activity 回收及整进程 o-kill；持续目标保持 active。

## 补读到的系统退出历史

以下时间来自手机的 `ApplicationExitInfo`；电脑时区时间另存于文件开头，不混用。

| 手机退出时间 | 应用 | PID | reason / subreason | description | 记录中的 RSS |
| --- | --- | ---: | --- | --- | ---: |
| 18:25:35.950 | 正式版 | 12941 | 13 / 6010 | o-kill(4010) | 112 MB |
| 18:25:36.123 | 监听版 | 7273 | 13 / 6010 | o-kill(4010) | 604 MB |
| 19:13:47.263 | 正式版 | 21621 | 13 / 6010 | o-kill(4010) | 103 MB |
| 19:13:47.359 | 监听版 | 22473 | 13 / 6010 | o-kill(4010) | 261 MB |

系统将这四项的 reason 标成 `OTHER KILLS BY SYSTEM`、subreason 标成 `UNKNOWN`。PSS 都记录为 `0.00`，不当作实际零占用。RSS 是退出历史中的明确字段，与上表 OEM 原因字符串中的无字段数字分开使用。

19:13 的监听版 PID 22473 正是上一轮六次闪烁期间仍存活的进程；这次退出发生在上一轮采集结束以后。新补读确认了另一次两个版本相隔 96 毫秒退出，但尚无该次厂商策略决定的完整记录，不能从 RSS 数值单独推出触发阈值或触发方。
